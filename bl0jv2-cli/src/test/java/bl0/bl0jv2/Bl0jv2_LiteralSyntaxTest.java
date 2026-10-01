package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_LexerException;
import bl0.bl0jv2.exceptions.Bl0j_ParserException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// number and string literals, and statements that do nothing
class Bl0jv2_LiteralSyntaxTest {

    @Test
    void floatsWithAnExponent() {
        assertEquals("1500.0|100000.0|0.0025|40.0", run(
                "print str(1.5e3) + '|' + str(1e5) + '|' + str(2.5E-3) + '|' + str(4e+1);"));
    }

    @Test
    void anExponentLiteralIsAFloat() {
        assertEquals("float", run("print typeOf(1e2);"));
    }

    @Test
    void hexLiteralsWithAnEAreStillIntegers() {
        assertEquals("229|int", run("print str(0xE5) + '|' + typeOf(0xE5);"));
    }

    @Test
    void lettersDirectlyAfterDigitsAreAnError() {
        var e = assertThrows(Bl0j_LexerException.class, () -> run("x = 5apples;"));
        assertTrue(e.getMessage().contains("invalid number literal"), e.getMessage());
    }

    @Test
    void anOutOfRangeIntegerLiteralIsAParserErrorWithAHint() {
        var e = assertThrows(Bl0j_ParserException.class, () -> run("print 99999999999;"));
        assertTrue(e.getMessage().contains("out of range") && e.getMessage().contains("99999999999.0"), e.getMessage());
    }

    @Test
    void anOversizedHexLiteralIsAParserError() {
        var e = assertThrows(Bl0j_ParserException.class, () -> run("print 0x1FFFFFFFF;"));
        assertTrue(e.getMessage().contains("does not fit in 32 bits"), e.getMessage());
    }

    @Test
    void theLargestIntAndFullWidthHexStillParse() {
        assertEquals("2147483647|-1", run("print str(2147483647) + '|' + str(0xFFFFFFFF);"));
    }

    @Test
    void doubleQuotedStrings() {
        assertEquals("hi there", run("print \"hi there\";"));
    }

    @Test
    void eachQuoteStyleMayContainTheOther() {
        assertEquals("it's|say \"hi\"", run("print \"it's\" + '|' + 'say \"hi\"';"));
    }

    @Test
    void escapesWorkInBothQuoteStyles() {
        assertEquals("a\"b|a'b|AB", run("print \"a\\\"b\" + '|' + 'a\\'b' + '|' + '\\x41\\x42';"));
    }

    @Test
    void anUnterminatedDoubleQuotedStringIsAnError() {
        assertThrows(Bl0j_LexerException.class, () -> run("print \"oops;"));
    }

    @Test
    void aStringMayContainANewlineAndLaterErrorsKeepTheirLine() {
        // the string spans two lines; the bad token after it is on line 3 (0-based 2)
        var e = assertThrows(Bl0j_LexerException.class, () -> run("print 'a\nb';\nx = 5apples;"));
        assertTrue(e.getMessage().contains("line: 2"), e.getMessage());
    }

    @Test
    void aBareLiteralStatementHasNoEffect() {
        var e = assertThrows(Bl0j_ParserException.class, () -> run("print 1 2;"));
        assertTrue(e.getMessage().contains("has no effect"), e.getMessage());
    }

    @Test
    void aBareVariableOrArithmeticStatementHasNoEffect() {
        assertThrows(Bl0j_ParserException.class, () -> run("x = 1; x;"));
        assertThrows(Bl0j_ParserException.class, () -> run("x = 1; x + 1;"));
        assertThrows(Bl0j_ParserException.class, () -> run("x = 1; x == 1;"));
    }

    @Test
    void realStatementsAreNotFlagged() {
        assertEquals("3|2", run(
                "def f() { return 1; } x = 0; x = x + 3; print str(x) + '|'; " +
                "x++; true && f(); y = 1; y == 1 ? f() : f(); print x - 2;"));
    }

    @Test
    void unclosedParenthesisReportsAPosition() {
        var e = assertThrows(Bl0j_ParserException.class, () -> run("print (1 + 2;"));
        assertTrue(e.getMessage().contains("expected ')'") && !e.getMessage().contains("line: -1"), e.getMessage());
    }
}
