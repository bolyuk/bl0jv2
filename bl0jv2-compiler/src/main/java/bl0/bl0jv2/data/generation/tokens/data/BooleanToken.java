package bl0.bl0jv2.data.generation.tokens.data;

public class BooleanToken extends DataToken {
    public final boolean value;

    public BooleanToken(int line, int pos, boolean value) {
        super(line, pos);
        this.value = value;
    }
}
