package bl0.bl0jv2.runtime.memory;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A single flat address space for peek/poke - addresses are plain bl0jv2
 * ints, and peek/poke can address anywhere in it regardless of which (if
 * any) reserve() call covers that address, mirroring how real hardware/MMIO
 * has no isolation between regions. This is a stand-in for real physical
 * memory until the VM itself moves off the JVM host.
 *
 * <p>There is deliberately no VM-level allocator here (no alloc()/free()):
 * real hardware doesn't provide one either - a real OS allocates its own
 * structures on top of this raw addressable space, the same way it would on
 * physical memory. reserve() only exists to carve out fixed ranges (e.g. an
 * MMIO device's register block) that the OS's own allocator should treat as
 * off-limits; RawMemory itself has no opinion on what the rest of the
 * address space is used for.
 *
 * <p>Now that multiple VM cores can call into this concurrently: reserve()
 * takes the write lock (it mutates reservedRegions, which must be mutually
 * exclusive with itself). peek/poke touch only the raw byte[] directly,
 * genuinely disjoint state from reservedRegions - they take the READ lock,
 * not because they conflict with each other, but purely for the JMM
 * happens-before edge from the last reserve() write (without it, a poke()
 * on one core has no visibility guarantee to a peek() on another). Two
 * cores poke()-ing the *same* address concurrently stays a genuine,
 * accepted race - same spirit as this class's own "no isolation between
 * regions" MMIO comment above.
 */
public final class RawMemory {
    private static final int DEFAULT_BYTES = 1 << 20; // 1 MiB

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private byte[] memory;
    // address -> size, ranges reserved via reserve() - bookkeeping only, to
    // reject overlapping reservations; unrelated to peek/poke, which can
    // still touch any address regardless of reservation
    private final TreeMap<Integer, Integer> reservedRegions = new TreeMap<>();
    // 0 = use DEFAULT_BYTES; must be set before reset() (which is what
    // actually sizes 'memory') to take effect
    private long maxBytes = 0;

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    // (re)sizes the arena and clears every reservation - call once per
    // program load, mirroring the managed heap's own reset in
    // feed_compiled_file()
    public void reset() {
        memory = new byte[maxBytes > 0 ? (int) maxBytes : DEFAULT_BYTES];
        reservedRegions.clear();
    }

    // reserves [addr, addr+size) - for a fixed hardware address (an MMIO
    // device's register range) that the OS's own allocator should never
    // hand out for anything else. Only rejects overlap against *other*
    // reservations and the arena's own bounds; peek/poke remain unaffected
    // either way, matching real MMIO's "no isolation" stance.
    public void reserve(int addr, int size) {
        lock.writeLock().lock();
        try {
            if (size <= 0)
                throw new Bl0j_VM_Exception("reserve size must be positive");
            if (addr < 0 || (long) addr + size > memory.length)
                throw new Bl0j_VM_Exception("cannot reserve: out of raw memory range (" + memory.length + " bytes)");

            Map.Entry<Integer, Integer> prev = reservedRegions.floorEntry(addr);
            if (prev != null && (long) prev.getKey() + prev.getValue() > addr)
                throw new Bl0j_VM_Exception("cannot reserve [" + addr + ", " + (addr + size) + "): overlaps existing reservation [" + prev.getKey() + ", " + (prev.getKey() + prev.getValue()) + ")");
            Map.Entry<Integer, Integer> next = reservedRegions.ceilingEntry(addr);
            if (next != null && (long) addr + size > next.getKey())
                throw new Bl0j_VM_Exception("cannot reserve [" + addr + ", " + (addr + size) + "): overlaps existing reservation [" + next.getKey() + ", " + (next.getKey() + next.getValue()) + ")");

            reservedRegions.put(addr, size);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void checkBounds(int addr, int widthBytes) {
        if (addr < 0 || (long) addr + widthBytes > memory.length)
            throw new Bl0j_VM_Exception("raw memory access out of bounds: address " + addr + " width " + (widthBytes * 8) + " bits");
    }

    // big-endian, matching this project's own bytecode format - not
    // bounds-checked against any specific region's own size, only against
    // the arena as a whole: peek/poke can address anywhere in it regardless
    // of which (if any) reserve() call covers that address, same as real
    // hardware/MMIO has no isolation between regions
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

    // bulk transfers for DMA-style devices (see DiskController)
    public void readBytes(int addr, byte[] into) {
        lock.readLock().lock();
        try {
            checkBounds(addr, into.length);
            System.arraycopy(memory, addr, into, 0, into.length);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void writeBytes(int addr, byte[] from) {
        lock.readLock().lock();
        try {
            checkBounds(addr, from.length);
            System.arraycopy(from, 0, memory, addr, from.length);
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

    // fixed 32-bit word width - this VM's reference-impl stand-in for a
    // lock-prefixed x86 instruction (`lock cmpxchg`/`lock xadd`). Takes the
    // WRITE lock, unlike plain peek/poke: this is a genuine read-modify-
    // write that must be indivisible with respect to every other core's own
    // atomic op on the same word, not just visible-after-the-fact like a
    // plain poke(). Both return the value that was there *before* the
    // operation.
    public int compareAndSwap(int addr, int expected, int newValue) {
        lock.writeLock().lock();
        try {
            checkBounds(addr, 4);
            int current = readWord(addr);
            if (current == expected)
                writeWord(addr, newValue);
            return current;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int atomicAdd(int addr, int delta) {
        lock.writeLock().lock();
        try {
            checkBounds(addr, 4);
            int current = readWord(addr);
            writeWord(addr, current + delta);
            return current;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // no locking of their own - only ever called from inside
    // compareAndSwap()/atomicAdd(), which already hold the write lock for
    // their whole read-modify-write
    private int readWord(int addr) {
        int value = 0;
        for (int i = 0; i < 4; i++)
            value = (value << 8) | (memory[addr + i] & 0xFF);
        return value;
    }

    private void writeWord(int addr, int value) {
        for (int i = 3; i >= 0; i--) {
            memory[addr + i] = (byte) value;
            value >>>= 8;
        }
    }
}
