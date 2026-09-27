package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Bl0jv2_VariablesTest {

    @Test
    void nilPrintsAsNil() {
        assertEquals("nil", run("x = nil; print x;"));
    }

    @Test
    void variableAssignmentAndReuse() {
        assertEquals("15", run("x = 10; x = x + 5; print x;"));
    }

    @Test
    void postfixIncrement() {
        assertEquals("6", run("i = 5; i++; print i;"));
    }

    @Test
    void postfixDecrement() {
        assertEquals("4", run("i = 5; i--; print i;"));
    }

    // regression: identifiers could not contain digits after the first
    // character (Bl0jv2_Lexer only allowed letters/underscore to continue)
    @Test
    void identifierMayContainDigits() {
        assertEquals("5", run("x1 = 5; print x1;"));
    }

    // regression: unary '-'/'!' used to mutate the operand's register in
    // place, so if the operand was a bare variable, negating it also
    // silently corrupted the variable itself (y = -x; also changed x)
    @Test
    void unaryMinusDoesNotMutateTheSourceVariable() {
        assertEquals("true", run("x = 5; y = -x; print x == 5;"));
    }

    @Test
    void unaryMinusProducesCorrectNegatedValue() {
        assertEquals("-5", run("x = 5; y = -x; print y;"));
    }

    @Test
    void unaryNotLeavesVariableUnchanged() {
        assertEquals("true", run("x = true; y = !x; print x == true;"));
    }
}
