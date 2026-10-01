package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// s[i] is a char; a one-character string and that char are interchangeable
class Bl0jv2_CharStringTest {

    @Test
    void aCharEqualsAOneCharacterString() {
        assertEquals("true|true|false", run("s = 'abc'; print str(s[0] == 'a') + '|' + str('b' == s[1]) + '|' + str(s[2] == 'x');"));
    }

    @Test
    void aCharDoesNotEqualALongerString() {
        assertEquals("false|false", run("s = 'abc'; print str(s[0] == 'ab') + '|' + str(s[0] == '');"));
    }

    @Test
    void notEqualsWorksToo() {
        assertEquals("false|true", run("s = 'abc'; print str(s[0] != 'a') + '|' + str(s[0] != 'b');"));
    }

    @Test
    void twoCharsConcatenateIntoAString() {
        assertEquals("ab|string", run("s = 'ab'; t = s[0] + s[1]; print t + '|' + typeOf(t);"));
    }

    @Test
    void charsStillCompareWithEachOther() {
        assertEquals("true|false", run("s = 'aa'; print str(s[0] == s[1]) + '|' + str(s[0] != s[1]);"));
    }

    @Test
    void splittingOnACharacterWorksWithoutTheIndexTrick() {
        // stdlib needed sp[0] on a one-char literal to compare against a char
        assertEquals("a|b|c", run(
                "s = 'a,b,c'; out = ''; part = ''; i = 0; " +
                "while (i < len(s)) { if (s[i] == ',') { out = out + part + '|'; part = ''; } else { part = part + s[i]; } i = i + 1; } " +
                "print out + part;"));
    }
}
