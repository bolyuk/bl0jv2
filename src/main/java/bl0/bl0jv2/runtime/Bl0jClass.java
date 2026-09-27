package bl0.bl0jv2.runtime;

import bl0.bl0jv2.data.FunDef;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.List;
import java.util.Map;

/**
 * A class's fixed shape: its declared field names (in allocation order) and
 * its methods. Shared by every instance of the class - instances only hold
 * their own field values plus a reference to this.
 */
public final class Bl0jClass {
    public final String name;
    private final List<String> fieldNames;
    private final Map<String, FunDef> methods;

    Bl0jClass(String name, List<String> fieldNames, Map<String, FunDef> methods) {
        this.name = name;
        this.fieldNames = fieldNames;
        this.methods = methods;
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
