package bl0.bl0jv2.runtime;

import bl0.bl0jv2.runtime.values.Bl0jArray;
import bl0.bl0jv2.runtime.values.Bl0jCell;
import bl0.bl0jv2.runtime.values.Bl0jClass;
import bl0.bl0jv2.runtime.values.Bl0jClosure;
import bl0.bl0jv2.runtime.values.Bl0jInstance;
import bl0.bl0jv2.runtime.values.Bl0jTuple;

import java.util.ArrayDeque;
import java.util.BitSet;

/**
 * One mark-and-sweep pass over a {@link Heap}: everything reachable from the
 * roots the VM hands in stays, every other in-use slot is reclaimed.
 *
 * <p>A reference is a NanBox REF, so "reachable" means: a root value is a REF
 * to the slot, or a reachable object holds such a value (array and tuple
 * elements, instance fields, a cell's value, a closure's captured cells, a
 * class's static fields). Strings, functions, errors, mutexes and events hold
 * no references. Roots are the constant pool, every live register of every
 * frame, and the handler functions registered with the interrupt controller.
 *
 * <p>The VM only runs this at an instruction boundary of a single-core
 * program - see Bl0jv2_jVM.collectGarbage() for why that is the one place it
 * is safe.
 */
final class Collector {
    private final Heap heap;
    private final BitSet marked;
    private final ArrayDeque<Integer> work = new ArrayDeque<>();

    Collector(Heap heap) {
        this.heap = heap;
        this.marked = new BitSet(heap.size());
    }

    void markValue(long bits) {
        if (!NanBox.isBoxed(bits) || NanBox.tagOf(bits) != NanBox.TAG_REF)
            return;
        int slot = NanBox.asRefIndex(bits);
        if (slot < 0 || slot >= heap.size() || marked.get(slot))
            return;
        marked.set(slot);
        work.push(slot);
    }

    void markValues(long[] values) {
        for (long v : values)
            markValue(v);
    }

    /** marks what a (non-heap) object holds - used for registered handler functions */
    void markObject(Object o) {
        if (o instanceof Bl0jClosure closure) {
            markValues(closure.capturedCells());
        }
        // a FunDef holds nothing; everything else is reached through a REF
    }

    /** follows references until nothing new is reachable */
    void drain() {
        while (!work.isEmpty()) {
            Object o = heap.peek(work.pop());
            if (o == null)
                continue; // freed explicitly, or never used
            switch (o) {
                case Bl0jArray arr -> {
                    for (int i = 0; i < arr.length(); i++)
                        markValue(arr.getRaw(i));
                }
                case Bl0jTuple tuple -> {
                    for (int i = 0; i < tuple.length(); i++)
                        markValue(tuple.getRaw(i));
                }
                case Bl0jInstance instance -> {
                    for (int i = 0; i < instance.cls.fieldCount(); i++)
                        markValue(instance.getFieldRaw(i));
                }
                case Bl0jCell cell -> markValue(cell.value);
                case Bl0jClosure closure -> markValues(closure.capturedCells());
                case Bl0jClass cls -> {
                    for (int i = 0; i < cls.staticFieldCount(); i++)
                        markValue(cls.getStaticFieldRaw(i));
                }
                default -> { }
            }
        }
    }

    /** reclaims everything not marked; returns how many slots it freed */
    int sweep() {
        return heap.sweep(marked);
    }
}
