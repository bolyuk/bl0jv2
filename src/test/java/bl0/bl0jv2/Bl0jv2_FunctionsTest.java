package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Bl0jv2_FunctionsTest {

    @Test
    void simpleFunctionCall() {
        assertEquals("5", run(
                "def add(a, b) { return a + b; }" +
                "print add(2, 3);"));
    }

    @Test
    void recursiveFunctionCall() {
        assertEquals("55", run(
                "def fib(n) { if (n < 2) { return n; } return fib(n - 1) + fib(n - 2); }" +
                "print fib(10);"));
    }

    @Test
    void functionWithNoArgs() {
        assertEquals("42", run(
                "def answer() { return 42; }" +
                "print answer();"));
    }

    // println prepends a newline before each value, per the native
    // implementation - no trailing newline after the last one
    @Test
    void printlnPrependsNewlineBeforeEachValue() {
        assertEquals("\na\nb", run("println 'a'; println 'b';"));
    }

    @Test
    void fullyTerminatedFunctionBodyParsesCorrectly() {
        assertEquals("5", run(
                "def add(a, b) { return a + b; } " +
                "print add(2, 3);"));
    }

    // regression: an empty-bodied function immediately after another
    // function that itself ended in 'return' would read that other
    // function's trailing RETURN byte and skip emitting its own, falling
    // through into unrelated bytecode when called
    @Test
    void emptyFunctionBodyAfterReturningFunctionStillReturnsCleanly() {
        assertEquals("nil", run(
                "def first() { return 1; } " +
                "def second() { } " +
                "print second();"));
    }
}
