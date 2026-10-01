package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

public class ForNode extends Node {
    public final Node init;
    public final Node condition;
    public final Node update;
    public final Node body;

    public ForNode(Node init, Node condition, Node update, Node body) {
        this.init = init;
        this.condition = condition;
        this.update = update;
        this.body = body;
    }
}
