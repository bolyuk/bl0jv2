package bl0.bl0jv2.generation.nodes.unary;

import bl0.bl0jv2.generation.Operator;
import bl0.bl0jv2.generation.nodes.Node;

public class LUnaryNode extends UnaryNode {
    public final Node left;

    public LUnaryNode(Operator op, Node left) {
        super(op);
        this.left = left;
    }
}
