package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bl0jv2_TupleTest {

    // --- tuples ---

    @Test
    void tupleLiteralPrintsWithParens() {
        assertEquals("(1, 2, 3)", run("print (1, 2, 3);"));
    }

    @Test
    void tupleIndexingAndNegativeIndex() {
        assertEquals("1", run("t = (1, 2, 3); print t[0];"));
        assertEquals("3", run("t = (1, 2, 3); print t[-1];"));
    }

    @Test
    void tupleLen() {
        assertEquals("3", run("print len((1, 2, 3));"));
    }

    @Test
    void typeOfAndIsTupleDistinguishFromArray() {
        assertEquals("tuple", run("print typeOf((1, 2));"));
        assertEquals("true", run("print isTuple((1, 2));"));
        assertEquals("false", run("print isArray((1, 2));"));
    }

    @Test
    void tuplesCompareByContentNotReference() {
        assertEquals("true", run("print (1, 2) == (1, 2);"));
        assertEquals("false", run("print (1, 2) == (1, 3);"));
    }

    @Test
    void tupleIndexAssignmentThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("t = (1, 2, 3); t[0] = 99;"));
    }

    @Test
    void pushOnTupleThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("t = (1, 2, 3); push(t, 4);"));
    }

    @Test
    void functionCanReturnATuple() {
        assertEquals("(2, 5)", run(
                "def minMax(a, b) { if (a < b) { return (a, b); } return (b, a); } " +
                "print minMax(5, 2);"));
    }

    // --- destructuring assignment ---

    @Test
    void destructureFromFunctionReturningTuple() {
        assertEquals("lo=2 hi=5", run(
                "def minMax(a, b) { if (a < b) { return (a, b); } return (b, a); } " +
                "lo, hi = minMax(5, 2); " +
                "print 'lo=' + lo + ' hi=' + hi;"));
    }

    @Test
    void destructureSwapWithoutTempVariable() {
        assertEquals("x=2 y=1", run("x = 1; y = 2; x, y = y, x; print 'x=' + x + ' y=' + y;"));
    }

    @Test
    void destructureFromArrayLiteral() {
        assertEquals("p=10 q=20 r=30", run(
                "p, q, r = [10, 20, 30]; print 'p=' + p + ' q=' + q + ' r=' + r;"));
    }

    @Test
    void destructureFromBareCommaValues() {
        assertEquals("a=100 b=200", run("a, b = 100, 200; print 'a=' + a + ' b=' + b;"));
    }

    @Test
    void destructureArityMismatchThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("m, n = (1, 2, 3);"));
    }

    @Test
    void plainFunctionCallArgumentsAreNotMisreadAsDestructuring() {
        // f(a, b) must still work normally - the destructuring lookahead
        // must not misfire just because a comma-separated identifier list
        // appears before a ')'
        assertEquals("3", run("def add(a, b) { return a + b; } x = 1; y = 2; print add(x, y);"));
    }

    @Test
    void plainArrayLiteralWithIdentifiersIsNotMisreadAsDestructuring() {
        assertEquals("[1, 2]", run("x = 1; y = 2; print [x, y];"));
    }

    @Test
    void singleIdentifierAssignmentStillWorks() {
        assertEquals("5", run("x = 5; print x;"));
    }
}
