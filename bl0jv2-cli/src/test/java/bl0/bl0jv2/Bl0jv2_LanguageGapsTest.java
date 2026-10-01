package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Bl0jv2_LanguageGapsTest {

    @Test
    void staticMethodCanBeUsedAsAValue() {
        assertEquals("7", run("def class M { static def twice(x) { return x * 2; } } " +
                "f = M.twice; print f(3) + 1;"));
    }

    @Test
    void staticMethodValueCanBePassedAsACallback() {
        assertEquals("6", run("def class M { static def inc(x) { return x + 1; } } " +
                "def apply(f, v) { return f(v); } print apply(M.inc, 5);"));
    }

    @Test
    void bareReturnYieldsNil() {
        assertEquals("nil", run("def f() { return; } print str(f());"));
    }

    @Test
    void bareReturnBeforeClosingBrace() {
        assertEquals("ok", run("def f(x) { if (x) { return } print 'no'; } f(true); print 'ok';"));
    }

    @Test
    void instanceComparedToNil() {
        assertEquals("1 2", run("def class A { field x; } def f(a) { if (a == nil) { return 1; } return 2; } " +
                "print str(f(nil)) + ' ' + str(f(new A()));"));
    }

    @Test
    void doubleQuoteAndHexEscapes() {
        assertEquals("\"A\"", run("print '\\\"\\x41\\\"';"));
    }

    @Test
    void nilCheckAfterStaticFactoryInsideStaticMethod() {
        assertEquals("sent", run("def class A { field x; static def make() { return new A(); } " +
                "def send(m) { return 1; } } " +
                "def class B { static def get() { c = A.make(); if (c == nil) { return nil; } c.send('x'); return 'sent'; } } " +
                "print B.get();"));
    }
}
