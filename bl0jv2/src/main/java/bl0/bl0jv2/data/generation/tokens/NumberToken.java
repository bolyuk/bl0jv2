package bl0.bl0jv2.data.generation.tokens;

public class NumberToken extends DataToken {
    public final String value;

    public NumberToken(int line, int pos, String value) {
        super(line, pos);
        this.value = value;
    }
}
