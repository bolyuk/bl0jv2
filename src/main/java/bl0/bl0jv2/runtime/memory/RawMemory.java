package bl0.bl0jv2.runtime.memory;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A single flat address space for alloc/free/peek/poke - addresses are
 * plain bl0jv2 ints, deliberately not tied to which alloc() produced them
 * (peek/poke can address anywhere in it), mirroring how real hardware/MMIO
 * has no isolation between allocations. This is a stand-in for real
 * physical memory until the VM itself moves off the JVM host.
 *
 * <p>Now that multiple VM cores can call into this concurrently: alloc/free/
 * reserve take the write lock (they mutate the allocator's own bookkeeping
 * and must be mutually exclusive with each other). peek/poke touch only the
 * raw byte[] directly, genuinely disjoint state from the allocator's
 * bookkeeping - they take the READ lock, not because they conflict with
 * each other, but purely for the JMM happens-before edge from the last
 * allocator write (without it, a poke() on one core has no visibility
 * guarantee to a peek() on another). Two cores poke()-ing the *same*
 * address concurrently stays a genuine, accepted race - same spirit as this
 * class's own "no isolation between allocations" MMIO comment above.
 */
public final class RawMemory {
    private static final int DEFAULT_BYTES = 1 << 20; // 1 MiB

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private byte[] memory;
    private final TreeMap<Integer, Integer> allocations = new TreeMap<>(); // address -> size, currently live
    private final List<int[]> freeList = new ArrayList<>(); // [address, size] free blocks, first-fit, no coalescing
    private int bumpPointer;
    // addresses reserved via reserve() - a subset of 'allocations' keys that
    // free() refuses to release, so a fixed MMIO region can never end up
    // back in the general free list
    private final Set<Integer> reservedAddresses = new HashSet<>();
    // 0 = use DEFAULT_BYTES; must be set before reset() (which is what
    // actually sizes 'memory') to take effect
    private long maxBytes = 0;

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    // (re)sizes the arena and clears every allocation/free-list entry - call
    // once per program load, mirroring the managed heap's own reset in
    // feed_compiled_file()
    public void reset() {
        memory = new byte[maxBytes > 0 ? (int) maxBytes : DEFAULT_BYTES];
        allocations.clear();
        freeList.clear();
        bumpPointer = 0;
        reservedAddresses.clear();
    }

    // first-fit over freeList (no coalescing of adjacent free blocks - an
    // accepted simplification for this JVM-hosted prototype), falling back
    // to the bump pointer when nothing frees up
    public int alloc(int size) {
        lock.writeLock().lock();
        try {
            if (size <= 0)
                throw new Bl0j_VM_Exception("alloc size must be positive");

            for (int i = 0; i < freeList.size(); i++) {
                int[] block = freeList.get(i);
                if (block[1] >= size) {
                    int addr = block[0];
                    if (block[1] == size) freeList.remove(i);
                    else { block[0] += size; block[1] -= size; }
                    allocations.put(addr, size);
                    return addr;
                }
            }

            if (bumpPointer + size > memory.length)
                throw new Bl0j_VM_Exception("out of memory: raw memory limit (" + memory.length + " bytes) reached");
            int addr = bumpPointer;
            bumpPointer += size;
            allocations.put(addr, size);
            return addr;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void free(int addr) {
        lock.writeLock().lock();
        try {
            if (reservedAddresses.contains(addr))
                throw new Bl0j_VM_Exception("cannot free reserved address " + addr);
            Integer size = allocations.remove(addr);
            if (size == null)
                throw new Bl0j_VM_Exception("invalid free: address " + addr + " is not currently allocated");
            freeList.add(new int[]{addr, size});
        } finally {
            lock.writeLock().unlock();
        }
    }

    // reserves [addr, addr+size) as permanently allocated - for a fixed
    // hardware address (an MMIO device's register range) that alloc() must
    // never hand out and free() can never release. Must be at or past the
    // current bump pointer: this simple allocator can't retroactively carve
    // a hole out of space it may already have handed out via alloc(), so
    // reserving always advances the bump pointer past the reserved region -
    // any gap between the old bump pointer and addr becomes permanently
    // unusable, the same way a real address space leaves room below a fixed
    // device mapping.
    public void reserve(int addr, int size) {
        lock.writeLock().lock();
        try {
            if (size <= 0)
                throw new Bl0j_VM_Exception("reserve size must be positive");
            if (addr < bumpPointer)
                throw new Bl0j_VM_Exception("cannot reserve address " + addr + ": already past the allocator's bump pointer (" + bumpPointer + ")");
            if ((long) addr + size > memory.length)
                throw new Bl0j_VM_Exception("cannot reserve: out of raw memory range (" + memory.length + " bytes)");

            allocations.put(addr, size);
            reservedAddresses.add(addr);
            bumpPointer = addr + size;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void checkBounds(int addr, int widthBytes) {
        if (addr < 0 || (long) addr + widthBytes > memory.length)
            throw new Bl0j_VM_Exception("raw memory access out of bounds: address " + addr + " width " + (widthBytes * 8) + " bits");
    }

    // big-endian, matching this project's own bytecode format - not
    // bounds-checked against any specific allocation's own size, only
    // against the arena as a whole: peek/poke can address anywhere in it
    // regardless of which alloc() (if any) produced that address, same as
    // real hardware/MMIO has no isolation between allocations
    public long peek(int addr, int widthBytes) {
        lock.readLock().lock();
        try {
            checkBounds(addr, widthBytes);
            long value = 0;
            for (int i = 0; i < widthBytes; i++)
                value = (value << 8) | (memory[addr + i] & 0xFFL);
            return value;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void poke(int addr, int widthBytes, long value) {
        lock.readLock().lock();
        try {
            checkBounds(addr, widthBytes);
            for (int i = widthBytes - 1; i >= 0; i--) {
                memory[addr + i] = (byte) value;
                value >>>= 8;
            }
        } finally {
            lock.readLock().unlock();
        }
    }
}
