package bl0.bl0jv2.runtime.interrupt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

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

    private record PendingInterrupt(int vector, int priority) implements Comparable<PendingInterrupt> {
        @Override
        public int compareTo(PendingInterrupt other) {
            return Integer.compare(other.priority, priority); // higher priority first
        }
    }

    private final Map<Integer, HandlerEntry> handlers = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<PendingInterrupt> pending = new PriorityBlockingQueue<>();
    private int pollInterval = 5;

    public void registerHandler(int vector, int priority, Object fn) {
        handlers.put(vector, new HandlerEntry(fn, priority));
    }

    public void raiseInterrupt(int vector) {
        HandlerEntry entry = handlers.get(vector);
        if (entry != null)
            pending.offer(new PendingInterrupt(vector, entry.priority()));
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
    public Fired pollNext() {
        PendingInterrupt next = pending.poll();
        if (next == null)
            return null;
        HandlerEntry entry = handlers.get(next.vector());
        if (entry == null)
            return null;
        return new Fired(next.vector(), entry.fn());
    }

    public int pollInterval() {
        return pollInterval;
    }

    public void setPollInterval(int pollInterval) {
        this.pollInterval = pollInterval;
    }

    public void reset() {
        handlers.clear();
        pending.clear();
    }
}
