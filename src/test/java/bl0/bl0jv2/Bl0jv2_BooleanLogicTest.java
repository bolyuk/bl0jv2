package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Bl0jv2_BooleanLogicTest {

    // --- equality / comparisons / ternary ---

    @Test
    void equalityTrue() {
        assertEquals("true", run("print 5 == 5;"));
    }

    @Test
    void equalityFalse() {
        assertEquals("false", run("print 5 == 6;"));
    }

    @Test
    void inequality() {
        assertEquals("true", run("print 5 != 6;"));
    }

    @Test
    void lessThan() {
        assertEquals("true", run("print 3 < 5;"));
    }

    @Test
    void greaterThan() {
        assertEquals("false", run("print 3 > 5;"));
    }

    @Test
    void logicalNot() {
        assertEquals("false", run("print !true;"));
    }

    @Test
    void ternaryTakesTrueBranch() {
        assertEquals("yes", run("print 5 > 3 ? 'yes' : 'no';"));
    }

    @Test
    void ternaryTakesFalseBranch() {
        assertEquals("no", run("print 5 < 3 ? 'yes' : 'no';"));
    }

    // regression: <= and >= lexed/parsed fine but crashed the compiler with
    // an unhandled "Unknown op" RuntimeException
    @Test
    void lessEqualsTrueAtBoundary() {
        assertEquals("true", run("print 5 <= 5;"));
    }

    @Test
    void lessEqualsFalseAboveBoundary() {
        assertEquals("false", run("print 6 <= 5;"));
    }

    @Test
    void greaterEqualsTrueAtBoundary() {
        assertEquals("true", run("print 5 >= 5;"));
    }

    @Test
    void greaterEqualsFalseBelowBoundary() {
        assertEquals("false", run("print 4 >= 5;"));
    }

    // --- bitwise operators on ints ---

    @Test
    void bitwiseAnd() {
        assertEquals("8", run("print 12 & 10;"));
    }

    @Test
    void bitwiseOr() {
        assertEquals("14", run("print 12 | 10;"));
    }

    @Test
    void bitwiseXor() {
        assertEquals("6", run("print 12 ^ 10;"));
    }

    @Test
    void bitwiseNot() {
        assertEquals("-13", run("print ~12;"));
    }

    @Test
    void bitwiseNotDoesNotMutateTheSourceVariable() {
        assertEquals("5", run("a = 5; b = ~a; print a;"));
    }

    @Test
    void shiftLeft() {
        assertEquals("48", run("print 12 << 2;"));
    }

    @Test
    void shiftRight() {
        assertEquals("3", run("print 12 >> 2;"));
    }

    @Test
    void bitwiseOperatorsBindLooserThanComparison() {
        // matches the classic C precedence gotcha: '|' binds looser than
        // '==', so this needs its own parens around the bitwise part
        assertEquals("true", run("print (1 | 2) == 3;"));
    }

    @Test
    void shiftBindsTighterThanComparisonButLooserThanAddition() {
        assertEquals("true", run("print 1 << 2 + 1 == 8;"));
    }

    // --- short-circuit && / || ---

    @Test
    void andTruthTable() {
        assertEquals("true", run("print true && true;"));
        assertEquals("false", run("print true && false;"));
        assertEquals("false", run("print false && true;"));
        assertEquals("false", run("print false && false;"));
    }

    @Test
    void orTruthTable() {
        assertEquals("true", run("print true || true;"));
        assertEquals("true", run("print true || false;"));
        assertEquals("true", run("print false || true;"));
        assertEquals("false", run("print false || false;"));
    }

    @Test
    void andShortCircuitsAndSkipsRightSide() {
        assertEquals("", run(
                "def sideEffect() { println 'ran'; return true; } " +
                "r = false && sideEffect();"));
    }

    @Test
    void orShortCircuitsAndSkipsRightSide() {
        assertEquals("", run(
                "def sideEffect() { println 'ran'; return true; } " +
                "r = true || sideEffect();"));
    }

    @Test
    void andEvaluatesRightSideWhenLeftIsTrue() {
        assertEquals("\nran", run(
                "def sideEffect() { println 'ran'; return true; } " +
                "r = true && sideEffect();"));
    }

    @Test
    void shortCircuitAndPreventsOutOfBoundsArrayAccess() {
        // without short-circuit this would evaluate arr[i] regardless of
        // the left side and throw, even though i is out of range
        assertEquals("false", run("arr = [1, 2, 3]; i = 10; print i < len(arr) && arr[i] == 99;"));
    }

    @Test
    void bitwiseOrBindsTighterThanAnd() {
        // 'false && se1() | se2()' must parse as 'false && (se1() | se2())'
        // - if '&&' bound tighter than '|', se2() would run regardless of
        // the short-circuit (since '|' itself never short-circuits)
        assertEquals("", run(
                "def se1() { println 'se1'; return true; } " +
                "def se2() { println 'se2'; return true; } " +
                "r = false && se1() | se2();"));
    }

    @Test
    void andBindsTighterThanOr() {
        // 'true || se1() && se2()' must parse as 'true || (se1() && se2())'
        assertEquals("", run(
                "def se1() { println 'se1'; return true; } " +
                "def se2() { println 'se2'; return true; } " +
                "r = true || se1() && se2();"));
    }
}
