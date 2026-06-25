package bl0.bl0jv2.generation.tokens.data;


public class StringToken extends DataToken {
    public final String value;

    public StringToken(int line, int pos, String value) {
        super(line, pos);
        this.value = value;
    }
}
