package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.generation.nodes.BinaryNode;
import bl0.bl0jv2.data.generation.nodes.Node;
import bl0.bl0jv2.data.exceptions.Bl0j_ParserException;
import bl0.bl0jv2.data.generation.nodes.NumberNode;
import bl0.bl0jv2.data.generation.tokens.NumberToken;
import bl0.bl0jv2.data.generation.tokens.OpToken;
import bl0.bl0jv2.data.generation.tokens.DataToken;
import bl0.bl0jv2.data.generation.tokens.Token;

import java.util.List;

public class Bl0jv2_Parser {
    private List<Token> tokens;
    private int pos;

    public Node getAST(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
        return expr();
    }

    // expr = term (('+' | '-') term)*
    private Node expr() {
        Node left = term();

        while (pos < tokens.size()) {
            Token t = tokens.get(pos);
            if (t instanceof OpToken op && (op.op == Op.ADD || op.op == Op.SUB)) {
                pos++;
                Node right = term();
                left = new BinaryNode(left, op.op, right);
            } else break;
        }

        return left;
    }

    // term = factor (('*' | '/') factor)*
    private Node term() {
        Node left = factor();

        while (pos < tokens.size()) {
            Token t = tokens.get(pos);
            if (t instanceof OpToken op && (op.op == Op.MUL || op.op == Op.DIV)) {
                pos++;
                Node right = factor();
                left = new BinaryNode(left, op.op, right);
            } else break;
        }

        return left;
    }

    // factor = NUMBER
    private Node factor() {
        Token t = tokens.get(pos);
        if (t instanceof NumberToken val) {
            pos++;
            return new NumberNode(Integer.parseInt(val.value));
        }
        throw new Bl0j_ParserException(t.line, t.line_index, "expected number");
    }
}
