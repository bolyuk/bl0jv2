package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// field/method access goes through interned symbol ids and per-class tables
// (Bl0jClass), with method values boxed once at class load
class Bl0jv2_ClassStorageTest {

    private static final String COUNTER =
            "def class Counter { field n; field label; " +
            "  def init() { this.n = 0; this.label = 'c'; } " +
            "  def inc() { this.n = this.n + 1; return this.n; } } ";

    @Test
    void methodCallsDoNotConsumeHeapSlots() {
        // every call used to box its FunDef afresh, so a few hundred calls
        // exhausted a tiny heap limit; with the method value boxed once at
        // class load this loop needs no further heap entries
        assertEquals("20000", run(COUNTER +
                "c = new Counter(); i = 0; while (i < 20000) { c.inc(); i = i + 1; } print c.n;",
                vm -> vm.set_max_heap_entries(200)));
    }

    @Test
    void fieldsOfSeveralClassesWithOverlappingNamesStaySeparate() {
        assertEquals("1|a|2|b", run(
                "def class A { field x; field name; } def class B { field name; field x; } " +
                "a = new A(); a.x = 1; a.name = 'a'; b = new B(); b.x = 2; b.name = 'b'; " +
                "print str(a.x) + '|' + a.name + '|' + str(b.x) + '|' + b.name;"));
    }

    @Test
    void methodsWithTheSameNameInDifferentClassesDispatchOnTheReceiver() {
        assertEquals("A|B", run(
                "def class A { def who() { return 'A'; } } def class B { def who() { return 'B'; } } " +
                "def call(o) { return o.who(); } print call(new A()) + '|' + call(new B());"));
    }

    // another class declares zzz/nope, so the compiler cannot reject the
    // access on a Counter and the VM has to
    private static final String ELSEWHERE = "def class Elsewhere { field zzz; def nope() { return 1; } } ";

    @Test
    void unknownFieldNamesTheFieldAndTheClass() {
        assertEquals("class Counter has no field 'zzz'", run(COUNTER + ELSEWHERE +
                "c = new Counter(); try { print c.zzz; } catch (e) { print e; }"));
    }

    @Test
    void unknownMethodNamesTheMethodAndTheClass() {
        assertEquals("class Counter has no method 'nope'", run(COUNTER + ELSEWHERE +
                "c = new Counter(); try { c.nope(); } catch (e) { print e; }"));
    }

    @Test
    void readingAFieldOfNilIsACleanError() {
        assertEquals("cannot read field 'n' on nil", run(COUNTER +
                "x = nil; try { print x.n; } catch (e) { print e; }"));
    }

    @Test
    void callingAMethodOnANonInstanceIsACleanError() {
        assertEquals("cannot call method 'inc' on int", run(COUNTER +
                "x = 5; try { x.inc(); } catch (e) { print e; }"));
    }

    @Test
    void settingAFieldOnNilIsACleanError() {
        assertEquals("cannot set field 'n' on nil", run(COUNTER +
                "x = nil; try { x.n = 1; } catch (e) { print e; }"));
    }

    @Test
    void userDefinedToStringAndEqualsStillWork() {
        assertEquals("P(3)|true|false", run(
                "def class P { field v; def init(v) { this.v = v; } " +
                "  def toString() { return 'P(' + this.v + ')'; } " +
                "  def equals(o) { return this.v == o.v; } } " +
                "a = new P(3); b = new P(3); c = new P(4); " +
                "print str(a) + '|' + str(a == b) + '|' + str(a == c);"));
    }
}
