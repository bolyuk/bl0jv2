package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// runaway recursion and self-referential data are errors a program can catch,
// not a dead JVM
class Bl0jv2_StackLimitTest {

    @Test
    void infiniteRecursionIsACatchableStackOverflow() {
        assertEquals("stack overflow: call depth limit (1000) exceeded", run(
                "def f(n) { return f(n + 1); } try { f(0); } catch (e) { print e; }",
                vm -> vm.set_max_call_depth(1000)));
    }

    @Test
    void infiniteRecursionWithTheDefaultLimitIsAlsoCaught() {
        String out = run("def f(n) { return 1 + f(n + 1); } try { f(0); } catch (e) { print e; }");
        assertTrue(out.startsWith("stack overflow: call depth limit ("), out);
    }

    @Test
    void mutualRecursionIsCaughtToo() {
        assertEquals("overflow", run(
                "def a(n) { return b(n); } def b(n) { return a(n); } " +
                "try { a(0); } catch (e) { print 'overflow'; }",
                vm -> vm.set_max_call_depth(500)));
    }

    @Test
    void legitimateDeepRecursionStillWorks() {
        assertEquals("20000", run("def d(n) { if (n == 0) { return 0; } return 1 + d(n - 1); } print d(20000);"));
    }

    @Test
    void theProgramKeepsRunningAfterTheOverflowIsCaught() {
        assertEquals("caught|still alive", run(
                "def f(n) { return f(n + 1); } " +
                "try { f(0); } catch (e) { print 'caught|'; } print 'still alive';",
                vm -> vm.set_max_call_depth(1000)));
    }

    @Test
    void anArrayContainingItselfPrintsWithAPlaceholder() {
        assertEquals("[[...]]", run("a = []; push(a, a); print a;"));
    }

    @Test
    void twoArraysContainingEachOtherPrint() {
        assertEquals("[[[...]]]", run("a = []; b = []; push(a, b); push(b, a); print a;"));
    }

    @Test
    void instancesPointingAtEachOtherPrint() {
        assertEquals("Node{next: Node{...}}", run(
                "def class Node { field next; } n = new Node(); n.next = n; print n;"));
    }

    @Test
    void aToStringThatPrintsItselfIsCaught() {
        assertEquals("stack overflow: call depth limit (200) exceeded", run(
                "def class Loop { def toString() { return 'x' + str(this); } } " +
                "try { s = str(new Loop()); } catch (e) { print e; }"));
    }

    @Test
    void anUncaughtErrorCarriesOneAddressPrefixNotOnePerNestedLevel() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(bl0.bl0jv2.exceptions.Bl0j_VM_Exception.class, () -> run(
                "def class Loop { def toString() { return 'x' + str(this); } } s = str(new Loop());"));
        assertEquals(1, e.getMessage().split("Exception on address", -1).length - 1, e.getMessage());
    }

    @Test
    void absurdlyDeepNestedDataIsACatchableErrorNotACrash() {
        String out = run(
                "a = []; i = 0; while (i < 300000) { a = [a]; i = i + 1; } " +
                "try { s = str(a); print 'printed'; } catch (e) { print e; }");
        assertTrue(out.equals("printed") || out.startsWith("stack overflow"), out);
    }
}
