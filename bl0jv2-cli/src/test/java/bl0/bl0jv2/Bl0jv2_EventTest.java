package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// newEvent()/eventGen()/signalEvent()/waitEvent(): broadcast event with a
// generation counter and a real blocking wait (Condition, not a sleep loop) that also returns early for a
// deliverable interrupt so a core can still run its own ISR.
class Bl0jv2_EventTest {

    @Test
    void typeIsEvent() {
        assertEquals("event", run("print typeOf(newEvent());"));
    }

    @Test
    void signalAfterTheSnapshotIsNotLost() {
        // the whole point of the generation: signalled between reading gen
        // and waiting, the wait still returns immediately
        assertEquals("true", run("e = newEvent(); g = eventGen(e); signalEvent(e); print waitEvent(e, g, 1000);"));
    }

    @Test
    void waitWithAFreshSnapshotTimesOutWithFalse() {
        assertEquals("false", run("e = newEvent(); signalEvent(e); print waitEvent(e, eventGen(e), 20);"));
    }

    @Test
    void waitActuallyWaitsForTheTimeout() {
        assertEquals("true", run("e = newEvent(); g = eventGen(e); t = ticks(); waitEvent(e, g, 60); print ticks() - t >= 50;"));
    }

    @Test
    void generationAdvancesOnEverySignal() {
        assertEquals("2", run("e = newEvent(); g = eventGen(e); signalEvent(e); signalEvent(e); print eventGen(e) - g;"));
    }

    @Test
    void oneSignalReleasesEveryWaiter() {
        // two worker cores sleep on the same snapshot; a single signal
        // from core 0 must wake BOTH (a consumed-permit design would only
        // wake one and leave the other asleep until its timeout)
        String out = run(
                "def class Cfg { static field ev; static field gen; } " +
                "Cfg.ev = newEvent(); Cfg.gen = eventGen(Cfg.ev); poke32(0, 0); " +
                "def worker(arg) { if (waitEvent(Cfg.ev, Cfg.gen, 5000)) { atomicAdd(0, 1); } } " +
                "dispatch(worker, 1, 0); dispatch(worker, 2, 0); " +
                "wait(100); signalEvent(Cfg.ev); " +
                "wait(100); print peek32(0);",
                vm -> vm.set_core_count(3));
        assertEquals("2", out);
    }

    @Test
    void interruptRaisedFromAnotherThreadEndsTheWaitEarlyAndTheHandlerRuns() {
        // single core: its ISR can only run once waitEvent() hands control
        // back, so a 5 s wait that a host thread interrupts after ~60 ms
        // has to return long before the timeout
        assertEquals("fast|1", run(
                "def class S { static field hits; } S.hits = 0; " +
                "def isr(v) { S.hits = S.hits + 1; } " +
                "registerHandler(isr, 7, 1); " +
                "e = newEvent(); g = eventGen(e); t = ticks(); " +
                "waitEvent(e, g, 5000); " +
                "fast = ticks() - t < 2000; " +
                "i = 0; while (i < 50) { i = i + 1; } " +
                "print (fast ? 'fast' : 'slow') + '|' + str(S.hits);",
                vm -> {
                    Thread t = new Thread(() -> {
                        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                        vm.raiseInterrupt(7);
                    });
                    t.setDaemon(true);
                    t.start();
                }));
    }

    @Test
    void signalFromAnotherCoreWakesTheWaiter() {
        String out = run(
                "def class Cfg { static field ev; } " +
                "Cfg.ev = newEvent(); " +
                "def worker(arg) { wait(30); signalEvent(Cfg.ev); } " +
                "g = eventGen(Cfg.ev); dispatch(worker, 1, 0); " +
                "t = ticks(); " +
                "print waitEvent(Cfg.ev, g, 5000); " +
                "print '|' + str(ticks() - t < 2000);",
                vm -> vm.set_core_count(2));
        assertEquals("true|true", out);
    }
}
