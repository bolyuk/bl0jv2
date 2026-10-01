package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// registers start as nil (not the double 0.0 an all-zero bit pattern decodes
// to), and a variable that is read but never assigned anywhere is a compile
// error rather than a silent nil
class Bl0jv2_UninitializedTest {

    @Test
    void aVariableReadAboveItsFirstAssignmentInALoopStartsAsNil() {
        // legitimate: the first iteration sees nil, later ones the previous value
        assertEquals("nil|int|int|", run(
                "i = 0; out = ''; while (i < 3) { out = out + typeOf(prev) + '|'; prev = i; i = i + 1; } " +
                "print out;"));
    }

    @Test
    void readingAnUnassignedVariableIsACompileError() {
        assertThrows(Bl0j_CompilerException.class, () -> run("print neverAssigned;"));
    }

    @Test
    void theErrorNamesTheVariableAndTheFunction() {
        var e = assertThrows(Bl0j_CompilerException.class, () -> run("def f() { return missing + 1; } print f();"));
        assertEquals(true, e.getMessage().contains("undefined variable 'missing'") && e.getMessage().contains("function f"));
    }

    @Test
    void aLambdaReadingAVariableAssignedOnlyAfterItIsACompileError() {
        // the lambda cannot capture 'later' - it does not exist yet - and would
        // silently read nil forever
        assertThrows(Bl0j_CompilerException.class, () -> run("f = () -> later; later = 5; print f();"));
    }

    @Test
    void parametersAndCapturesAreNotUndefined() {
        assertEquals("7", run("k = 3; f = (x) -> x + k; print f(4);"));
    }
}
