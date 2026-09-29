package bl0.bl0jv2.runtime.values;

import bl0.bl0jv2.data.FunDef;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import bl0.bl0jv2.runtime.NanBox;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A class's fixed shape: its declared field names (in allocation order) and
 * its methods. Shared by every instance of the class - instances only hold
 * their own field values plus a reference to this.
 *
 * <p>Also holds the class's own static field storage: unlike instance
 * fields, a static field's index is resolved entirely at compile time
 * (the access is always through a literal class name), so this is a plain
 * long[] indexed directly - no name lookup at runtime.
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
    private final List<String> fieldNames;
    private final long[] fieldDefaults;
    private final Map<String, FunDef> methods;
    private final long[] staticFields;

    public Bl0jClass(String name, List<String> fieldNames, long[] fieldDefaults, Map<String, FunDef> methods, int staticFieldCount) {
        this.name = name;
        this.fieldNames = fieldNames;
        this.fieldDefaults = fieldDefaults;
        this.methods = methods;
        this.staticFields = new long[staticFieldCount];
        Arrays.fill(staticFields, NanBox.NIL);
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

    public int fieldCount() {
        return fieldNames.size();
    }

    public List<String> fieldNames() {
        return fieldNames;
    }

    // fields are a fixed, closed set per class - misspelling one is a clear
    // error here rather than silently creating a new dynamic property
    public int fieldIndex(String fieldName) {
        int idx = fieldNames.indexOf(fieldName);
        if (idx < 0)
            throw new Bl0j_VM_Exception("class " + name + " has no field '" + fieldName + "'");
        return idx;
    }

    public FunDef method(String methodName) {
        FunDef fun = methods.get(methodName);
        if (fun == null)
            throw new Bl0j_VM_Exception("class " + name + " has no method '" + methodName + "'");
        return fun;
    }

    public boolean hasMethod(String methodName) {
        return methods.containsKey(methodName);
    }

    @Override
    public String toString() {
        return "class " + name;
    }
}
