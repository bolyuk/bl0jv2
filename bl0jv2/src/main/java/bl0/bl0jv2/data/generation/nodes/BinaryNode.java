package bl0.bl0jv2.data.generation.nodes;

import bl0.bl0jv2.data.Op;

public class BinaryNode extends Node {
    public final Node left;
    public final Node right;
    public final Op op;
    public BinaryNode(Node left, Op op, Node right) {
        this.left = left;
        this.op = op;
        this.right = right;
    }
}
