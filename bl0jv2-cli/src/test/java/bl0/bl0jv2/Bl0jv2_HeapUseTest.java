package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// operations that used to take a heap slot per call but don't have to
class Bl0jv2_HeapUseTest {

    @Test
    void typeOfNeedsNoHeapEntryPerCall() {
        assertEquals("int", run("i = 0; while (i < 20000) { t = typeOf(i); i = i + 1; } print t;",
                vm -> vm.set_max_heap_entries(100)));
    }

    @Test
    void strOfAStringReturnsTheSameString() {
        assertEquals("abc", run("s = 'abc'; i = 0; while (i < 20000) { t = str(s); i = i + 1; } print t;",
                vm -> vm.set_max_heap_entries(100)));
    }

    @Test
    void strOfBoolsAndNilNeedsNoHeapEntryPerCall() {
        assertEquals("true|false|nil", run(
                "i = 0; while (i < 20000) { a = str(true); b = str(false); c = str(nil); i = i + 1; } print a + '|' + b + '|' + c;",
                vm -> vm.set_max_heap_entries(100)));
    }

    @Test
    void methodCallsAndFieldAccessNeedNoHeapEntriesEither() {
        assertEquals("20000", run(
                "def class C { field n; def init() { this.n = 0; } def inc() { this.n = this.n + 1; return this.n; } } " +
                "c = new C(); i = 0; while (i < 20000) { c.inc(); i = i + 1; } print c.n;",
                vm -> vm.set_max_heap_entries(100)));
    }

    @Test
    void typeOfStillReportsEveryKind() {
        assertEquals("int|float|string|bool|nil|array|char|function", run(
                "s = 'ab'; print typeOf(1) + '|' + typeOf(1.5) + '|' + typeOf('x') + '|' + typeOf(true) + '|' + typeOf(nil) " +
                "+ '|' + typeOf([1]) + '|' + typeOf(s[0]) + '|' + typeOf((x) -> x);"));
    }
}
