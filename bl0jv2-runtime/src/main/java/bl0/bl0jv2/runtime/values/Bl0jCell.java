package bl0.bl0jv2.runtime.values;

import bl0.bl0jv2.runtime.NanBox;

/**
 * A single mutable NaN-boxed slot on the heap. Backs a captured (closed-over)
 * local: both the enclosing function and every closure that captures it
 * hold a reference to the same cell, so a write from either side is visible
 * to the other - a plain register can't do that, since it dies with its
 * frame.
 */
public final class Bl0jCell {
    public long value = NanBox.NIL;

    @Override
    public String toString() {
        return "cell";
    }
}
