package bl0.bl0jv2.data.generation.nodes.unary;

import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.generation.nodes.Node;

public abstract class UnaryNode extends Node {
    public final Op op;

    protected UnaryNode(Op op) {
        this.op = op;
    }
}
