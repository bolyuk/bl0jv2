package bl0.bl0jv2.data.generation.nodes.statements;

import bl0.bl0jv2.data.generation.nodes.Node;

public class WhileNode extends Node {
    public final Node condition;
    public final Node body;

    public WhileNode(Node condition, Node body) {
        this.condition = condition;
        this.body = body;
    }
}
