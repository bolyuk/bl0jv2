package bl0.bl0jv2.generation;

/**
 * Standard-library functions written in bl0jv2 itself rather than as
 * compiler intrinsics. Compiled once and merged in front of every user
 * program (see Bl0jv2_Compiler.compile) - a poor man's stdlib until real
 * multi-file linking exists.
 *
 * <p>Only functions that are genuinely expressible with existing primitives
 * (len, push, indexing, ...) belong here. Anything the language itself has
 * no other way to compute (len, indexing, arithmetic, ...) has to stay a
 * real opcode - this file is for convenience wrappers around those, not a
 * place to fake missing primitives.
 */
public final class Bl0jv2_Prelude {
    private Bl0jv2_Prelude() {}

    public static final String SOURCE = """
            def toArr(s) {
                result = [];
                i = 0;
                while (i < len(s)) {
                    push(result, s[i]);
                    i = i + 1;
                }
                return result;
            }
            """;
}
