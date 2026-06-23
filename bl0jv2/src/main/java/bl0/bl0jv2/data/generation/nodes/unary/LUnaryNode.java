package bl0.bl0jv2.data.generation.nodes.unary;

import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.generation.nodes.Node;

public class LUnaryNode extends UnaryNode {
    public final Node left;

    public LUnaryNode(Op op, Node left) {
        super(op);
        this.left = left;
    }
}
