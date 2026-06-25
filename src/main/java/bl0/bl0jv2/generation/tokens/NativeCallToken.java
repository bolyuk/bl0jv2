package bl0.bl0jv2.generation.tokens;

public class NativeCallToken extends Token {
    public final byte id;
    public NativeCallToken(int line, int pos, byte id) {
        super(line, pos);
        this.id = id;
    }
}
