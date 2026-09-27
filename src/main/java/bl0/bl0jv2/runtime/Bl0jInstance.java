package bl0.bl0jv2.runtime;

import java.util.Arrays;

/**
 * A class instance. Field storage is a flat long[] of NaN-boxed values,
 * same representation as everything else here - name-to-index resolution
 * goes through the shared Bl0jClass rather than being baked into the
 * instance, so all instances of the same class share one lookup.
 */
public final class Bl0jInstance {
    public final Bl0jClass cls;
    private final long[] fields;
    private final Bl0jv2_jVM owner;

    Bl0jInstance(Bl0jClass cls, Bl0jv2_jVM owner) {
        this.cls = cls;
        this.fields = new long[cls.fieldCount()];
        Arrays.fill(fields, NanBox.NIL);
        this.owner = owner;
    }

    public long getFieldRaw(String name) {
        return fields[cls.fieldIndex(name)];
    }

    public void setFieldRaw(String name, long value) {
        fields[cls.fieldIndex(name)] = value;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(cls.name).append("{");
        var names = cls.fieldNames();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i)).append(": ").append(owner.unbox(fields[i]));
        }
        return sb.append("}").toString();
    }
}
