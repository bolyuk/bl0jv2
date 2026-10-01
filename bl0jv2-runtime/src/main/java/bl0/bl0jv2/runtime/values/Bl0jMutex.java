package bl0.bl0jv2.runtime.values;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A mutual-exclusion lock for kernel code (bl0jv2) to protect its own
 * cross-core shared structures - e.g. a run queue an OS-level scheduler
 * mutates from multiple cores. Distinct from disableInterrupts()/
 * enableInterrupts(), which only guards against interrupt-handler
 * reentrancy on one core and does nothing for races between cores.
 *
 * <p>Backed by a real {@link ReentrantLock}: reentrant specifically so
 * re-locking from the same core is a no-op (mirroring the mental model
 * disableInterrupts()'s own nesting-safe counter already establishes),
 * while still genuinely blocking a *different* core's Java thread - other
 * cores are independent threads, so one blocking in lock() doesn't stall
 * the rest.
 */
public final class Bl0jMutex {
    private final ReentrantLock lock = new ReentrantLock();

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        if (!lock.isHeldByCurrentThread())
            throw new Bl0j_VM_Exception("unlock: mutex not held by this core");
        lock.unlock();
    }

    @Override
    public String toString() {
        return "mutex";
    }
}
