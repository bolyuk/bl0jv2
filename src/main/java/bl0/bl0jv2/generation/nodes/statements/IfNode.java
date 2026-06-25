package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

public class IfNode extends Node {
    public final Node condition;
    public final Node body;
    public final Node elseBody;

    public IfNode(Node condition, Node body, Node elseBody) {
        this.condition = condition;
        this.body = body;
        this.elseBody = elseBody;
    }
}
