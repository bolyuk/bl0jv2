package bl0.bl0jv2.runtime.values;

import bl0.bl0jv2.data.FunDef;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.util.Arrays;

/**
 * A class instance. Field storage is a flat long[] of NaN-boxed values,
 * same representation as everything else here - name-to-slot resolution
 * goes through the shared Bl0jClass (by interned symbol id, see its doc)
 * rather than being baked into the instance, so all instances of the same
 * class share one lookup table.
 *
 * <p>Not safe for concurrent mutation: {@link #setFieldRaw} is a plain
 * array write, no locking. An instance shared across cores (e.g. handed to
 * a dispatched task) needs its own {@code Mutex} around concurrent field
 * writes, same as {@link Bl0jArray}/{@link Bl0jClass}'s static fields.
 */
public final class Bl0jInstance {
    public final Bl0jClass cls;
    private final long[] fields;
    // public: Bl0jv2_jVM.valuesEqual needs it to box 'this' before calling a
    // user-defined equals()
    public final Bl0jv2_jVM owner;

    public Bl0jInstance(Bl0jClass cls, Bl0jv2_jVM owner) {
        this.cls = cls;
        // each field starts at its class's literal default (nil where none
        // was declared) - init() is free to overwrite any of them
        this.fields = Arrays.copyOf(cls.fieldDefaults(), cls.fieldCount());
        this.owner = owner;
    }

    public long getFieldRaw(int slot) {
        return fields[slot];
    }

    public void setFieldRaw(int slot, long value) {
        fields[slot] = value;
    }

    @Override
    public String toString() {
        // a class that declares its own toString() gets to decide its own
        // display form instead of the default field dump
        FunDef toStringMethod = cls.toStringMethod();
        if (toStringMethod != null)
            return String.valueOf(owner.invoke(toStringMethod, owner.box(this)));

        if (!PrintGuard.enter(this))
            return cls.name + "{...}";
        try {
            StringBuilder sb = new StringBuilder(cls.name).append("{");
            var names = cls.fieldNames();
            for (int i = 0; i < names.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(names[i]).append(": ").append(owner.unbox(fields[i]));
            }
            return sb.append("}").toString();
        } finally {
            PrintGuard.exit(this);
        }
    }
}
