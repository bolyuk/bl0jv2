package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Bl0jv2_TryCatchTest {

    @Test
    void catchesRuntimeErrorWithCleanMessage() {
        // no "Exception on address: N -" prefix - just the message, per the
        // 'err is deliberately minimal' design
        assertEquals("division by zero", run("try { x = 1 / 0; } catch (e) { print e; }"));
    }

    @Test
    void tryBodyRunsNormallyWhenNoErrorOccurs() {
        assertEquals("5", run("try { x = 5; print x; } catch (e) { print 'unreachable'; }"));
    }

    @Test
    void codeAfterTryCatchStillRuns() {
        assertEquals("ok", run("try { y = 1 / 0; } catch (e) {} print 'ok';"));
    }

    @Test
    void caughtValueHasTypeErr() {
        assertEquals("err", run("try { x = 1 / 0; } catch (e) { print typeOf(e); }"));
    }

    @Test
    void isErrIdentifiesCaughtValue() {
        assertEquals("true", run("try { x = 1 / 0; } catch (e) { print isErr(e); }"));
    }

    @Test
    void exceptionInsideNestedFunctionCallIsCaughtByOuterTry() {
        assertEquals("division by zero", run(
                "def deep() { return 1 / 0; } " +
                "try { deep(); } catch (e) { print e; }"));
    }

    @Test
    void programContinuesNormallyAfterCatchingFromNestedCall() {
        assertEquals("still alive", run(
                "def deep() { return 1 / 0; } " +
                "try { deep(); } catch (e) {} " +
                "print 'still alive';"));
    }

    // regression-shaped: an early 'return' from inside a try body must not
    // leave a stale handler registered - otherwise a *later*, unrelated
    // error would be wrongly caught by it (or worse, corrupt the call stack)
    @Test
    void earlyReturnFromInsideTryDoesNotLeaveStaleHandler() {
        assertEquals("early|division by zero", run(
                "def withEarlyReturn() { try { return 'early'; } catch (e) { return 'wrong'; } } " +
                "r = withEarlyReturn(); " +
                "try { z = 1 / 0; } catch (e2) { print r + '|' + e2; }"));
    }

    @Test
    void nestedTryCatchInnerHandlesItsOwnError() {
        assertEquals("inner", run(
                "try { " +
                "  try { x = 1 / 0; } catch (e) { print 'inner'; } " +
                "} catch (e2) { print 'outer'; }"));
    }

    @Test
    void manualErrConstructionForGoStyleReturns() {
        assertEquals("true", run("e = err('custom message'); print isErr(e);"));
    }

    @Test
    void manualErrMessageIsPreserved() {
        assertEquals("custom message", run("print err('custom message');"));
    }

    @Test
    void functionCanSignalErrorGoStyleInsteadOfThrowing() {
        assertEquals("5|err", run(
                "def safeDivide(a, b) { if (b == 0) { return err('div by zero'); } return a / b; } " +
                "r1 = safeDivide(10, 2); " +
                "r2 = safeDivide(10, 0); " +
                "print r1 + '|' + typeOf(r2);"));
    }
}
