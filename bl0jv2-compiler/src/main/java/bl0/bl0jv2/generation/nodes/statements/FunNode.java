package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

public class FunNode extends Node {
    public final String name;
    public final PARAMS_N args;
    public final Node body;

    public FunNode(String name, PARAMS_N args, Node body) {
        this.name = name;
        this.args = args;
        this.body = body;
    }
}
