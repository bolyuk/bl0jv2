package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// raiseInterruptOn(core, vector): an inter-processor interrupt - only the
// named core ever takes it, unlike raiseInterrupt() where whichever core
// polls first does.
class Bl0jv2_IpiTest {

    @Test
    void ipiToCoreZeroRunsTheHandlerOnCoreZero() {
        assertEquals("0", run(
                "def class S { static field core; } S.core = -1; " +
                "def isr(v) { S.core = currentCore(); } " +
                "registerHandler(isr, 9, 1); " +
                "raiseInterruptOn(0, 9); " +
                "i = 0; while (i < 50) { i = i + 1; } " +
                "print S.core;"));
    }

    @Test
    void ipiToAWorkerRunsTheHandlerOnThatWorkerNotOnCoreZero() {
        // core 0 keeps busy-looping long enough that, with a plain
        // raiseInterrupt(), it would very likely take the interrupt itself;
        // an IPI must reach core 2 regardless
        String out = run(
                "def class S { static field core; } S.core = -1; " +
                "def isr(v) { S.core = currentCore(); } " +
                "registerHandler(isr, 9, 1); " +
                "def idler(arg) { i = 0; while (i < 20000) { i = i + 1; wait(0); } } " +
                "dispatch(idler, 1, 0); dispatch(idler, 2, 0); " +
                "wait(50); raiseInterruptOn(2, 9); " +
                "i = 0; while (i < 200 && S.core == -1) { i = i + 1; wait(5); } " +
                "print S.core;",
                vm -> vm.set_core_count(3));
        assertEquals("2", out);
    }

    @Test
    void ipiToANonexistentCoreIsAnError() {
        assertEquals("err", run(
                "try { raiseInterruptOn(5, 9); } catch (e) { print typeOf(e); }"));
    }
}
