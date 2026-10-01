package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_LexerException;
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

    // --- hex / binary literals ---

    @Test
    void hexLiteral() {
        assertEquals("255", run("print 0xFF;"));
        assertEquals("16", run("print 0x10;"));
    }

    @Test
    void hexLiteralIsCaseInsensitive() {
        assertEquals("255", run("print 0xff;"));
        assertEquals("255", run("print 0XFF;"));
    }

    @Test
    void binaryLiteral() {
        assertEquals("10", run("print 0b1010;"));
    }

    // the whole point of writing a bit pattern in hex instead of decimal:
    // parseUnsignedInt (not parseInt) means a full 32-bit pattern is a
    // valid literal even though it reads as negative in decimal - matters
    // for masks/addresses in kernel-style code
    @Test
    void fullWidthHexLiteralIsValidEvenThoughItsNegativeAsASignedInt() {
        assertEquals("-1", run("print 0xFFFFFFFF;"));
        assertEquals("-559038737", run("print 0xDEADBEEF;"));
    }

    @Test
    void hexLiteralIsStillAnOrdinaryInt() {
        assertEquals("int", run("print typeOf(0x10);"));
    }

    @Test
    void hexLiteralWorksInArithmetic() {
        assertEquals("32", run("print 0x10 + 0x10;"));
    }

    @Test
    void bareRadixPrefixWithNoDigitsThrows() {
        assertThrows(Bl0j_LexerException.class, () -> run("print 0x;"));
    }
}
