package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// atomicAdd/atomicCas - this VM's reference-impl stand-in for a
// lock-prefixed x86 instruction (see RawMemory's own doc). Both operate on
// a fixed 32-bit word and return the value that was there *before* the
// operation; neither is privilege-gated, matching real hardware (atomics
// are usable from user mode too).
class Bl0jv2_AtomicsTest {

    @Test
    void atomicAddReturnsThePreviousValueAndAppliesTheDelta() {
        assertEquals("10|17", run(
                "poke32(0, 10); old = atomicAdd(0, 7); print old + '|' + peek32(0);"));
    }

    @Test
    void atomicCasSucceedsAndReturnsThePreviousValueWhenExpectedMatches() {
        assertEquals("5|99", run(
                "poke32(0, 5); old = atomicCas(0, 5, 99); print old + '|' + peek32(0);"));
    }

    // a failed CAS must leave the memory untouched - the returned old value
    // (!= expected) is how the caller is supposed to detect the failure
    @Test
    void atomicCasFailsAndLeavesTheValueUntouchedWhenExpectedDoesNotMatch() {
        assertEquals("5|5", run(
                "poke32(0, 5); old = atomicCas(0, 999, 99); print old + '|' + peek32(0);"));
    }

    @Test
    void atomicAddOutOfBoundsThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "atomicAdd(999999999, 1);",
                vm -> vm.set_max_raw_bytes(64)));
    }

    @Test
    void atomicCasOutOfBoundsThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "atomicCas(999999999, 0, 1);",
                vm -> vm.set_max_raw_bytes(64)));
    }

    @Test
    void atomicOpsWorkFromUserModeUnlikeReserveOrPortIO() {
        assertEquals("1", run("dropToUserMode(); atomicAdd(0, 1); print peek32(0);"));
    }

    // real cross-core race: 3 worker cores each atomicAdd() the *same*
    // address perTask times, with no Mutex protecting the address itself -
    // only atomicAdd's own indivisibility. A plain peek()+poke() pair here
    // would lose updates the same way Bl0jv2_ThreadingTest's unsafe-Mutex
    // test does; atomicAdd must not. The completion signal (doneCount) is
    // still Mutex-protected, same reasoning as that file's own
    // WAIT_AND_REPORT: a lock acquire/release gives core 0 a real JMM
    // happens-before edge to the workers' last atomicAdd(), through
    // RawMemory's own write lock.
    @Test
    void atomicAddIsRaceFreeAcrossRealCores() {
        int perTask = 20000;
        String source =
                "def class Counter { static field doneCount; static field doneLock; } " +
                "Counter.doneCount = 0; Counter.doneLock = newMutex(); " +
                "def bump(n) { " +
                "  i = 0; while (i < n) { atomicAdd(0, 1); i = i + 1; } " +
                "  lock(Counter.doneLock); Counter.doneCount = Counter.doneCount + 1; unlock(Counter.doneLock); " +
                "} " +
                "dispatch(bump, 1, " + perTask + "); " +
                "dispatch(bump, 2, " + perTask + "); " +
                "dispatch(bump, 3, " + perTask + "); " +
                "while (true) { " +
                "  lock(Counter.doneLock); done = Counter.doneCount; unlock(Counter.doneLock); " +
                "  if (done >= 3) { break; } " +
                "} " +
                "print peek32(0);";

        assertEquals(String.valueOf(perTask * 3), run(source, vm -> vm.set_core_count(4)));
    }
}
