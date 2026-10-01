package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 'def' inside a function/block is a closure over the enclosing variables
class Bl0jv2_NestedDefTest {

    @Test
    void nestedDefSeesEnclosingParameters() {
        assertEquals("15", run("def outer(base) { def add(x) { return base + x; } return add(5); } print outer(10);"));
    }

    @Test
    void nestedDefSeesEnclosingLocalsAtCallTime() {
        // captured by cell, so it reads the variable's CURRENT value
        assertEquals("1|2", run("def outer() { n = 1; def get() { return n; } a = get(); n = 2; return str(a) + '|' + str(get()); } print outer();"));
    }

    @Test
    void nestedDefCanMutateAnEnclosingVariable() {
        assertEquals("3", run("def outer() { count = 0; def bump() { count = count + 1; return count; } bump(); bump(); bump(); return count; } print outer();"));
    }

    @Test
    void nestedDefCanRecurse() {
        assertEquals("120", run("def outer(k) { def fact(n) { if (n <= 1) { return 1; } return n * fact(n - 1); } return fact(k); } print outer(5);"));
    }

    @Test
    void nestedDefCanBeReturnedAsAClosure() {
        assertEquals("7|12", run("def makeAdder(k) { def adder(x) { return x + k; } return adder; } " +
                "a = makeAdder(3); b = makeAdder(8); print str(a(4)) + '|' + str(b(4));"));
    }

    @Test
    void nestedDefCanCallEarlierNestedDef() {
        assertEquals("9", run("def outer() { def sq(x) { return x * x; } def f(x) { return sq(x); } return f(3); } print outer();"));
    }

    @Test
    void nestedDefInsideABlockAtTopLevel() {
        assertEquals("42", run("if (true) { k = 40; def f() { return k + 2; } print f(); }"));
    }

    @Test
    void topLevelDefsAreStillGlobalAndHoisted() {
        assertEquals("ok", run("print later(); def later() { return 'ok'; }"));
    }

    @Test
    void nestedClassDefinitionIsRejected() {
        assertThrows(RuntimeException.class, () -> run("def f() { def class A { field x; } }"));
    }
}
