package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// ticks(): milliseconds elapsed since feed_compiled_file(), a real
// wall-clock monotonic source (matches wait()'s own use of real time).
class Bl0jv2_TicksTest {

    @Test
    void ticksIsAnOrdinaryInt() {
        assertEquals("int", run("print typeOf(ticks());"));
    }

    @Test
    void ticksNeverDecreases() {
        assertEquals("true", run("t1 = ticks(); t2 = ticks(); print t2 >= t1;"));
    }

    // proves ticks() actually reflects elapsed wall-clock time, not just a
    // constant or an instruction counter
    @Test
    void ticksAdvancesAfterWaiting() {
        assertEquals("true", run("t1 = ticks(); wait(20); t2 = ticks(); print t2 > t1;"));
    }
}
