package bl0.bl0jv2.runtime.values;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;

/**
 * An immutable, fixed-length value, Python-tuple style. Unlike
 * {@link Bl0jArray} (compared by reference, like every other mutable
 * reference type here), a tuple is compared by content - that's the
 * point of it being immutable.
 */
public final class Bl0jTuple {
    private final long[] elements;
    private final Bl0jv2_jVM owner;

    public Bl0jTuple(long[] elements, Bl0jv2_jVM owner) {
        this.elements = elements;
        this.owner = owner;
    }

    public int length() {
        return elements.length;
    }

    public long getRaw(int index) {
        int i = Bl0jArray.normalizeIndex(index, elements.length);
        if (i < 0 || i >= elements.length)
            throw new Bl0j_VM_Exception("tuple index out of bounds: " + index + " (length " + elements.length + ")");
        return elements[i];
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Bl0jTuple other) || elements.length != other.elements.length)
            return false;
        for (int i = 0; i < elements.length; i++) {
            Object a = owner.unbox(elements[i]);
            Object b = other.owner.unbox(other.elements[i]);
            if (!Bl0jv2_jVM.valuesEqual(a, b))
                return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (long e : elements) {
            Object v = owner.unbox(e);
            result = 31 * result + (v == null ? 0 : v.hashCode());
        }
        return result;
    }

    @Override
    public String toString() {
        if (!PrintGuard.enter(this))
            return "(...)";
        try {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < elements.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(owner.unbox(elements[i]));
            }
            return sb.append(")").toString();
        } finally {
            PrintGuard.exit(this);
        }
    }
}
