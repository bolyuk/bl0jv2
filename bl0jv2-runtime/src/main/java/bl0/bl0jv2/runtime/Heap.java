package bl0.bl0jv2.runtime;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The VM's managed heap: everything a register holds as a NanBox REF (strings,
 * arrays, instances, closures, ...) lives in a slot here, the REF being the
 * slot index.
 *
 * <p>Reads ({@link #get}) take no lock: slots live in a volatile array that is
 * only ever replaced by a bigger copy, so a reader either sees the value or
 * falls back to a locked re-read. This is the hottest path in the interpreter
 * (every unbox of a string/array/instance), and with several cores it used to
 * serialise them on a read-write lock. Allocation, explicit free and
 * collection take the lock.
 *
 * <p>Slots come back two ways: an explicit free() (the FREE opcode), and
 * {@link #sweep}, which the VM's collector calls with the set of slots it
 * found reachable. A reclaimed slot holds {@link #FREED} until it is reused,
 * so a stale reference reads as "use after free" instead of someone else's
 * value.
 */
final class Heap {
    /** marks a freed slot - distinct from the VM's nil so a freed value is never mistaken for a field that holds nil */
    static final Object FREED = new Object() {
        @Override
        public String toString() { return "<freed>"; }
    };

    private final ReentrantLock lock = new ReentrantLock();
    private volatile Object[] slots = new Object[1024];
    private int size;                                   // slots in use or freed, guarded by lock
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>(); // guarded by lock
    private long maxEntries;                            // 0 = unlimited
    private int live;                                   // slots holding a real value, guarded by lock

    // allocation accounting for the collector's trigger: an estimate of the
    // bytes handed out since the last collection (entry count alone would
    // let a loop that builds ever-longer strings retain gigabytes first)
    private long bytesSinceCollect;
    private long collectThresholdBytes = DEFAULT_THRESHOLD_BYTES;
    private volatile boolean collectWanted;
    private static final long DEFAULT_THRESHOLD_BYTES = 64L << 20;

    void setMaxEntries(long maxEntries) {
        this.maxEntries = maxEntries;
    }

    /** stores the value and returns its slot; throws when the entry limit is reached */
    int add(Object value) {
        lock.lock();
        try {
            bytesSinceCollect += weigh(value);
            if (bytesSinceCollect >= collectThresholdBytes
                    || (maxEntries > 0 && live >= maxEntries - maxEntries / 4))
                collectWanted = true;

            if (!freeSlots.isEmpty()) {
                int slot = freeSlots.pop();
                slots[slot] = value;
                live++;
                return slot;
            }
            if (maxEntries > 0 && live >= maxEntries)
                throw new Bl0j_VM_Exception("out of memory: heap entry limit (" + maxEntries + ") reached");
            if (size == slots.length)
                slots = Arrays.copyOf(slots, size * 2);
            slots[size] = value;
            live++;
            return size++;
        } finally {
            lock.unlock();
        }
    }

    // rough size in bytes: a fixed per-entry cost plus what a string or an
    // array actually holds
    private static long weigh(Object value) {
        if (value instanceof String s)
            return 48 + 2L * s.length();
        return 64;
    }

    /** the value in a slot; may return {@link #FREED}. No lock on the fast path. */
    Object get(int slot) {
        Object[] a = slots;
        if (slot >= 0 && slot < a.length) {
            Object v = a[slot];
            if (v != null)
                return v;
        }
        // not visible yet to this thread (or never valid): decide under the lock
        lock.lock();
        try {
            if (slot < 0 || slot >= size)
                throw new Bl0j_VM_Exception("invalid heap reference " + slot);
            return slots[slot];
        } finally {
            lock.unlock();
        }
    }

    /** frees one slot; false if it was already free */
    boolean free(int slot) {
        lock.lock();
        try {
            if (slot < 0 || slot >= size || slots[slot] == FREED)
                return false;
            slots[slot] = FREED;
            freeSlots.push(slot);
            live--;
            return true;
        } finally {
            lock.unlock();
        }
    }

    void clear() {
        lock.lock();
        try {
            slots = new Object[1024];
            size = 0;
            live = 0;
            freeSlots.clear();
            bytesSinceCollect = 0;
            collectWanted = false;
        } finally {
            lock.unlock();
        }
    }

    // ---- collection support (the VM calls these only while no program is running) ----

    boolean collectWanted() {
        return collectWanted;
    }

    int size() {
        return size;
    }

    /** the value in a slot for the collector's own traversal, or null for a freed/unused slot */
    Object peek(int slot) {
        Object v = slots[slot];
        return v == FREED ? null : v;
    }

    /** reclaims every in-use slot not set in 'reachable'; returns how many it freed */
    int sweep(BitSet reachable) {
        lock.lock();
        try {
            int freed = 0;
            for (int i = 0; i < size; i++) {
                Object v = slots[i];
                if (v == null || v == FREED || reachable.get(i))
                    continue;
                slots[i] = FREED;
                freeSlots.push(i);
                live--;
                freed++;
            }
            bytesSinceCollect = 0;
            collectWanted = false;
            // next time: wait until about as much again as survived has been
            // allocated, but never collect more often than every 64 MB
            collectThresholdBytes = Math.max(DEFAULT_THRESHOLD_BYTES, (long) live * 64);
            return freed;
        } finally {
            lock.unlock();
        }
    }

    int liveCount() {
        return live;
    }
}
