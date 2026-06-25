package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

public class ReturnNode extends Node {
    public final Node right;
    public ReturnNode(Node right) {
        this.right = right;
    }
}
