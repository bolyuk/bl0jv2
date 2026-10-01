package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// a comparison feeding an if/while/for condition is one instruction (JUMP_IF_NOT_LESS ... JUMP_IF_EQ);
// it must behave exactly like the comparison followed by a conditional jump
class Bl0jv2_FusedJumpTest {

    private static String branches(String a, String b) {
        String body = "def t(a, b) { r = ''; "
                + "if (a < b) { r += 'lt '; } else { r += 'nlt '; } "
                + "if (a > b) { r += 'gt '; } else { r += 'ngt '; } "
                + "if (a <= b) { r += 'le '; } else { r += 'nle '; } "
                + "if (a >= b) { r += 'ge '; } else { r += 'nge '; } "
                + "if (a == b) { r += 'eq '; } else { r += 'neq '; } "
                + "if (a != b) { r += 'ne'; } else { r += 'nne'; } "
                + "return r; } ";
        return run(body + "print t(" + a + ", " + b + ");");
    }

    @Test
    void everyOperatorTakesTheRightBranch() {
        assertEquals("lt ngt le nge neq ne", branches("1", "2"));
        assertEquals("nlt gt nle ge neq ne", branches("2", "1"));
        assertEquals("nlt ngt le ge eq nne", branches("2", "2"));
        assertEquals("lt ngt le nge neq ne", branches("1", "1.5"));
        assertEquals("nlt ngt le ge eq nne", branches("'a'", "'a'"));
    }

    @Test
    void nanIsNeitherLessNorGreaterNorEqual() {
        // !(a < b) is not a >= b: with NaN both are false, so both else-branches run
        assertEquals("nlt ngt nle nge neq ne", branches("0.0 / 0.0", "1.0"));
    }

    @Test
    void loopsAndForsUseTheFusedJump() {
        assertEquals("10|45|3", run("i = 0; while (i < 10) { i += 1; } print str(i) + '|' + str(sum()) + '|' + str(down());"
                + " def sum() { s = 0; for (j = 0; j < 10; j += 1) { s += j; } return s; }"
                + " def down() { n = 6; c = 0; while (n != 0) { n -= 2; c += 1; } return c; }"));
    }

    @Test
    void aConditionThatIsNotAComparisonStillWorks() {
        assertEquals("a|b|c", run("t = true; f = false; x = 3; "
                + "if (t) { print 'a'; } if (f) { print 'x'; } else { print '|b'; } "
                + "ok = x < 5; if (ok) { print '|c'; }"));
    }

    @Test
    void aNonBooleanConditionIsStillAnError() {
        String out = run("try { if (1) { print 'no'; } } catch (e) { print 'error'; }");
        assertTrue(out.contains("error"), out);
    }

    @Test
    void comparingIncomparableValuesIsStillAnError() {
        String out = run("try { if ('a' < 1) { print 'no'; } } catch (e) { print 'error'; }");
        assertTrue(out.contains("error"), out);
    }
}
