package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_ParserException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bl0jv2_ControlFlowTest {

    @Test
    void ifTakesThenBranch() {
        assertEquals("big", run("x = 10; if (x > 5) { print 'big'; } else { print 'small'; }"));
    }

    @Test
    void ifTakesElseBranch() {
        assertEquals("small", run("x = 3; if (x > 5) { print 'big'; } else { print 'small'; }"));
    }

    @Test
    void ifWithoutElseSkipsWhenFalse() {
        assertEquals("", run("x = 3; if (x > 5) { print 'big'; }"));
    }

    @Test
    void whileLoopAccumulates() {
        assertEquals("10", run(
                "i = 0; sum = 0;" +
                "while (i < 5) { sum = sum + i; i = i + 1; }" +
                "print sum;"));
    }

    // regression: a ';'-terminated assignment directly followed by a
    // print/if/while statement used to fail to parse, since the leftover
    // ';' was checked against before being consumed (see Bl0jv2_Parser)
    @Test
    void semicolonTerminatedAssignmentFollowedByPrintParsesCorrectly() {
        assertEquals("10", run("x = 10; print x;"));
    }

    @Test
    void semicolonTerminatedAssignmentFollowedByIfParsesCorrectly() {
        assertEquals("big", run("x = 10; if (x > 5) { print 'big'; } else { print 'small'; }"));
    }

    @Test
    void semicolonTerminatedAssignmentFollowedByWhileParsesCorrectly() {
        assertEquals("10", run(
                "i = 0; sum = 0; " +
                "while (i < 5) { sum = sum + i; i = i + 1; } " +
                "print sum;"));
    }

    // --- for loop ---

    @Test
    void forLoopAccumulates() {
        assertEquals("55", run("sum = 0; for (i = 1; i <= 10; i = i + 1) { sum = sum + i; } print sum;"));
    }

    @Test
    void forLoopWithZeroIterationsSkipsBody() {
        assertEquals("0", run("n = 0; for (i = 0; i < 0; i = i + 1) { n = n + 1; } print n;"));
    }

    @Test
    void forLoopVariableIsVisibleAfterTheLoop() {
        // this language has no block scoping, so this matches every other
        // construct (if/while bodies) rather than being for-loop-specific
        assertEquals("3", run("for (i = 0; i < 3; i = i + 1) {} print i;"));
    }

    @Test
    void nestedForLoops() {
        assertEquals("4", run(
                "count = 0; " +
                "for (a = 0; a < 2; a = a + 1) { " +
                "  for (b = 0; b < 2; b = b + 1) { count = count + 1; } " +
                "} " +
                "print count;"));
    }

    @Test
    void forLoopOverArrayUsingLen() {
        assertEquals("60", run(
                "arr = [10, 20, 30]; total = 0; " +
                "for (i = 0; i < len(arr); i = i + 1) { total = total + arr[i]; } " +
                "print total;"));
    }

    @Test
    void forLoopParensAreMandatory() {
        assertThrows(Bl0j_ParserException.class, () -> run("for i = 0; i < 3; i = i + 1 {}"));
    }
}
