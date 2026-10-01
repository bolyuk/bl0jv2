package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// setTimer/setInterval/cancelTimer: a host timer thread raises the vector
// after the delay; the handler then runs through the normal interrupt poll.
class Bl0jv2_TimerTest {

    // waitEvent() returns early for a deliverable interrupt (see
    // Bl0jEvent), so waiting on a never-signalled event with a generous
    // timeout is a blocking wait that a timer interrupt cuts short
    private static final String HANDLER =
            "def class S { static field hits; } S.hits = 0; " +
            "def isr(v) { S.hits = S.hits + 1; } " +
            "registerHandler(isr, 11, 1); " +
            "e = newEvent(); ";

    @Test
    void oneShotTimerFiresOnceAfterTheDelay() {
        assertEquals("true|1", run(HANDLER +
                "t = ticks(); id = setTimer(60, 11); " +
                "waitEvent(e, eventGen(e), 5000); " +
                "i = 0; while (i < 50) { i = i + 1; } " +
                "ok = ticks() - t >= 50 && ticks() - t < 2000; " +
                "wait(150); i = 0; while (i < 50) { i = i + 1; } " +
                "print str(ok) + '|' + str(S.hits);"));
    }

    @Test
    void cancelledTimerNeverFires() {
        assertEquals("true|0", run(HANDLER +
                "id = setTimer(60, 11); ok = cancelTimer(id); " +
                "wait(200); i = 0; while (i < 50) { i = i + 1; } " +
                "print str(ok) + '|' + str(S.hits);"));
    }

    @Test
    void cancellingAnAlreadyFiredOneShotReturnsFalse() {
        assertEquals("false", run(HANDLER +
                "id = setTimer(20, 11); wait(150); print cancelTimer(id);"));
    }

    @Test
    void intervalKeepsFiringUntilCancelled() {
        assertEquals("true|true", run(HANDLER +
                "id = setInterval(20, 11); " +
                "i = 0; while (i < 100 && S.hits < 3) { i = i + 1; wait(10); } " +
                "reached = S.hits >= 3; cancelTimer(id); " +
                "wait(60); i = 0; while (i < 50) { i = i + 1; } " +
                "settled = S.hits; wait(120); i = 0; while (i < 50) { i = i + 1; } " +
                "print str(reached) + '|' + str(S.hits == settled);"));
    }

    @Test
    void zeroDelayIsAnError() {
        assertEquals("err", run("try { setTimer(0, 11); } catch (e) { print typeOf(e); }"));
    }
}
