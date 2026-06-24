package bl0.bl0jv2.generation.tokens;

public abstract class Token {
    public final int line;
    public final int line_index;

    public Token(int line, int pos) {
        this.line = line;
        this.line_index = pos;
    }
}
