package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// x++ / x-- write back to wherever x lives and yield the old value
class Bl0jv2_IncrementTest {

    @Test
    void plainLocal() {
        assertEquals("2", run("n = 0; n++; n++; print n;"));
    }

    @Test
    void decrement() {
        assertEquals("3", run("n = 5; n--; n--; print n;"));
    }

    @Test
    void aVariableInAScopeThatHasALambda() {
        // every local of such a function lives in a cell: ++ used to bump a copy
        assertEquals("2", run("def f() { n = 0; g = () -> n; n++; n++; return n; } print f();"));
    }

    @Test
    void aCapturedVariableIsSeenByTheLambda() {
        assertEquals("3", run("def f() { n = 0; g = () -> n; n++; n++; n++; return g(); } print f();"));
    }

    @Test
    void aVariableIncrementedInsideALambda() {
        assertEquals("3", run("n = 0; bump = () -> { n++; }; bump(); bump(); bump(); print n;"));
    }

    @Test
    void aField() {
        assertEquals("2", run("def class C { field v; def init() { this.v = 0; } def bump() { this.v++; return this.v; } } " +
                "c = new C(); c.bump(); print c.bump();"));
    }

    @Test
    void anArrayElement() {
        assertEquals("[2, 1]", run("a = [1, 2]; a[0]++; a[1]--; print a;"));
    }

    @Test
    void aStaticField() {
        assertEquals("2", run("def class S { static field n; } S.n = 0; S.n++; S.n++; print S.n;"));
    }

    @Test
    void theExpressionYieldsTheOldValue() {
        assertEquals("5|6", run("n = 5; m = n++; print str(m) + '|' + str(n);"));
    }

    @Test
    void worksAsAForLoopStep() {
        assertEquals("55", run("sum = 0; for (i = 1; i <= 10; i++) { sum += i; } print sum;"));
    }

    @Test
    void aFloatIsIncremented() {
        assertEquals("2.5", run("x = 1.5; x++; print x;"));
    }

    @Test
    void anOperandThatIsNotAVariableIsRejected() {
        assertThrows(Bl0j_CompilerException.class, () -> run("def f() { return 1; } f()++;"));
    }

    @Test
    void anOperandWithACallInItsIndexIsRejected() {
        assertThrows(Bl0j_CompilerException.class, () -> run("def f() { return 0; } a = [1]; a[f()]++;"));
    }
}
