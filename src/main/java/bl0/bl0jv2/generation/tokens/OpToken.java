package bl0.bl0jv2.generation.tokens;

import bl0.bl0jv2.generation.Operator;

public class OpToken extends Token {
    public final Operator op;

    public OpToken(int line, int pos, Operator op) {
        super(line, pos);
        this.op = op;
    }

    @Override
    public String toString() {
        return op.toString();
    }
}
