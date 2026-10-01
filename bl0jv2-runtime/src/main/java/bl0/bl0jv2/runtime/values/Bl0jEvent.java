package bl0.bl0jv2.runtime.values;

import bl0.bl0jv2.runtime.interrupt.InterruptController;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A broadcast event with a generation counter. signal() bumps the
 * generation and wakes EVERY waiter; await(seenGen, ...) sleeps until the
 * generation differs from a value the caller snapshotted earlier with
 * generation().
 *
 * <p>The snapshot is what makes this free of lost wakeups with several
 * cores: a waiter reads the generation, THEN checks its own condition, THEN
 * waits on that snapshot. A signal that lands anywhere after the snapshot -
 * including between the condition check and the wait - makes await() return
 * at once. A plain "permit" semaphore does not give this: with two cores
 * sharing one event, one can consume the permit for a change the other was
 * waiting on, leaving the other asleep on state that already changed.
 * Callers must still loop - a wakeup only means "something happened".
 *
 * <p>Blocking is a real Condition wait on the monitor owned by
 * {@link InterruptController}, not a sleep-poll loop, so a signal wakes the
 * waiter immediately. That monitor is also signalled by raiseInterrupt(),
 * which is what lets await() hand control back the moment a deliverable
 * interrupt is pending: a core cannot run its own ISR while it sits inside
 * a native call, so without that early return a single-core program waiting
 * on a frame its own ISR is about to deliver would only wake by timeout.
 */
public final class Bl0jEvent {
    private final InterruptController controller;
    private int generation = 0; // guarded by controller.wakeLock()

    public Bl0jEvent(InterruptController controller) {
        this.controller = controller;
    }

    public int generation() {
        var lock = controller.wakeLock();
        lock.lock();
        try {
            return generation;
        } finally {
            lock.unlock();
        }
    }

    public void signal() {
        var lock = controller.wakeLock();
        lock.lock();
        try {
            generation++;
            controller.wakeCond().signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until generation != seenGen (returns true), or until timeoutMs
     * elapses / stop.getAsBoolean() turns true (returns false). A negative
     * timeout waits indefinitely, but still re-checks stop at least every
     * 50 ms so a machine-wide panic can't leave a core blocked forever.
     */
    public boolean await(int seenGen, long timeoutMs, BooleanSupplier stop) throws InterruptedException {
        long deadline = timeoutMs < 0 ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        var lock = controller.wakeLock();
        lock.lock();
        try {
            while (true) {
                if (generation != seenGen)
                    return true;
                if (stop.getAsBoolean())
                    return false;
                long remaining = timeoutMs < 0 ? Long.MAX_VALUE : deadline - System.nanoTime();
                if (remaining <= 0)
                    return false;
                controller.wakeCond().awaitNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return "event";
    }
}
