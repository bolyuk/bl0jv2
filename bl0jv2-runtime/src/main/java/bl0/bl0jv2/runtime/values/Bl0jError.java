package bl0.bl0jv2.runtime.values;

import java.util.Objects;

/**
 * A caught runtime error, deliberately minimal - just the message, no
 * address/stack-trace pointer. Produced automatically by a 'catch' clause,
 * or manually via the 'err(message)' builtin for Go-style "return an
 * error" code.
 */
public final class Bl0jError {
    private final String message;

    public Bl0jError(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Bl0jError other && Objects.equals(message, other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(message);
    }

    @Override
    public String toString() {
        return message;
    }
}
