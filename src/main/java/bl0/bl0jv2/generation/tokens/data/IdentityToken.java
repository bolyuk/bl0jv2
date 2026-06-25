package bl0.bl0jv2.generation.tokens.data;

public class IdentityToken extends DataToken {
    public final String name;

    public IdentityToken(int line, int pos, String name) {
        super(line, pos);
        this.name = name;
    }
}
