package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

public class IndexNode extends Node {
    public final Node left;
    public final Node index;

    public IndexNode(Node left, Node index) {
        this.left = left;
        this.index = index;
    }
}
