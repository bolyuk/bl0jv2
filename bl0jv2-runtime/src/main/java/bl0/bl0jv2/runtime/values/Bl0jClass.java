package bl0.bl0jv2.runtime.values;

import bl0.bl0jv2.data.FunDef;
import bl0.bl0jv2.runtime.NanBox;

import java.util.Arrays;

/**
 * A class's fixed shape: its declared fields (in allocation order) and its
 * methods. Shared by every instance of the class - instances only hold
 * their own field values plus a reference to this.
 *
 * <p>Members are looked up by an interned int id ("symbol", see the VM's
 * SymbolTable), not by name: {@link #fieldSlot} and {@link #methodIndex} are
 * a couple of array probes with no string hashing or comparison, which is
 * what GET_FIELD/SET_FIELD/LOOKUP_METHOD pay on every execution. Names are
 * only touched on the error path and by host-side reflection
 * ({@link #fieldNames()}, {@link #toStringMethod()}).
 *
 * <p>Every method's callable value is boxed ONCE, when the class is loaded
 * ({@link #methodRef}), and handed out as-is on every call. LOOKUP_METHOD used
 * to box the FunDef afresh per call, which allocated a heap slot (and took
 * the heap's write lock) for every single method call.
 *
 * <p>Also holds the class's own static field storage: unlike instance
 * fields, a static field's index is resolved entirely at compile time
 * (the access is always through a literal class name), so this is a plain
 * long[] indexed directly - no lookup at runtime.
 *
 * <p>Static field storage is not safe for concurrent mutation from
 * multiple cores - {@link #setStaticFieldRaw} is a plain array write, no
 * locking. This matters in practice: static fields are exactly how
 * bl0jv2 kernel code holds global state (see aeon-os's own {@code Kernel}
 * class), so a scheduler touching them from more than one core needs its
 * own {@code Mutex}, same as {@link Bl0jArray}.
 */
public final class Bl0jClass {
    public final String name;
    private final String[] fieldNames;
    private final long[] fieldDefaults;
    private final IntTable fieldSlots;

    private final String[] methodNames;
    private final FunDef[] methodDefs;
    private final long[] methodRefs;
    private final IntTable methodSlots;
    private final int toStringIndex;
    private final int equalsIndex;

    private final long[] staticFields;

    public Bl0jClass(String name,
                     String[] fieldNames, int[] fieldSymbols, long[] fieldDefaults,
                     String[] methodNames, int[] methodSymbols, FunDef[] methodDefs, long[] methodRefs,
                     int staticFieldCount) {
        this.name = name;
        this.fieldNames = fieldNames;
        this.fieldDefaults = fieldDefaults;
        this.fieldSlots = new IntTable(fieldSymbols);
        this.methodNames = methodNames;
        this.methodDefs = methodDefs;
        this.methodRefs = methodRefs;
        this.methodSlots = new IntTable(methodSymbols);
        this.toStringIndex = indexOfName(methodNames, "toString");
        this.equalsIndex = indexOfName(methodNames, "equals");
        this.staticFields = new long[staticFieldCount];
        Arrays.fill(staticFields, NanBox.NIL);
    }

    private static int indexOfName(String[] names, String wanted) {
        for (int i = 0; i < names.length; i++)
            if (names[i].equals(wanted))
                return i;
        return -1;
    }

    // a fresh instance's own fields start as a copy of this - see
    // Bl0jInstance's constructor
    public long[] fieldDefaults() {
        return fieldDefaults;
    }

    public long getStaticFieldRaw(int index) {
        return staticFields[index];
    }

    public void setStaticFieldRaw(int index, long value) {
        staticFields[index] = value;
    }

    public int staticFieldCount() {
        return staticFields.length;
    }

    public int fieldCount() {
        return fieldNames.length;
    }

    public String[] fieldNames() {
        return fieldNames;
    }

    /** slot of the field with this symbol id, or -1 - fields are a fixed, closed set per class */
    public int fieldSlot(int symbol) {
        return fieldSlots.get(symbol);
    }

    /** index of the method with this symbol id, or -1 */
    public int methodIndex(int symbol) {
        return methodSlots.get(symbol);
    }

    /** the method's callable value, already boxed - valid for the VM that loaded this class */
    public long methodRef(int methodIndex) {
        return methodRefs[methodIndex];
    }

    public FunDef methodDef(int methodIndex) {
        return methodDefs[methodIndex];
    }

    public String methodName(int methodIndex) {
        return methodNames[methodIndex];
    }

    public int methodCount() {
        return methodNames.length;
    }

    /** the class's own toString(), or null - consulted when printing an instance */
    public FunDef toStringMethod() {
        return toStringIndex < 0 ? null : methodDefs[toStringIndex];
    }

    /** the class's own equals(other), or null - consulted by == */
    public FunDef equalsMethod() {
        return equalsIndex < 0 ? null : methodDefs[equalsIndex];
    }

    @Override
    public String toString() {
        return "class " + name;
    }

    // tiny open-addressing int -> int map (symbol id -> slot/index). Keys
    // are stored +1 so 0 can mean "empty"; the table is a power of two at
    // least twice the member count, so probes stay short and a miss
    // terminates at the first empty cell.
    private static final class IntTable {
        private final int[] keys;
        private final int[] values;
        private final int mask;

        IntTable(int[] symbols) {
            int size = 4;
            while (size < symbols.length * 2)
                size <<= 1;
            keys = new int[size];
            values = new int[size];
            mask = size - 1;
            for (int i = 0; i < symbols.length; i++) {
                int pos = symbols[i] & mask;
                while (keys[pos] != 0 && keys[pos] != symbols[i] + 1)
                    pos = (pos + 1) & mask;
                keys[pos] = symbols[i] + 1;
                values[pos] = i;
            }
        }

        int get(int symbol) {
            int pos = symbol & mask;
            int want = symbol + 1;
            while (true) {
                int k = keys[pos];
                if (k == want)
                    return values[pos];
                if (k == 0)
                    return -1;
                pos = (pos + 1) & mask;
            }
        }
    }
}
