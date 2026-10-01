package bl0.bl0jv2.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interns member names (fields, methods) to small dense ints for the whole
 * VM, so a class can find a field or method by an int key instead of
 * comparing strings on every access. Shared by every loaded program
 * (exec() appends to the same VM), so the same name always gets the same id
 * no matter which program's constant pool it came from.
 */
final class SymbolTable {
    private final Map<String, Integer> ids = new HashMap<>();
    private final List<String> names = new ArrayList<>();

    synchronized int intern(String name) {
        Integer id = ids.get(name);
        if (id != null)
            return id;
        int next = names.size();
        names.add(name);
        ids.put(name, next);
        return next;
    }

    synchronized String name(int id) {
        return names.get(id);
    }

    synchronized void clear() {
        ids.clear();
        names.clear();
    }
}
