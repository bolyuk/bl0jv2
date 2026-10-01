package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Bl0jv2_CharTest {

    // toArr isn't a language builtin - it's an ordinary stdlib.bl0/str/toArr.bl0
    // function, not auto-included, so any test that uses it defines its own
    // copy inline (Bl0jv2_TestRunner.run(String) has no filesystem access
    // for 'import')
    private static final String TOARR =
            "def toArr(s) { result = []; i = 0; " +
            "  while (i < len(s)) { push(result, s[i]); i = i + 1; } " +
            "  return result; } ";

    @Test
    void toArrConvertsStringToCharArray() {
        assertEquals("[h, e, l, l, o]", run(TOARR + "print toArr('hello');"));
    }

    @Test
    void toArrElementIsIndexable() {
        assertEquals("h", run(TOARR + "print toArr('hello')[0];"));
    }

    @Test
    void directStringIndexingReturnsChar() {
        assertEquals("e", run("print 'hello'[1];"));
    }

    @Test
    void lenWorksOnCharArray() {
        assertEquals("5", run(TOARR + "print len(toArr('hello'));"));
    }

    @Test
    void lenWorksOnStringsToo() {
        assertEquals("5", run("print len('hello');"));
    }

    @Test
    void toArrDoesNotMutateTheSourceVariable() {
        assertEquals("hello", run(TOARR + "s = 'hello'; c = toArr(s); print s;"));
    }

    @Test
    void charWorksAsFunctionReturnValue() {
        assertEquals("w", run(TOARR +
                "def firstChar(s) { return toArr(s)[0]; } " +
                "print firstChar('world');"));
    }

    @Test
    void negativeStringIndexReadsFromTheEnd() {
        assertEquals("o", run("print 'hello'[-1];"));
    }
}
