package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Bl0jv2_CharTest {

    @Test
    void toArrConvertsStringToCharArray() {
        assertEquals("[h, e, l, l, o]", run("print toArr('hello');"));
    }

    @Test
    void toArrElementIsIndexable() {
        assertEquals("h", run("print toArr('hello')[0];"));
    }

    @Test
    void directStringIndexingReturnsChar() {
        assertEquals("e", run("print 'hello'[1];"));
    }

    @Test
    void lenWorksOnCharArray() {
        assertEquals("5", run("print len(toArr('hello'));"));
    }

    @Test
    void lenWorksOnStringsToo() {
        assertEquals("5", run("print len('hello');"));
    }

    @Test
    void toArrDoesNotMutateTheSourceVariable() {
        assertEquals("hello", run("s = 'hello'; c = toArr(s); print s;"));
    }

    @Test
    void charWorksAsFunctionReturnValue() {
        assertEquals("w", run(
                "def firstChar(s) { return toArr(s)[0]; } " +
                "print firstChar('world');"));
    }

    @Test
    void negativeStringIndexReadsFromTheEnd() {
        assertEquals("o", run("print 'hello'[-1];"));
    }
}
