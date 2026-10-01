package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// lambdas: (params) -> expr | (params) -> { block }. Named functions were
// already first-class values (a bare function name loads its FunDef), so
// these tests focus on what's actually new: anonymous function literals
// and real closures (captured variables backed by a shared heap cell, not
// a copied value).
class Bl0jv2_LambdaTest {

    @Test
    void expressionBodiedLambdaCallsCorrectly() {
        assertEquals("10", run("f = (x) -> x * 2; print f(5);"));
    }

    @Test
    void blockBodiedLambdaCallsCorrectly() {
        assertEquals("6", run("f = (x) -> { y = x + 1; return y * 2; }; print f(2);"));
    }

    @Test
    void zeroArgLambda() {
        assertEquals("42", run("f = () -> 42; print f();"));
    }

    @Test
    void lambdaPassedDirectlyAsAnArgument() {
        assertEquals("11", run("def apply(g, x) { return g(x); } print apply((x) -> x + 1, 10);"));
    }

    @Test
    void lambdaReadsACapturedVariable() {
        assertEquals("true|false", run(
                "threshold = 3; " +
                "isBig = (x) -> x > threshold; " +
                "print isBig(5) + '|' + isBig(1);"));
    }

    @Test
    void closureMutatesCapturedStateAcrossCalls() {
        assertEquals("1|2|3", run(
                "def makeCounter() { " +
                "  count = 0; " +
                "  return () -> { count = count + 1; return count; }; " +
                "} " +
                "next = makeCounter(); " +
                "print next() + '|' + next() + '|' + next();"));
    }

    @Test
    void independentClosuresDoNotShareCapturedState() {
        assertEquals("1|1|2", run(
                "def makeCounter() { " +
                "  count = 0; " +
                "  return () -> { count = count + 1; return count; }; " +
                "} " +
                "a = makeCounter(); b = makeCounter(); " +
                "print a() + '|' + b() + '|' + a();"));
    }

    @Test
    void multiLevelNestedClosuresThreadCapturesThroughEachLevel() {
        assertEquals("17|26", run(
                "def makeAdder(base) { " +
                "  return (x) -> { return (y) -> base + x + y; }; " +
                "} " +
                "add5 = makeAdder(5); " +
                "print add5(10)(2) + '|' + add5(20)(1);"));
    }

    @Test
    void closureFromOneCallDoesNotAffectAnotherCallsClosure() {
        assertEquals("16|26", run(
                "def makeAdder(base) { return (x) -> (y) -> base + x + y; } " +
                "add5 = makeAdder(5); " +
                "a = add5(10); b = add5(20); " +
                "print a(1) + '|' + b(1);"));
    }

    @Test
    void lambdaInsideAMethodCanCaptureALocalDerivedFromAField() {
        assertEquals("15|17", run(
                "def class Adder { field base; def init(base) { this.base = base; } " +
                "  def makeFn() { b = this.base; return (x) -> b + x; } } " +
                "f = new Adder(10).makeFn(); " +
                "print f(5) + '|' + f(7);"));
    }

    @Test
    void lambdaInsideAMethodCanCaptureThisDirectly() {
        assertEquals("101", run(
                "def class Adder { field base; def init(base) { this.base = base; } " +
                "  def makeFn() { return (x) -> this.base + x; } } " +
                "print new Adder(100).makeFn()(1);"));
    }

    // all closures made from the same loop iteration variable share the
    // SAME cell (this language has no per-iteration block scoping, same
    // as a for-loop's own variable staying visible after the loop) - this
    // pins down that behavior as intentional, not a bug
    @Test
    void closuresOverALoopVariableShareTheSameFinalValue() {
        assertEquals("3|3|3", run(
                "fns = []; " +
                "for (i = 0; i < 3; i = i + 1) { push(fns, () -> i); } " +
                "print fns[0]() + '|' + fns[1]() + '|' + fns[2]();"));
    }

    // a function that contains a lambda anywhere boxes all of its own
    // locals defensively - this must not disturb unrelated break/continue/
    // try-catch control flow in the same function
    @Test
    void breakContinueAndTryCatchStillWorkInAFunctionThatAlsoContainsALambda() {
        assertEquals("16", run(
                "def process(arr) { " +
                "  total = 0; i = 0; " +
                "  while (i < len(arr)) { " +
                "    if (arr[i] < 0) { i = i + 1; continue; } " +
                "    if (arr[i] > 100) { break; } " +
                "    try { total = total + arr[i]; } catch (e) { print 'err'; } " +
                "    i = i + 1; " +
                "  } " +
                "  return (x) -> total + x; " +
                "} " +
                "print process([1, -5, 2, 3, 200, 4])(10);"));
    }

    @Test
    void higherOrderFunctionsWorkWithBothNamedFunctionsAndLambdas() {
        assertEquals("[2, 4, 6]|[2, 4, 6]", run(
                "def apply1(f, x) { return f(x); } " +
                "def double(x) { return x * 2; } " +
                "a = [apply1(double, 1), apply1(double, 2), apply1(double, 3)]; " +
                "b = [apply1((x) -> x * 2, 1), apply1((x) -> x * 2, 2), apply1((x) -> x * 2, 3)]; " +
                "print a + '|' + b;"));
    }
}
