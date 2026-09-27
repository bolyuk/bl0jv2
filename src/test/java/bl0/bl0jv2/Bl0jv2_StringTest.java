package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_LexerException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bl0jv2_StringTest {

    @Test
    void stringConcatenation() {
        assertEquals("x=5", run("print 'x=' + 5;"));
    }

    @Test
    void stringConcatenatedWithBoolean() {
        assertEquals("ok=true", run("print 'ok=' + true;"));
    }

    @Test
    void stringRepetition() {
        assertEquals("ababab", run("print 'ab' * 3;"));
    }

    // regression: OperatorTable used to silently swap mismatched operands to
    // fit however the operator happened to be registered, so '+' produced
    // the same (wrong) result regardless of source-code operand order
    @Test
    void concatenationIsOrderSensitive() {
        assertEquals("5 apples", run("print 5 + ' apples';"));
        assertEquals("apples 5", run("print 'apples ' + 5;"));
    }

    @Test
    void stringRepetitionWorksInEitherOperandOrder() {
        assertEquals("ababab", run("print 'ab' * 3;"));
        assertEquals("ababab", run("print 3 * 'ab';"));
    }

    // regression: an unterminated string literal was silently truncated
    // instead of being reported as a lexer error
    @Test
    void unterminatedStringThrowsLexerException() {
        assertThrows(Bl0j_LexerException.class, () -> run("print 'unterminated;"));
    }

    // --- string escape sequences ---

    @Test
    void newlineEscape() {
        assertEquals("a\nb", run("print 'a\\nb';"));
    }

    @Test
    void tabEscape() {
        assertEquals("a\tb", run("print 'a\\tb';"));
    }

    @Test
    void escapedQuoteInsideString() {
        assertEquals("it's", run("print 'it\\'s';"));
    }

    @Test
    void escapedBackslash() {
        assertEquals("a\\b", run("print 'a\\\\b';"));
    }

    @Test
    void unknownEscapeSequenceThrows() {
        assertThrows(Bl0j_LexerException.class, () -> run("print 'a\\qb';"));
    }
}
