package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.exceptions.Bl0j_ParserException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// x op= y is x = x op y; and a name that belongs to a function can't be reassigned
class Bl0jv2_CompoundAssignTest {

    @Test
    void arithmeticCompoundAssignment() {
        assertEquals("15|12|36|9|1", run(
                "x = 10; x += 5; a = x; x -= 3; b = x; x *= 3; c = x; x /= 4; d = x; x %= 2; " +
                "print str(a) + '|' + str(b) + '|' + str(c) + '|' + str(d) + '|' + str(x);"));
    }

    @Test
    void bitwiseAndShiftCompoundAssignment() {
        assertEquals("1|7|6|24|6|1073741823", run(
                "x = 3; x &= 5; a = x; x |= 6; b = x; x ^= 1; c = x; x <<= 2; d = x; x >>= 2; e = x; " +
                "y = -4; y >>>= 2; " +
                "print str(a) + '|' + str(b) + '|' + str(c) + '|' + str(d) + '|' + str(e) + '|' + str(y);"));
    }

    @Test
    void powerCompoundAssignment() {
        assertEquals("1024", run("x = 2; x **= 10; print x;"));
    }

    @Test
    void stringConcatenation() {
        assertEquals("abc", run("s = 'a'; s += 'b'; s += 'c'; print s;"));
    }

    @Test
    void theRightSideIsAFullExpression() {
        assertEquals("16", run("x = 4; x *= 1 + 3; print x;"));
    }

    @Test
    void worksOnFieldsAndArrayElements() {
        assertEquals("5|[1, 12, 3]", run(
                "def class C { field n; } c = new C(); c.n = 2; c.n += 3; a = [1, 2, 3]; a[1] += 10; " +
                "print str(c.n) + '|' + str(a);"));
    }

    @Test
    void worksInsideLoopsAndFunctions() {
        assertEquals("55", run("def sum(n) { t = 0; i = 1; while (i <= n) { t += i; i += 1; } return t; } print sum(10);"));
    }

    @Test
    void theOldOperatorsAreUnchanged() {
        assertEquals("true|true|true|2|1", run(
                "print str(1 <= 2) + '|' + str(2 >= 2) + '|' + str(1 != 2) + '|' + str(8 >> 2) + '|' + str(1 << 0);"));
    }

    @Test
    void aTargetWithACallWouldRunTwiceSoItIsRejected() {
        var e = assertThrows(Bl0j_ParserException.class, () -> run(
                "def idx() { return 0; } a = [1]; a[idx()] += 1;"));
        assertTrue(e.getMessage().contains("evaluated twice"), e.getMessage());
    }

    @Test
    void assigningToAFunctionNameIsACompileError() {
        var e = assertThrows(Bl0j_CompilerException.class, () -> run("def f() { return 1; } f = 5;"));
        assertTrue(e.getMessage().contains("cannot assign to 'f': it is the name of a function"), e.getMessage());
    }

    @Test
    void assigningToAClassNameIsACompileError() {
        var e = assertThrows(Bl0j_CompilerException.class, () -> run("def class A { field x; } A = 5;"));
        assertTrue(e.getMessage().contains("name of a class"), e.getMessage());
    }

    @Test
    void aParameterShadowsAFunctionOfTheSameNameInsideItsOwnFunction() {
        // 'handler' below is both a top-level function and g's parameter; inside
        // g the parameter wins, outside the function is still the function
        assertEquals("7|f", run(
                "def handler(x) { return 'f'; } def g(handler) { return handler; } " +
                "print str(g(7)) + '|' + handler(1);"));
    }

    @Test
    void aParameterShadowingAFunctionIsCalledNotTheFunction() {
        assertEquals("param", run(
                "def go() { return 'function'; } def run2(go) { return go(); } print run2(() -> 'param');"));
    }
}
