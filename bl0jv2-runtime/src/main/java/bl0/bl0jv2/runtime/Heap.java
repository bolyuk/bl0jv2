package bl0.bl0jv2.runtime;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import bl0.bl0jv2.runtime.values.Bl0jArray;
import bl0.bl0jv2.runtime.values.Bl0jInstance;
import bl0.bl0jv2.runtime.values.Bl0jTuple;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

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
    private long minThresholdBytes = 64L << 20;
    private long collectThresholdBytes = minThresholdBytes;
    private volatile boolean collectWanted;

    // ---- who holds how much: every slot is charged to an account (the VM says which, from the core that allocates),
    // at an estimated size. 'used' follows allocations, free() and sweeps; the sizes of arrays and instances that
    // grow afterwards are brought up to date by recount(). An account may have a limit; the total may too.
    static final int ACCOUNTS = 4096;
    private int[] owners = new int[1024];
    private int[] weights = new int[1024];
    private final long[] used = new long[ACCOUNTS];
    private final long[] limits = new long[ACCOUNTS];
    private long usedTotal;
    private long totalLimit;
    private long addsSinceRecount;
    private IntSupplier ownerSource = () -> 0;
    private BooleanSupplier gcPossible = () -> false;
    private BooleanSupplier kernelMode = () -> false;     // limits bind a program, not the kernel running on its behalf

    /** how much allocation (estimated bytes) triggers a collection request; the floor of the adaptive threshold */
    void setCollectThresholdBytes(long bytes) {
        lock.lock();
        try {
            minThresholdBytes = bytes;
            collectThresholdBytes = bytes;
        } finally {
            lock.unlock();
        }
    }

    void setMaxEntries(long maxEntries) {
        this.maxEntries = maxEntries;
    }

    /** stores the value and returns its slot; throws when the entry limit is reached */
    int add(Object value) {
        lock.lock();
        try {
            long w = weigh(value);
            int owner = ownerSource.getAsInt();
            checkLimits(owner, w);
            bytesSinceCollect += w;
            if (bytesSinceCollect >= collectThresholdBytes
                    || (maxEntries > 0 && live >= maxEntries - maxEntries / 4))
                collectWanted = true;
            if (++addsSinceRecount >= Math.max(2048, live / 4) && hasLimits())
                recountLocked();

            int slot;
            if (!freeSlots.isEmpty()) {
                slot = freeSlots.pop();
                slots[slot] = value;
                live++;
            } else {
                if (maxEntries > 0 && live >= maxEntries)
                    throw new Bl0j_VM_Exception("out of memory: heap entry limit (" + maxEntries + ") reached");
                if (size == slots.length) {
                    slots = Arrays.copyOf(slots, size * 2);
                    owners = Arrays.copyOf(owners, size * 2);
                    weights = Arrays.copyOf(weights, size * 2);
                }
                slots[size] = value;
                live++;
                slot = size++;
            }
            owners[slot] = owner;
            weights[slot] = (int) w;
            used[owner] += w;
            usedTotal += w;
            return slot;
        } finally {
            lock.unlock();
        }
    }

    private boolean hasLimits() {
        return totalLimit > 0 || limitedAccounts > 0;
    }

    private int limitedAccounts;

    // How much more than its limit an account may take once it has been told: enough for the program's own handler to
    // report the error (an error needs a string, a message another).
    private static final long RESERVE = 16384;
    private final boolean[] breached = new boolean[ACCOUNTS];
    private boolean breachedTotal;

    // refuses an allocation that would take an account (or the whole heap) over its limit; when a collection could
    // make room it is asked for first and the allocation goes through, up to a quarter more than the limit. The first
    // refusal throws and opens the reserve above, so the error can be handled; beyond the reserve every allocation is
    // refused until the account is back under its limit.
    private void checkLimits(int owner, long w) {
        if (kernelMode.getAsBoolean()) return;
        long limit = limits[owner];
        if (limit > 0 && used[owner] + w > limit) {
            if (gcPossible.getAsBoolean() && used[owner] + w <= limit + limit / 4) {
                collectWanted = true;
            } else if (!breached[owner]) {
                breached[owner] = true;
                throw new Bl0j_VM_Exception("out of memory: the limit of " + limit + " bytes for this process is reached");
            } else if (used[owner] + w > limit + RESERVE) {
                throw new Bl0j_VM_Exception("out of memory: the limit of " + limit + " bytes for this process is reached");
            }
        }
        if (totalLimit > 0 && usedTotal + w > totalLimit) {
            if (gcPossible.getAsBoolean() && usedTotal + w <= totalLimit + totalLimit / 4) {
                collectWanted = true;
            } else if (!breachedTotal) {
                breachedTotal = true;
                throw new Bl0j_VM_Exception("out of memory: the heap limit of " + totalLimit + " bytes is reached");
            } else if (usedTotal + w > totalLimit + RESERVE) {
                throw new Bl0j_VM_Exception("out of memory: the heap limit of " + totalLimit + " bytes is reached");
            }
        }
    }

    // rough size in bytes: a fixed per-entry cost plus what a string or an
    // array actually holds
    private static long weigh(Object value) {
        if (value instanceof String s)
            return 48 + 2L * s.length();
        if (value instanceof Bl0jArray a)
            return 64 + 8L * a.length();
        if (value instanceof Bl0jTuple t)
            return 64 + 8L * t.length();
        if (value instanceof Bl0jInstance i)
            return 64 + 8L * i.cls.fieldCount();
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
            release(slot);
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
            owners = new int[1024];
            weights = new int[1024];
            Arrays.fill(used, 0);
            usedTotal = 0;
            addsSinceRecount = 0;
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
                release(i);
                slots[i] = FREED;
                freeSlots.push(i);
                live--;
                freed++;
            }
            bytesSinceCollect = 0;
            collectWanted = false;
            // next time: wait until a multiple of what survived (each live
            // entry counted at ~64 bytes) has been allocated, but never
            // collect more often than the configured minimum
            collectThresholdBytes = Math.max(minThresholdBytes, (long) live * 64);
            return freed;
        } finally {
            lock.unlock();
        }
    }

    int liveCount() {
        return live;
    }

    // ---- accounts

    /** takes a slot's charge back from its account */
    private void release(int slot) {
        int o = owners[slot];
        used[o] = Math.max(0, used[o] - weights[slot]);
        usedTotal = Math.max(0, usedTotal - weights[slot]);
        weights[slot] = 0;
        if (breached[o] && used[o] <= limits[o]) breached[o] = false;
        if (breachedTotal && usedTotal <= totalLimit) breachedTotal = false;
    }

    void setAccounting(IntSupplier ownerSource, BooleanSupplier gcPossible, BooleanSupplier kernelMode) {
        this.ownerSource = ownerSource;
        this.gcPossible = gcPossible;
        this.kernelMode = kernelMode;
    }

    long usedBy(int account) {
        lock.lock();
        try {
            return used[account];
        } finally {
            lock.unlock();
        }
    }

    long usedTotal() {
        return usedTotal;
    }

    long limitOf(int account) {
        return limits[account];
    }

    void setLimit(int account, long bytes) {
        lock.lock();
        try {
            if (limits[account] > 0) limitedAccounts--;
            limits[account] = Math.max(0, bytes);
            breached[account] = false;
            if (limits[account] > 0) limitedAccounts++;
        } finally {
            lock.unlock();
        }
    }

    long totalLimit() {
        return totalLimit;
    }

    void setTotalLimit(long bytes) {
        totalLimit = Math.max(0, bytes);
        breachedTotal = false;
    }

    int slotCapacity() {
        return slots.length;
    }

    /** moves what an account holds to account 0 and takes its limit off: the process that held it has ended */
    void releaseAccount(int account) {
        if (account == 0) return;
        lock.lock();
        try {
            for (int i = 0; i < size; i++) {
                if (owners[i] == account && slots[i] != null && slots[i] != FREED) owners[i] = 0;
            }
            used[0] += used[account];
            used[account] = 0;
            if (limits[account] > 0) limitedAccounts--;
            limits[account] = 0;
        } finally {
            lock.unlock();
        }
    }

    /** the sizes of everything, as they are now (arrays grow after they are made) */
    void recount() {
        lock.lock();
        try {
            recountLocked();
        } finally {
            lock.unlock();
        }
    }

    private void recountLocked() {
        Arrays.fill(used, 0);
        usedTotal = 0;
        for (int i = 0; i < size; i++) {
            Object v = slots[i];
            if (v == null || v == FREED) continue;
            long w = weigh(v);
            weights[i] = (int) Math.min(w, Integer.MAX_VALUE);
            used[owners[i]] += w;
            usedTotal += w;
        }
        addsSinceRecount = 0;
    }
}
