package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.generation.nodes.BinaryNode;
import bl0.bl0jv2.data.generation.nodes.Node;
import bl0.bl0jv2.data.exceptions.Bl0j_ParserException;
import bl0.bl0jv2.data.generation.nodes.NumberNode;
import bl0.bl0jv2.data.generation.nodes.unary.LUnaryNode;
import bl0.bl0jv2.data.generation.tokens.NumberToken;
import bl0.bl0jv2.data.generation.tokens.OpToken;
import bl0.bl0jv2.data.generation.tokens.Token;
import bl0.bl0jv2.data.generation.tokens.paren.LParenToken;
import bl0.bl0jv2.data.generation.tokens.paren.RParenToken;

import java.util.List;

public class Bl0jv2_Parser {
    private List<Token> tokens;
    private int pos;

    // addSub = multiplyDivide (('+' | '-') multiplyDivide)*
    // multiplyDivide = unary (('*' | '/') unary)*
    // unary = '-' unary | data
    // data = NUMBER | IDENT
    public Node getAST(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
        return addSub();
    }

    private Node addSub() {
        Node left = multiplyDivide();

        while (pos < tokens.size()) {
            Token t = tokens.get(pos);
            if (t instanceof OpToken op && (op.op == Op.PLUS || op.op == Op.MINUS)) {
                pos++;
                Node right = multiplyDivide();
                left = new BinaryNode(left, op.op, right);
            } else break;
        }

        return left;
    }

    private Node multiplyDivide() {
        Node left = unary();

        while (pos < tokens.size()) {
            Token t = tokens.get(pos);
            if (t instanceof OpToken op && (op.op == Op.STAR || op.op == Op.DIV)) {
                pos++;
                Node right = unary();
                left = new BinaryNode(left, op.op, right);
            } else break;
        }

        return left;
    }

    private Node unary() {
        Token t = peek();
        if (t instanceof OpToken op &&
                op.op == Op.MINUS) {
            pos++;
            Node operand = unary();
            return new LUnaryNode(Op.MINUS, operand);
        }
        return data();
    }

    private Node data() {
        Token t = peek();
        if (t instanceof NumberToken val) {
            pos++;
            return new NumberNode(Integer.parseInt(val.value));
        }

        if(t instanceof LParenToken){
            pos++;
            var node = addSub();
            Token closing = peek();
            if (!(closing instanceof RParenToken))
                throw new Bl0j_ParserException(closing.line, closing.line_index, "expected ')'");
            pos++;
            return node;
        }

        throw new Bl0j_ParserException(t.line, t.line_index, "expected number");
    }

    private Token peek() {
        if (pos >= tokens.size())
            throw new Bl0j_ParserException(-1, -1, "unexpected end of input");
        return tokens.get(pos);
    }
}
