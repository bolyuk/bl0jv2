package bl0.bl0jv2.generation.nodes.unary;

import bl0.bl0jv2.generation.Operator;
import bl0.bl0jv2.generation.nodes.Node;

public class RUnaryNode extends UnaryNode {
    public final Node right;

    public RUnaryNode(Operator op, Node right) {
        super(op);
        this.right = right;
    }
}
