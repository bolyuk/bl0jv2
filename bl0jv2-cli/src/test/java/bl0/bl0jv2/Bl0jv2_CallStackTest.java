package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the register file of a call depth is reused by the next call at that depth: a callee must always start
// with every register nil, and nothing may leak between calls of different functions or after an unwind
class Bl0jv2_CallStackTest {

    @Test
    void aReusedFrameStartsWithNilRegisters() {
        // 'a' sets locals; 'b' runs at the same depth afterwards and must see its own locals as nil
        assertEquals("nil|nil|7", run(
                "def a() { x = 7; y = 8; return x; } "
                        + "def b() { return z; z = 1; } "
                        + "def c() { if (false) { q = 1; } return typeOf(q); } "
                        + "def d() { w = 0; return w; } "
                        + "a(); print str(b()) + '|' + str(c() == 'nil' ? nil : 'x') + '|' + str(a());"));
    }

    @Test
    void functionsOfDifferentSizesShareDepths() {
        assertEquals("6|120|13", run(
                "def small(n) { return n; } "
                        + "def big(n) { a = n; b = a + 1; c = b + 1; d = c + 1; e = d + 1; return a + b + c + d + e - 4 * n - 4; } "
                        + "def fact(n) { if (n <= 1) { return 1; } return n * fact(n - 1); } "
                        + "def fib(n) { if (n < 2) { return n; } return fib(n - 1) + fib(n - 2); } "
                        + "print str(small(6)) + '|' + str(fact(5)) + '|' + str(fib(7) + big(1) * 0);"));
    }

    @Test
    void mutualRecursionAndDeepRecursion() {
        assertEquals("true|false|50000", run(
                "def even(n) { if (n == 0) { return true; } return odd(n - 1); } "
                        + "def odd(n) { if (n == 0) { return false; } return even(n - 1); } "
                        + "def depth(n) { if (n == 0) { return 0; } return 1 + depth(n - 1); } "
                        + "print str(even(100)) + '|' + str(even(7)) + '|' + str(depth(50000));"));
    }

    @Test
    void anErrorUnwindsFramesAndTheNextCallsAreClean() {
        assertEquals("caught|ok|15", run(
                "def boom(n) { local = n * 2; if (n == 0) { return err('x') + 1; } return boom(n - 1) + local; } "
                        + "def sum(n) { if (n == 0) { return 0; } return n + sum(n - 1); } "
                        + "r = 'ok'; try { boom(5); } catch (e) { print 'caught|'; } "
                        + "print r + '|' + str(sum(5));"));
    }

    @Test
    void stackOverflowIsStillAnErrorAndTheVmKeepsWorking() {
        String out = run("def inf(n) { return inf(n + 1) + 1; } try { inf(0); } catch (e) { print 'overflow'; } print '|' + str(1 + 1);");
        assertTrue(out.startsWith("overflow") && out.endsWith("|2"), out);
    }

    @Test
    void closuresKeepTheirOwnVariablesAcrossReusedFrames() {
        assertEquals("1|2|3|10", run(
                "def counter() { n = 0; return () -> { n += 1; return n; }; } "
                        + "def noise(k) { a = k; b = k; return a + b; } "
                        + "c = counter(); x = c(); noise(5); y = c(); noise(9); z = c(); "
                        + "print str(x) + '|' + str(y) + '|' + str(z) + '|' + str(noise(5));"));
    }
}
