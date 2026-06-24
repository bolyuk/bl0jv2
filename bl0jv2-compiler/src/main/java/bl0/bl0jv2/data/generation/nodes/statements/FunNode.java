package bl0.bl0jv2.data.generation.nodes.statements;

import bl0.bl0jv2.data.generation.nodes.Node;

public class FunNode extends Node {
    public final String name;
    public final ArgumentNode args;
    public final Node body;

    public FunNode(String name, ArgumentNode args, Node body) {
        this.name = name;
        this.args = args;
        this.body = body;
    }
}
