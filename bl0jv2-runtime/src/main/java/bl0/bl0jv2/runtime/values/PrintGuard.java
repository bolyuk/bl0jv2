package bl0.bl0jv2.runtime.values;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Stops toString() of a container from recursing forever through a cycle
 * (an array that contains itself, two instances pointing at each other):
 * the second visit of an object that is already being printed gets a
 * placeholder instead.
 */
final class PrintGuard {
    private PrintGuard() {}

    private static final ThreadLocal<Set<Object>> PRINTING =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    /** false if 'value' is already being printed further up this thread's stack */
    static boolean enter(Object value) {
        return PRINTING.get().add(value);
    }

    static void exit(Object value) {
        PRINTING.get().remove(value);
    }
}
