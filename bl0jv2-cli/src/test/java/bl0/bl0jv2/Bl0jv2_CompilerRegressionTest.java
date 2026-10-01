package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// regressions that are compiler/VM-internal rather than about any one
// language feature
class Bl0jv2_CompilerRegressionTest {

    // word-sized (2-byte) instruction/register/address operands used to
    // silently wrap around past 65535 (corrupting jump targets) instead of
    // failing to compile
    @Test
    void programExceedingOperandLimitFailsToCompileInsteadOfCorrupting() {
        StringBuilder sb = new StringBuilder("x = 0; ");
        for (int i = 0; i < 40000; i++)
            sb.append("x = x + 1; ");
        sb.append("print x;");

        assertThrows(Bl0j_CompilerException.class, () -> run(sb.toString()));
    }

    // _instr_len() used to return byte, which sign-extends when widened to
    // int at call sites like 'startJump = _instr_len()' - any loop starting
    // at instruction address 128 or beyond got a negative address and was
    // wrongly rejected as if it had exceeded the limit
    @Test
    void loopStartingPastInstructionAddress128CompilesAndRuns() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++)
            sb.append("x = ").append(i).append("; ");
        sb.append("i = 0; while (i < 3) { i = i + 1; } print i;");

        assertEquals("3", run(sb.toString()));
    }

    // operands widened from 1 byte (0..255) to 2 bytes (0..65535): a loop
    // starting well past the old ceiling must compile and run correctly now
    @Test
    void loopStartingPastTheOldByteCeilingCompilesAndRuns() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++)
            sb.append("x = ").append(i).append("; ");
        sb.append("i = 0; while (i < 3) { i = i + 1; } print i;");

        assertEquals("3", run(sb.toString()));
    }

    // CALL_NATIVE writes the native call's own return value back into its
    // operand register - print/println/wait compiled that operand straight
    // from a bare variable's own register instead of a fresh copy, so
    // 'print x;' silently clobbered x to print's own status code (0).
    // Discovered writing a kernel-style program that printed the same
    // variable twice in a row.
    @Test
    void printingABareVariableTwiceDoesNotClobberItsValue() {
        assertEquals("55", run("x = 5; print x; print x;"));
    }

    @Test
    void waitingOnABareVariableDoesNotClobberItsValue() {
        assertEquals("2020", run("ms = 20; wait ms; print ms; print ms;"));
    }

    // raiseInterrupt()/panic() are built as CALL_NATIVE-based builtins too
    // and had the exact same bug: raiseInterrupt(vector) with vector a bare
    // variable silently clobbered it to nil (raiseInterrupt's own return
    // value) after the first call - a second raiseInterrupt(v) would then
    // pass nil to the native method and throw a ClassCastException instead
    // of reaching 'done'
    @Test
    void raisingTheSameVariableTwiceDoesNotClobberIt() {
        assertEquals("1done", run(
                "def h(v) { } registerHandler(h, 1, 1); " +
                "v = 1; raiseInterrupt(v); raiseInterrupt(v); print v; print 'done';",
                vm -> vm.set_interrupt_poll_interval(1000)));
    }
}
