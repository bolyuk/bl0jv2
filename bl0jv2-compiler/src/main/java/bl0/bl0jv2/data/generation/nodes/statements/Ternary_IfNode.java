package bl0.bl0jv2.data.generation.nodes.statements;

import bl0.bl0jv2.data.generation.nodes.Node;

public class Ternary_IfNode extends Node {
    public final Node condition;
    public final Node body;
    public final Node elseBody;

    public Ternary_IfNode(Node condition, Node body, Node elseBody) {
        this.condition = condition;
        this.body = body;
        this.elseBody = elseBody;
    }
}
