package bl0.bl0jv2.runtime;

/**
 * NaN-boxes every non-double VM value into a single 64-bit word, so the
 * register file can stay a flat {@code long[]} - no per-value Java object
 * sitting in every register. This is the representation a future C port
 * would use directly as a tagged {@code uint64_t}.
 *
 * <p>Layout of a boxed (non-double) value's bit pattern:
 * <pre>
 *   bit  63    : sign, always 0
 *   bits 62-52 : exponent, all 1s (the IEEE754 NaN/Infinity range)
 *   bit  51    : quiet-NaN bit, always 1
 *   bit  50    : boxed-value marker, always 1 - this keeps the pattern
 *                distinct from Double.doubleToLongBits's canonical NaN
 *                (0x7FF8000000000000L), which always has bit 50 = 0, so a
 *                genuine float NaN can never be mistaken for a boxed value
 *   bits 49-47 : 3-bit type tag (INT / BOOL / NIL / REF / CHAR / ...)
 *   bits 46-0  : payload - an int32 value, or an index into the VM's
 *                reference table for REF
 * </pre>
 * Any bit pattern that does not match this reserved prefix is a real,
 * bit-identical IEEE754 double.
 */
public final class NanBox {
    private NanBox() {}

    private static final long EXP_MASK  = 0x7FF0000000000000L;
    private static final long QUIET_BIT = 0x0008000000000000L;
    private static final long BOX_BIT   = 0x0004000000000000L;
    private static final long SIGN_BIT  = 0x8000000000000000L;

    private static final long TAG_PREFIX = EXP_MASK | QUIET_BIT | BOX_BIT;            // 0x7FFC000000000000L
    private static final long TAG_MASK   = SIGN_BIT | EXP_MASK | QUIET_BIT | BOX_BIT; // 0xFFFC000000000000L

    private static final int  TAG_SHIFT     = 47;
    private static final long TAG_BITS_MASK = 0x7L;
    private static final long PAYLOAD_MASK  = 0x00007FFFFFFFFFFFL;

    public static final int TAG_INT  = 0;
    public static final int TAG_BOOL = 1;
    public static final int TAG_NIL  = 2;
    public static final int TAG_REF  = 3;
    public static final int TAG_CHAR = 4;

    public static final long NIL = box(TAG_NIL, 0);

    public static boolean isBoxed(long bits) {
        return (bits & TAG_MASK) == TAG_PREFIX;
    }

    public static int tagOf(long bits) {
        return (int) ((bits >>> TAG_SHIFT) & TAG_BITS_MASK);
    }

    private static long box(int tag, long payload) {
        return TAG_PREFIX | ((long) tag << TAG_SHIFT) | (payload & PAYLOAD_MASK);
    }

    public static long ofInt(int value) {
        return box(TAG_INT, value & 0xFFFFFFFFL);
    }

    // low 32 bits carry the value untouched by the tag prefix, so a plain
    // long->int narrowing cast recovers it (sign included)
    public static int asInt(long bits) {
        return (int) bits;
    }

    public static long ofBoolean(boolean value) {
        return box(TAG_BOOL, value ? 1 : 0);
    }

    public static boolean asBoolean(long bits) {
        return (bits & 1L) != 0L;
    }

    public static long ofRef(int heapIndex) {
        return box(TAG_REF, heapIndex & 0xFFFFFFFFL);
    }

    public static int asRefIndex(long bits) {
        return (int) bits;
    }

    public static long ofChar(char value) {
        return box(TAG_CHAR, value);
    }

    public static char asChar(long bits) {
        return (char) bits;
    }
}
