package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertThrows;

// regressions that are compiler/VM-internal rather than about any one
// language feature
class Bl0jv2_CompilerRegressionTest {

    // byte-sized instruction/register/address operands used to silently
    // wrap around past 255 (corrupting jump targets) instead of failing to
    // compile
    @Test
    void programExceedingByteAddressLimitFailsToCompileInsteadOfCorrupting() {
        StringBuilder sb = new StringBuilder("i = 0; a = 0; while (i < 1) { ");
        for (int i = 0; i < 70; i++)
            sb.append("a = a + 1; ");
        sb.append("i = i + 1; } print a;");

        assertThrows(Bl0j_CompilerException.class, () -> run(sb.toString()));
    }
}
