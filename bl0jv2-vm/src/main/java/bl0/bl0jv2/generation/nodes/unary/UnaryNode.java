package bl0.bl0jv2.generation.nodes.unary;

import bl0.bl0jv2.generation.Operator;
import bl0.bl0jv2.generation.nodes.Node;

public abstract class UnaryNode extends Node {
    public final Operator op;

    protected UnaryNode(Operator op) {
        this.op = op;
    }
}
