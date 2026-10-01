package bl0.bl0jv2.runtime.interrupt;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Programmable timer: after a delay (once, or repeatedly) it raises an
 * interrupt vector, exactly like a real hardware timer asserting its IRQ
 * line. The handler then runs through the normal cooperative poll, so
 * nothing about delivery (priority, per-core masking, haltCore()/
 * waitEvent() wakeups) is timer-specific. One daemon thread serves every
 * timer, so an idle VM costs nothing and never keeps the JVM alive.
 *
 * <p>A fire whose vector has no registered handler is dropped by
 * {@link InterruptController#raiseInterrupt}, same as any other raise. An
 * interval keeps ticking regardless of whether the previous tick has been
 * handled yet - interrupts for the same vector can pile up, as with a real
 * level-triggered line left unserviced.
 */
public final class TimerService {
    private final InterruptController interrupts;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private volatile ScheduledThreadPoolExecutor executor; // created lazily

    public TimerService(InterruptController interrupts) {
        this.interrupts = interrupts;
    }

    private ScheduledThreadPoolExecutor executor() {
        ScheduledThreadPoolExecutor e = executor;
        if (e == null) {
            synchronized (this) {
                e = executor;
                if (e == null) {
                    e = new ScheduledThreadPoolExecutor(1, r -> {
                        Thread t = new Thread(r, "bl0jv2-timer");
                        t.setDaemon(true);
                        return t;
                    });
                    e.setRemoveOnCancelPolicy(true);
                    executor = e;
                }
            }
        }
        return e;
    }

    /** returns the timer's id (always > 0) for cancel() */
    public int start(int delayMs, int vector, boolean periodic) {
        if (delayMs < 1)
            throw new Bl0j_VM_Exception("timer delay must be at least 1 ms");
        int id = nextId.getAndIncrement();
        Runnable fire = () -> interrupts.raiseInterrupt(vector);
        ScheduledFuture<?> f = periodic
                ? executor().scheduleAtFixedRate(fire, delayMs, delayMs, TimeUnit.MILLISECONDS)
                : executor().schedule(() -> {
                    timers.remove(id);
                    fire.run();
                }, delayMs, TimeUnit.MILLISECONDS);
        timers.put(id, f);
        return id;
    }

    /** true if the timer was still pending (a one-shot that already fired, or an unknown id, is false) */
    public boolean cancel(int id) {
        ScheduledFuture<?> f = timers.remove(id);
        if (f == null)
            return false;
        return f.cancel(false);
    }

    public void cancelAll() {
        timers.values().forEach(f -> f.cancel(false));
        timers.clear();
    }
}
