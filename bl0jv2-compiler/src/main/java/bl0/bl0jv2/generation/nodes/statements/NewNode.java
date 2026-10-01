package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

import java.util.List;

public class NewNode extends Node {
    public final String className;
    public final List<Node> args;

    public NewNode(String className, List<Node> args) {
        this.className = className;
        this.args = args;
    }
}
