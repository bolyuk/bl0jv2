package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// a lambda assigned to a variable can call itself through that variable
class Bl0jv2_RecursiveLambdaTest {

    @Test
    void factorialAtTopLevel() {
        assertEquals("120", run("fact = (n) -> n <= 1 ? 1 : n * fact(n - 1); print fact(5);"));
    }

    @Test
    void blockBodyRecursion() {
        assertEquals("55", run("fib = (n) -> { if (n < 2) { return n; } return fib(n - 1) + fib(n - 2); }; print fib(10);"));
    }

    @Test
    void insideAFunction() {
        assertEquals("21", run("def f(k) { sum = (n) -> n == 0 ? 0 : n + sum(n - 1); return sum(k); } print f(6);"));
    }

    @Test
    void recursiveLambdaStillSeesOtherCapturedVariables() {
        assertEquals("30", run("step = 5; total = (n) -> n == 0 ? 0 : step + total(n - 1); print total(6);"));
    }

    @Test
    void reassigningTheVariableLaterIsSeenByTheLambda() {
        // the lambda captured the variable's cell, not a snapshot of its value
        assertEquals("1|100", run("f = (n) -> n == 0 ? 1 : f(n - 1); a = f(3); " +
                "f = (n) -> 100; print str(a) + '|' + str(f(0));"));
    }

    @Test
    void nonRecursiveLambdaIsUnaffected() {
        assertEquals("7", run("add = (a, b) -> a + b; print add(3, 4);"));
    }
}
