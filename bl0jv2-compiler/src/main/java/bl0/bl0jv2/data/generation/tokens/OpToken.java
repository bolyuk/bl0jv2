package bl0.bl0jv2.data.generation.tokens;

import bl0.bl0jv2.data.Op;

public class OpToken extends Token {
    public final Op op;

    public OpToken(int line, int pos, Op op) {
        super(line, pos);
        this.op = op;
    }
}
