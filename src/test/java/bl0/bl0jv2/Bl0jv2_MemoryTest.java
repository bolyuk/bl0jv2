package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// free() and the VM's bounded managed heap - kernel-adaptation work: values
// on the managed heap (arrays/strings/instances/...) already allocate
// implicitly through existing syntax, so free() is the missing "release it
// early" half, and set_max_heap_entries() is what lets a host cap how much
// memory a VM instance is allowed to hand out.
class Bl0jv2_MemoryTest {

    @Test
    void useAfterFreeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "arr = [1, 2, 3]; free(arr); print arr;"));
    }

    @Test
    void freeingAStringAndAnInstanceAlsoWorks() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("s = 'hi'; free(s); print s;"));
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "def class Point { field x; } p = new Point(); free(p); print p;"));
    }

    @Test
    void doubleFreeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "arr = [1]; free(arr); free(arr);"));
    }

    @Test
    void freeingANonRefNonIntValueThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("free(true);"));
    }

    @Test
    void freeEvaluatesToNilAndDoesNotThrowOnItsOwn() {
        assertEquals("nil", run("arr = [1, 2]; print free(arr);"));
    }

    @Test
    void maxHeapEntriesLimitIsEnforced() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "a = [1]; b = [2]; c = [3];",
                vm -> vm.set_max_heap_entries(2)));
    }

    // proves the freed slot actually gets reused, not just that the limit
    // exception exists - without free-list reuse this would also throw.
    // Prints a plain int, not a string: a string literal is itself a
    // constant-pool entry that also boxes onto the heap, which would throw
    // its own budget off
    @Test
    void aFreedSlotIsReusedByTheNextAllocation() {
        assertEquals("1", run(
                "a = [1]; b = [2]; free(a); c = [3]; print 1;",
                vm -> vm.set_max_heap_entries(2)));
    }

    // --- raw memory: alloc/free/peek8/16/32/poke8/16/32 ---

    @Test
    void poke8AndPeek8RoundTrip() {
        assertEquals("200", run("addr = alloc(4); poke8(addr, 200); print peek8(addr);"));
    }

    @Test
    void poke16AndPeek16RoundTrip() {
        assertEquals("40000", run("addr = alloc(4); poke16(addr, 40000); print peek16(addr);"));
    }

    @Test
    void poke32AndPeek32RoundTrip() {
        assertEquals("300000000", run("addr = alloc(4); poke32(addr, 300000000); print peek32(addr);"));
    }

    @Test
    void differentWidthsAtAdjacentAddressesDoNotOverlap() {
        assertEquals("200|40000|300000000", run(
                "addr = alloc(16); " +
                "poke8(addr, 200); poke16(addr + 1, 40000); poke32(addr + 4, 300000000); " +
                "print peek8(addr) + '|' + peek16(addr + 1) + '|' + peek32(addr + 4);"));
    }

    @Test
    void peekPastTheArenaThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "print peek8(999999999);",
                vm -> vm.set_max_raw_bytes(64)));
    }

    @Test
    void pokePastTheArenaThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "poke8(999999999, 1);",
                vm -> vm.set_max_raw_bytes(64)));
    }

    @Test
    void freeingAnAddressNeverAllocatedThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("free(12345);"));
    }

    @Test
    void doubleFreeOfARawAddressThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "addr = alloc(4); free(addr); free(addr);"));
    }

    @Test
    void allocPastMaxRawBytesThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "a = alloc(32); b = alloc(32); c = alloc(32);",
                vm -> vm.set_max_raw_bytes(64)));
    }

    // proves the raw allocator's free-list actually reuses space - a
    // bump-only allocator would throw here even though nothing leaked
    @Test
    void repeatedAllocFreeOfTheSameSizeReusesSpaceInsteadOfExhaustingTheArena() {
        assertEquals("done", run(
                "i = 0; while (i < 50) { a = alloc(32); free(a); i = i + 1; } print 'done';",
                vm -> vm.set_max_raw_bytes(32)));
    }

    @Test
    void freeOnAPlainIntRoutesToRawFreeNotManagedFree() {
        // if this were misrouted to the managed-heap path, freeing a value
        // that was never a heap REF would throw a different error (or
        // corrupt an unrelated heap slot) instead of this clean, expected
        // "invalid free" for an address that was never alloc()'d
        assertThrows(Bl0j_VM_Exception.class, () -> run("free(42);"));
    }

    // --- reserve: fixed MMIO-style address ranges ---

    @Test
    void peekAndPokeWorkOnAReservedAddressWithoutAllocatingIt() {
        assertEquals("42", run(
                "reserve(0, 4); poke32(0, 42); print peek32(0);"));
    }

    @Test
    void allocNeverHandsOutAnAddressInsideAReservedRange() {
        // the arena is exactly 8 bytes; reserving all of it must force
        // alloc() to fail rather than silently overlap the reservation
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "reserve(0, 8); alloc(1);",
                vm -> vm.set_max_raw_bytes(8)));
    }

    @Test
    void allocStillWorksInTheSpaceAfterAReservedRange() {
        assertEquals("32", run(
                "reserve(0, 4); addr = alloc(4); poke32(addr, 32); print peek32(addr);",
                vm -> vm.set_max_raw_bytes(8)));
    }

    @Test
    void freeingAReservedAddressThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("reserve(0, 4); free(0);"));
    }

    @Test
    void reservingBehindTheBumpPointerThrows() {
        // alloc() has already handed out [0, 4) via the bump pointer, so
        // reserve() can't retroactively claim address 0
        assertThrows(Bl0j_VM_Exception.class, () -> run("alloc(4); reserve(0, 4);"));
    }

    @Test
    void reservingPastTheArenaThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "reserve(0, 999999999);",
                vm -> vm.set_max_raw_bytes(64)));
    }
}
