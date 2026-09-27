package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bl0jv2_ArithmeticTest {

    @Test
    void addition() {
        assertEquals("3", run("print 1 + 2;"));
    }

    @Test
    void operatorPrecedence() {
        assertEquals("7", run("print 1 + 2 * 3;"));
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertEquals("9", run("print (1 + 2) * 3;"));
    }

    @Test
    void subtraction() {
        assertEquals("-1", run("print 4 - 5;"));
    }

    @Test
    void integerDivisionTruncates() {
        assertEquals("3", run("print 10 / 3;"));
    }

    @Test
    void remainder() {
        assertEquals("1", run("print 10 % 3;"));
    }

    @Test
    void divisionByZeroThrows() {
        Bl0j_VM_Exception ex = assertThrows(Bl0j_VM_Exception.class, () -> run("print 10 / 0;"));
        assertTrue(ex.getMessage().contains("division by zero"));
    }

    @Test
    void remainderByZeroThrows() {
        Bl0j_VM_Exception ex = assertThrows(Bl0j_VM_Exception.class, () -> run("print 10 % 0;"));
        assertTrue(ex.getMessage().contains("division by zero"));
    }

    @Test
    void unaryMinus() {
        assertEquals("-5", run("print -5;"));
    }

    // '**' was already lexed (Operator.STAR_STAR) and had a reserved opcode
    // slot set aside for it, but the parser never consumed the token and
    // nothing compiled/executed it
    @Test
    void powerOperator() {
        assertEquals("8", run("print 2 ** 3;"));
    }

    @Test
    void powerOperatorWithZeroExponent() {
        assertEquals("1", run("print 5 ** 0;"));
    }

    @Test
    void powerOperatorNegativeExponentThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("print 2 ** -1;"));
    }
}
