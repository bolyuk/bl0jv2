package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// strings can't grow without bound, '**' is fast, and it parses the way
// everyone expects
class Bl0jv2_ArithmeticLimitsTest {

    @Test
    void negativeRepeatCountIsAnError() {
        assertEquals("negative repeat count: -1", run("try { s = 'ab' * -1; } catch (e) { print e; }"));
    }

    @Test
    void repeatingPastTheLimitIsAnErrorNotAnOutOfMemory() {
        String out = run("try { s = 'x' * 2000000000; } catch (e) { print e; }");
        assertTrue(out.startsWith("string too long: repeating 1 characters 2000000000 times"), out);
    }

    @Test
    void concatenationPastTheLimitIsAnError() {
        assertEquals("string too long: concatenation would exceed 100 characters", run(
                "s = 'x' * 60; try { t = s + s; } catch (e) { print e; }",
                vm -> vm.set_max_string_length(100)));
    }

    @Test
    void stringsUnderTheLimitAreUnaffected() {
        assertEquals("120", run("s = 'ab' * 20; t = s + s + s; print len(t);", vm -> vm.set_max_string_length(120)));
    }

    @Test
    void hugeIntegerExponentsAreFast() {
        // looped once per exponent unit before: seconds for 2 ** 2000000000
        long start = System.nanoTime();
        assertEquals("0|1|-2147483648", run("print str(2 ** 2000000000) + '|' + str(7 ** 0) + '|' + str(2 ** 31);"));
        assertTrue((System.nanoTime() - start) < 2_000_000_000L);
    }

    @Test
    void integerPowersKeepTheirWrappingValues() {
        assertEquals("689956897|-8|1|1024", run("print str(3 ** 40) + '|' + str((-2) ** 3) + '|' + str(0 ** 0) + '|' + str(2 ** 10);"));
    }

    @Test
    void powerBindsTighterThanUnaryMinusOnItsLeft() {
        assertEquals("-4|-9|4", run("x = 3; print str(-2 ** 2) + '|' + str(-x ** 2) + '|' + str((-2) ** 2);"));
    }

    @Test
    void powerIsRightAssociative() {
        assertEquals("512|262144", run("print str(2 ** 3 ** 2) + '|' + str(2 ** 3 ** 2 ** 1 * 512);"));
    }

    @Test
    void powerBindsTighterThanMultiplication() {
        assertEquals("18|12", run("print str(2 * 3 ** 2) + '|' + str(3 ** 2 + 3);"));
    }

    @Test
    void theExponentMayBeNegated() {
        assertEquals("0.5", run("print 2.0 ** -1;"));
    }

    @Test
    void powerOnTheRightOfAComparisonStillParses() {
        assertEquals("true", run("print 2 ** 4 == 16;"));
    }
}
