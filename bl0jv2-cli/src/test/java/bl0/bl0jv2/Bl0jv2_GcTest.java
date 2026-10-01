package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// opt-in (set_gc_enabled) mark-and-sweep: garbage is reclaimed, everything reachable
// survives. A tiny threshold makes a collection happen every few hundred
// allocations so these programs go through hundreds of them.
class Bl0jv2_GcTest {

    private static Consumer<Bl0jv2_jVM> eager() {
        return vm -> { vm.set_gc_enabled(true); vm.set_gc_threshold_bytes(64 * 1024 / 8); };
    }

    @Test
    void aStringBuildingLoopRunsInBoundedMemory() {
        // every intermediate string is garbage the moment 's' is reassigned;
        // with only 2000 heap entries allowed this could not finish otherwise
        String out = run(
                "s = ''; i = 0; while (i < 50000) { s = s + 'x'; i = i + 1; } print len(s);",
                vm -> { vm.set_gc_enabled(true); vm.set_gc_threshold_bytes(64 * 1024); vm.set_max_heap_entries(2000); });
        assertEquals("50000", out);
    }

    @Test
    void byDefaultNothingIsCollectedSoTheSameLoopExhaustsTheHeapLimit() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "s = ''; i = 0; while (i < 50000) { s = s + 'x'; i = i + 1; } print len(s);",
                vm -> vm.set_max_heap_entries(2000)));
    }

    @Test
    void collectionsActuallyHappen() {
        var holder = new Bl0jv2_jVM[1];
        run("i = 0; while (i < 20000) { s = 'garbage' + str(i); i = i + 1; } print i;",
                vm -> { vm.set_gc_enabled(true); vm.set_gc_threshold_bytes(8 * 1024); holder[0] = vm; });
        assertTrue(holder[0].gc_collections() > 5, "collections: " + holder[0].gc_collections());
    }

    @Test
    void arraysAndTheirElementsSurvive() {
        assertEquals("a|b|c|3", run(
                "keep = ['a', 'b', 'c']; i = 0; while (i < 20000) { g = 'junk' + str(i); i = i + 1; } " +
                "print keep[0] + '|' + keep[1] + '|' + keep[2] + '|' + str(len(keep));", eager()));
    }

    @Test
    void nestedContainersSurvive() {
        assertEquals("deep|9", run(
                "grid = [['x', ['deep']], (1, 9)]; i = 0; while (i < 20000) { g = 'junk' + str(i); i = i + 1; } " +
                "print grid[0][1][0] + '|' + str(grid[1][1]);", eager()));
    }

    @Test
    void instanceFieldsSurvive() {
        assertEquals("alice|[x, y]", run(
                "def class P { field name; field tags; } " +
                "p = new P(); p.name = 'al' + 'ice'; p.tags = ['x', 'y']; " +
                "i = 0; while (i < 20000) { g = 'junk' + str(i); i = i + 1; } " +
                "print p.name + '|' + str(p.tags);", eager()));
    }

    @Test
    void staticFieldsSurvive() {
        assertEquals("config", run(
                "def class Cfg { static field name; } Cfg.name = 'con' + 'fig'; " +
                "i = 0; while (i < 20000) { g = 'junk' + str(i); i = i + 1; } print Cfg.name;", eager()));
    }

    @Test
    void closureCapturesSurvive() {
        assertEquals("hello world", run(
                "greeting = 'hel' + 'lo'; f = (w) -> greeting + ' ' + w; " +
                "i = 0; while (i < 20000) { g = 'junk' + str(i); i = i + 1; } print f('world');", eager()));
    }

    @Test
    void valuesHeldOnlyInACalleeFrameSurvive() {
        assertEquals("keep!", run(
                "def work(label) { i = 0; while (i < 20000) { g = 'junk' + str(i); i = i + 1; } return label + '!'; } " +
                "print work('ke' + 'ep');", eager()));
    }

    @Test
    void anInterruptHandlerClosureSurvivesAndStillRuns() {
        // once install() returns, the closure and the string it captured are
        // reachable ONLY through the interrupt controller's handler table
        assertEquals("tag:7", run(
                "def class S { static field out; } " +
                "def install() { tag = 'ta' + 'g'; h = (v) -> { S.out = tag + ':' + str(v); }; registerHandler(h, 7, 1); } " +
                "install(); " +
                "i = 0; while (i < 20000) { g = 'junk' + str(i); i = i + 1; } " +
                "raiseInterrupt(7); j = 0; while (j < 50) { j = j + 1; } print S.out;", eager()));
    }

    @Test
    void explicitFreeStillWorksAlongsideTheCollector() {
        assertEquals("ok|double free", run(
                "x = 'to' + 'free'; free(x); i = 0; while (i < 20000) { g = 'junk' + str(i); i = i + 1; } " +
                "y = 'again'; z = 'ag' + 'ain'; print 'ok|'; try { free(z); free(z); } catch (e) { print e; }", eager()));
    }

    @Test
    void aLongLivedMapSurvivesHeavyChurn() {
        // the stdlib hash map: buckets of entries, all reachable from one variable
        assertEquals("200|v77", run(
                "def class E { field k; field v; } " +
                "m = []; i = 0; while (i < 200) { e = new E(); e.k = 'k' + str(i); e.v = 'v' + str(i); push(m, e); i = i + 1; } " +
                "j = 0; while (j < 30000) { g = 'junk' + str(j); j = j + 1; } " +
                "print str(len(m)) + '|' + m[77].v;", eager()));
    }

    @Test
    void multiCoreMachinesDoNotCollectAutomatically() {
        var holder = new Bl0jv2_jVM[1];
        String out = run("def t(v) { } dispatch(t, 1, 0); i = 0; while (i < 20000) { s = 'junk' + str(i); i = i + 1; } print 'done';",
                vm -> { vm.set_core_count(2); vm.set_gc_enabled(true); vm.set_gc_threshold_bytes(8 * 1024); holder[0] = vm; });
        assertEquals("done", out);
        assertEquals(0, holder[0].gc_collections());
    }
}
