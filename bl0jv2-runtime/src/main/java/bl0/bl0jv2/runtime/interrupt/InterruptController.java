package bl0.bl0jv2.runtime.interrupt;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Interrupt registration and the pending queue, kept separate from
 * {@code Bl0jv2_jVM} itself. A vector can have at most one registered
 * handler (fn: a FunDef or Bl0jClosure - deliberately untyped here so this
 * class doesn't need to depend on {@code runtime.values}); raising an
 * unregistered vector is silently dropped. Pending vectors fire
 * highest-priority-first, not FIFO. {@link #raiseInterrupt(int)} is
 * thread-safe (backed by a {@link PriorityBlockingQueue}) so a host-side
 * timer/device thread can call it directly, not just the interpreter loop -
 * {@link #handlers} is a {@link ConcurrentHashMap} for the same reason, now
 * that multiple VM cores can call {@link #registerHandler} concurrently
 * with {@link #raiseInterrupt}/{@link #pollNext}.
 *
 * <p>Interrupt <em>masking</em> (disableInterrupts()/enableInterrupts()) is
 * deliberately NOT this class's concern, even though it used to live here -
 * real hardware gives each CPU core its own interrupt-enable flag, so
 * masking is per-core state and lives on the VM's own per-core context
 * instead. This class only tracks whether something is pending; the caller
 * decides whether it's allowed to ask.
 */
public final class InterruptController {

    public record Fired(int vector, Object handlerFn) {}

    private record HandlerEntry(Object fn, int priority) {}

    // target == ANY_CORE: whichever core polls first takes it (every
    // ordinary device IRQ); otherwise only that core ever will (an IPI -
    // see raiseInterruptOn)
    public static final int ANY_CORE = -1;

    private record PendingInterrupt(int vector, int priority, int target) {}

    // 256 entries, matching a real x86 IDT's fixed size - registerHandler/
    // raiseInterrupt/handlerFor all reject anything outside this range, so
    // a future real-hardware backend can size its own IDT identically
    private static final int VECTOR_COUNT = 256;

    private final Map<Integer, HandlerEntry> handlers = new ConcurrentHashMap<>();
    // guarded by its own monitor; highest priority wins, ties go to the
    // one raised first. A plain list (not a PriorityBlockingQueue) because
    // a core has to skip entries addressed to a different core.
    private final List<PendingInterrupt> pending = new ArrayList<>();
    private int pollInterval = 5;

    // one monitor shared by every blocking waiter (Bl0jEvent.await) and by
    // raiseInterrupt(): a core blocked in waitEvent() must wake the moment
    // either its event is signalled OR an interrupt becomes pending, and a
    // single shared condition is the simplest way to get both without
    // polling. Waiters re-check their own predicate after every wakeup, so
    // a signalAll() that wakes someone else's event is harmless.
    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Condition wakeCond = wakeLock.newCondition();

    public ReentrantLock wakeLock() { return wakeLock; }
    public Condition wakeCond() { return wakeCond; }

    /** wakes every thread blocked on the shared monitor (see wakeLock) */
    public void wakeWaiters() {
        wakeLock.lock();
        try {
            wakeCond.signalAll();
        } finally {
            wakeLock.unlock();
        }
    }

    private static void checkVector(int vector) {
        if (vector < 0 || vector >= VECTOR_COUNT)
            throw new Bl0j_VM_Exception("interrupt vector out of range [0, " + VECTOR_COUNT + "): " + vector);
    }

    public void registerHandler(int vector, int priority, Object fn) {
        checkVector(vector);
        handlers.put(vector, new HandlerEntry(fn, priority));
    }

    public void raiseInterrupt(int vector) {
        raiseInterruptOn(ANY_CORE, vector);
    }

    /**
     * Inter-processor interrupt: only core {@code targetCore} will ever take
     * this one (ANY_CORE = the ordinary "first core to poll" behaviour).
     * The target must be a real core - the caller range-checks it against
     * the core count, this class doesn't know it.
     */
    public void raiseInterruptOn(int targetCore, int vector) {
        checkVector(vector);
        HandlerEntry entry = handlers.get(vector);
        if (entry != null) {
            synchronized (pending) {
                pending.add(new PendingInterrupt(vector, entry.priority(), targetCore));
            }
        }
        wakeWaiters();
    }

    /** every registered handler function (a FunDef or a Bl0jClosure) - the collector's roots */
    public void forEachHandlerFn(java.util.function.Consumer<Object> action) {
        for (HandlerEntry entry : handlers.values())
            action.accept(entry.fn());
    }

    // synchronous lookup for syscall(): the same vector table raiseInterrupt/
    // pollNext use for async hardware IRQs, just read without touching the
    // pending queue at all - a syscall is delivered immediately, not queued
    public Object handlerFor(int vector) {
        checkVector(vector);
        HandlerEntry entry = handlers.get(vector);
        return entry == null ? null : entry.fn();
    }

    // returns the highest-priority pending interrupt's vector + handler, or
    // null when nothing is pending. Callers are expected to check their own
    // masking state before calling this at all (see the VM's per-core
    // disableDepth) - a raise while the caller is masked still queues
    // normally (see raiseInterrupt), it simply isn't polled for until the
    // caller stops skipping this call, same as a level-triggered hardware
    // interrupt staying asserted while masked. A vector unregistered
    // between raise and poll is treated as dropped, same as one that was
    // never registered.
    public Fired pollNext(int coreId) {
        PendingInterrupt next = null;
        synchronized (pending) {
            for (PendingInterrupt p : pending) {
                if (p.target() != ANY_CORE && p.target() != coreId)
                    continue;
                if (next == null || p.priority() > next.priority())
                    next = p;
            }
            if (next == null)
                return null;
            pending.remove(next); // removes the first equal entry - same vector/priority/target, indistinguishable
        }
        HandlerEntry entry = handlers.get(next.vector());
        if (entry == null)
            return null;
        return new Fired(next.vector(), entry.fn());
    }

    // used by haltCore()/waitEvent() to block a core until there's
    // something worth waking up for, without actually consuming it
    // (that's pollNext()'s job, respecting priority and the caller's own
    // masking state). Per core: an IPI for another core is not worth
    // waking this one up for.
    public boolean hasPending(int coreId) {
        synchronized (pending) {
            for (PendingInterrupt p : pending)
                if (p.target() == ANY_CORE || p.target() == coreId)
                    return true;
            return false;
        }
    }

    public int pollInterval() {
        return pollInterval;
    }

    public void setPollInterval(int pollInterval) {
        this.pollInterval = pollInterval;
    }

    public void reset() {
        handlers.clear();
        synchronized (pending) {
            pending.clear();
        }
    }
}
