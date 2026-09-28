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
}
