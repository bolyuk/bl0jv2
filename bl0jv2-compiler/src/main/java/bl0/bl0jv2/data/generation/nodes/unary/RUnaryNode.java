package bl0.bl0jv2.data.generation.nodes.unary;

import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.generation.nodes.Node;

public class RUnaryNode extends UnaryNode {
    public final Node right;

    public RUnaryNode(Op op, Node right) {
        super(op);
        this.right = right;
    }
}
