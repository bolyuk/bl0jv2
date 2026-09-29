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

    // --- raw memory: peek8/16/32/poke8/16/32 - no VM-level allocator: an
    // address is just a literal, peek/poke never require any prior
    // reserve()/allocation, exactly like real hardware/MMIO ---

    @Test
    void poke8AndPeek8RoundTrip() {
        assertEquals("200", run("poke8(0, 200); print peek8(0);"));
    }

    @Test
    void poke16AndPeek16RoundTrip() {
        assertEquals("40000", run("poke16(0, 40000); print peek16(0);"));
    }

    @Test
    void poke32AndPeek32RoundTrip() {
        assertEquals("300000000", run("poke32(0, 300000000); print peek32(0);"));
    }

    @Test
    void differentWidthsAtAdjacentAddressesDoNotOverlap() {
        assertEquals("200|40000|300000000", run(
                "poke8(0, 200); poke16(1, 40000); poke32(4, 300000000); " +
                "print peek8(0) + '|' + peek16(1) + '|' + peek32(4);"));
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
    void freeOnARawAddressThrowsSinceThereIsNoRawAllocator() {
        // FREE only ever frees a managed heap value now - a plain int
        // reaching it (whether or not it was ever a "real" address) is
        // always a user error
        assertThrows(Bl0j_VM_Exception.class, () -> run("free(42);"));
    }

    // --- reserve: fixed MMIO-style address ranges - bookkeeping only, to
    // reject overlapping reservations; peek/poke ignore it entirely ---

    @Test
    void peekAndPokeWorkOnAReservedAddressJustLikeAnyOtherOne() {
        assertEquals("42", run(
                "reserve(0, 4); poke32(0, 42); print peek32(0);"));
    }

    @Test
    void peekAndPokeWorkOnAnUnreservedAddressToo() {
        // reserve() carries no special permission - it's pure bookkeeping
        // for reserve() itself, not an access-control boundary
        assertEquals("42", run("poke32(100, 42); print peek32(100);"));
    }

    @Test
    void reservedRangesCannotOverlap() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "reserve(0, 8); reserve(4, 8);"));
    }

    @Test
    void adjacentNonOverlappingReservationsBothSucceed() {
        assertEquals("done", run(
                "reserve(0, 4); reserve(4, 4); print 'done';"));
    }

    @Test
    void reservingPastTheArenaThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "reserve(0, 999999999);",
                vm -> vm.set_max_raw_bytes(64)));
    }
}
