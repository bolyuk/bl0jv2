package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.exceptions.Bl0j_ParserException;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
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

    // --- break / continue ---

    @Test
    void breakStopsAWhileLoopEarly() {
        assertEquals("5", run("i = 0; while (i < 10) { i = i + 1; if (i == 5) { break; } } print i;"));
    }

    @Test
    void continueSkipsTheRestOfAWhileIteration() {
        assertEquals("12", run(
                "i = 0; sum = 0; " +
                "while (i < 5) { i = i + 1; if (i == 3) { continue; } sum = sum + i; } " +
                "print sum;"));
    }

    @Test
    void breakStopsAForLoopEarly() {
        assertEquals("10", run(
                "sum = 0; for (i = 0; i < 10; i = i + 1) { if (i == 5) { break; } sum = sum + i; } " +
                "print sum;"));
    }

    @Test
    void continueSkipsTheRestOfAForIterationButStillRunsTheUpdate() {
        assertEquals("8", run(
                "sum = 0; for (i = 0; i < 5; i = i + 1) { if (i == 2) { continue; } sum = sum + i; } " +
                "print sum;"));
    }

    @Test
    void breakOnlyExitsTheInnermostLoop() {
        assertEquals("20", run(
                "found = -1; " +
                "for (i = 0; i < 3; i = i + 1) { " +
                "  for (j = 0; j < 3; j = j + 1) { if (j == 1) { break; } found = i * 10 + j; } " +
                "} " +
                "print found;"));
    }

    @Test
    void breakOutsideOfALoopIsACompileTimeError() {
        assertThrows(Bl0j_CompilerException.class, () -> run("break;"));
    }

    @Test
    void continueOutsideOfALoopIsACompileTimeError() {
        assertThrows(Bl0j_CompilerException.class, () -> run("continue;"));
    }

    // break jumping out of a try body used to skip TRY_EXIT, leaving the
    // handler on the VM's handlerStack forever - a later, unrelated error
    // outside the loop would then be wrongly caught by that stale handler
    // instead of propagating
    @Test
    void breakOutOfATryBlockDoesNotLeaveAStaleHandler() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "i = 0; " +
                "while (i < 10) { " +
                "  try { i = i + 1; if (i == 3) { break; } } catch (e) { print 'unexpected'; } " +
                "} " +
                "x = 1 / 0;"));
    }

    // --- switch --- desugars to an if/else-if/else chain (see
    // Bl0jv2_Parser's own switch_statement() doc) - no fallthrough, each
    // case is its own block.

    @Test
    void switchTakesTheMatchingCase() {
        assertEquals("two", run("x = 2; switch (x) { case 1 { print 'one'; } case 2 { print 'two'; } }"));
    }

    @Test
    void switchFallsBackToDefaultWhenNoCaseMatches() {
        assertEquals("other", run("x = 99; switch (x) { case 1 { print 'one'; } default { print 'other'; } }"));
    }

    @Test
    void switchWithNoMatchAndNoDefaultDoesNothing() {
        assertEquals("", run("x = 99; switch (x) { case 1 { print 'one'; } }"));
    }

    @Test
    void switchOnlyEverTakesOneCase() {
        // no fallthrough: matching 'case 1' must not also run 'case 2'
        assertEquals("one", run("x = 1; switch (x) { case 1 { print 'one'; } case 2 { print 'two'; } }"));
    }

    @Test
    void switchEvaluatesItsSubjectExactlyOnceRegardlessOfCaseCount() {
        assertEquals("one|1", run(
                "def class Counter { static field n; } Counter.n = 0; " +
                "def bump() { Counter.n = Counter.n + 1; return Counter.n; } " +
                "switch (bump()) { case 1 { print 'one'; } case 2 { print 'two'; } default { print 'other'; } } " +
                "print '|' + Counter.n;"));
    }

    @Test
    void switchCasesCanBeArbitraryExpressionsNotJustLiterals() {
        assertEquals("big", run(
                "def class Threshold { static field big; } Threshold.big = 10; " +
                "x = 10; switch (x) { case Threshold.big { print 'big'; } default { print 'small'; } }"));
    }

    @Test
    void switchCanBeNested() {
        assertEquals("one-one|one-other|other", run(
                "def classify(a, b) { " +
                "  switch (a) { " +
                "    case 1 { switch (b) { case 1 { return 'one-one'; } default { return 'one-other'; } } } " +
                "    default { return 'other'; } " +
                "  } " +
                "} " +
                "print classify(1, 1) + '|' + classify(1, 2) + '|' + classify(2, 1);"));
    }

    @Test
    void switchBodyMustContainOnlyCaseAndDefault() {
        assertThrows(Bl0j_ParserException.class, () -> run("switch (1) { print 'oops'; }"));
    }
}
