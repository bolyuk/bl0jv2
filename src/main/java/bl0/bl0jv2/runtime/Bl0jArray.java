package bl0.bl0jv2.runtime;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.Arrays;

/**
 * A mutable, growable array value. Elements are kept as raw NaN-boxed longs
 * (see {@link NanBox}), the same representation registers use, so copying
 * an element in or out never needs to box/unbox.
 */
public final class Bl0jArray {
    private long[] elements;
    private int size;
    private final Bl0jv2_jVM owner;

    Bl0jArray(long[] elements, Bl0jv2_jVM owner) {
        this.elements = elements;
        this.size = elements.length;
        this.owner = owner;
    }

    public int length() {
        return size;
    }

    public long getRaw(int index) {
        index = normalizeIndex(index, size);
        checkBounds(index);
        return elements[index];
    }

    public void setRaw(int index, long value) {
        index = normalizeIndex(index, size);
        checkBounds(index);
        elements[index] = value;
    }

    // arr[-1] means "last element", arr[-2] "second to last", etc.
    public static int normalizeIndex(int index, int length) {
        return index < 0 ? index + length : index;
    }

    public void push(long value) {
        if (size == elements.length)
            elements = Arrays.copyOf(elements, elements.length == 0 ? 4 : elements.length * 2);
        elements[size++] = value;
    }

    public long pop() {
        if (size == 0)
            throw new Bl0j_VM_Exception("pop from empty array");
        return elements[--size];
    }

    private void checkBounds(int index) {
        if (index < 0 || index >= size)
            throw new Bl0j_VM_Exception("array index out of bounds: " + index + " (length " + size + ")");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(owner.unbox(elements[i]));
        }
        return sb.append("]").toString();
    }
}
