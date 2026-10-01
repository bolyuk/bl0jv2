package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// CALL carries its argument count; the VM refuses a call whose count doesn't
// match the callee instead of reading stray registers (too few) or silently
// dropping arguments (too many)
class Bl0jv2_ArityTest {

    @Test
    void tooFewArgumentsIsAnError() {
        assertEquals("function f expects 2 arguments, got 1", run(
                "def f(a, b) { return a; } g = f; try { g(1); } catch (e) { print e; }"));
    }

    @Test
    void tooManyArgumentsIsAnError() {
        assertEquals("function f expects 1 argument, got 3", run(
                "def f(a) { return a; } g = f; try { g(1, 2, 3); } catch (e) { print e; }"));
    }

    @Test
    void matchingArgumentCountStillWorks() {
        assertEquals("3", run("def add(a, b) { return a + b; } g = add; print g(1, 2);"));
    }

    @Test
    void methodArityDoesNotCountThis() {
        assertEquals("function A.inc expects 0 arguments, got 1", run(
                "def class A { def inc() { return 1; } } a = new A(); try { a.inc(5); } catch (e) { print e; }"));
    }

    @Test
    void constructorArityIsChecked() {
        assertEquals("function A.init expects 1 argument, got 0", run(
                "def class A { field x; def init(v) { this.x = v; } } try { a = new A(); } catch (e) { print e; }"));
    }

    @Test
    void staticMethodArityIsChecked() {
        assertEquals("function M.twice expects 1 argument, got 2", run(
                "def class M { static def twice(x) { return x * 2; } } f = M.twice; try { f(1, 2); } catch (e) { print e; }"));
    }

    @Test
    void lambdaArityIsChecked() {
        assertEquals("lambda expects 1 argument, got 2", run(
                "f = (x) -> x; try { f(1, 2); } catch (e) { print e; }"));
    }

    @Test
    void capturedVariablesAreNotCountedAsArguments() {
        assertEquals("lambda expects 1 argument, got 0|11", run(
                "k = 10; f = (x) -> x + k; " +
                "try { f(); } catch (e) { print e + '|'; } print f(1);"));
    }

    @Test
    void callingANonFunctionNamesTheType() {
        assertEquals("cannot call int - not a function", run(
                "x = 5; try { x(1); } catch (e) { print e; }"));
    }

    @Test
    void anInterruptHandlerWithTheWrongParameterCountIsReported() {
        assertEquals("function handler expects 0 arguments, got 1", run(
                "def handler() { } registerHandler(handler, 9, 1); " +
                "try { syscall(9, 0); } catch (e) { print e; }"));
    }
}
