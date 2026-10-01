package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// <, >, <=, >= and the bool-only places (conditions, '!'): fast int/double
// paths, NaN semantics, string/char ordering, and clean errors for the rest
class Bl0jv2_ComparisonTest {

    @Test
    void integerComparisons() {
        assertEquals("true|false|true|true|false|true", run(
                "print str(1 < 2) + '|' + str(2 < 1) + '|' + str(2 <= 2) + '|' + str(3 >= 3) + '|' + str(1 >= 2) + '|' + str(5 > 4);"));
    }

    @Test
    void mixedIntAndDoubleComparisons() {
        assertEquals("true|true|false", run("print str(1 < 1.5) + '|' + str(2.5 >= 2) + '|' + str(3 <= 2.9);"));
    }

    @Test
    void everyComparisonWithNaNIsFalse() {
        // NaN <= 1 used to come out true: <= was compiled as NOT (a > b)
        assertEquals("false|false|false|false|false", run(
                "n = 0.0 / 0.0; print str(n < 1.0) + '|' + str(n > 1.0) + '|' + str(n <= 1.0) + '|' + str(n >= 1.0) + '|' + str(n == n);"));
    }

    @Test
    void stringsCompareByCharacterOrder() {
        assertEquals("true|false|true|true", run(
                "print str('a' < 'b') + '|' + str('b' < 'a') + '|' + str('abc' <= 'abc') + '|' + str('abd' > 'abc');"));
    }

    @Test
    void charsCompareLikeOneLetterStrings() {
        assertEquals("true|true", run("s = 'ab'; print str(s[0] < s[1]) + '|' + str(s[1] >= s[0]);"));
    }

    @Test
    void comparingIncompatibleTypesNamesBoth() {
        assertEquals("cannot compare int with string", run(
                "try { x = 1 < 'a'; } catch (e) { print e; }"));
    }

    @Test
    void nilIsNotOrderable() {
        assertEquals("cannot compare nil with int", run(
                "try { x = nil < 1; } catch (e) { print e; }"));
    }

    @Test
    void aNonBoolConditionIsAnError() {
        assertEquals("condition must be a bool, got int", run(
                "try { if (5) { print 'yes'; } } catch (e) { print e; }"));
    }

    @Test
    void nilIsNotAConditionEither() {
        assertEquals("condition must be a bool, got nil", run(
                "x = nil; try { while (x) { print 'loop'; break; } } catch (e) { print e; }"));
    }

    @Test
    void notRequiresABool() {
        assertEquals("operand of '!' must be a bool, got int", run(
                "try { x = !5; } catch (e) { print e; }"));
    }

    @Test
    void logicalOperatorsRequireBoolsToo() {
        assertEquals("condition must be a bool, got int", run(
                "try { x = 5 && true; } catch (e) { print e; }"));
    }

    @Test
    void arithmeticFastPathsKeepIntOverflowAndMixedBehaviour() {
        assertEquals("-2147483648|3.5|7|6.0", run(
                "big = 2147483647; print str(big + 1) + '|' + str(1 + 2.5) + '|' + str(10 - 3) + '|' + str(2.0 * 3.0);"));
    }

    @Test
    void equalityFastPaths() {
        assertEquals("true|false|true|false|true", run(
                "print str(3 == 3) + '|' + str(3 == 4) + '|' + str(true == true) + '|' + str(true == false) + '|' + str(1 == 1.0);"));
    }
}
