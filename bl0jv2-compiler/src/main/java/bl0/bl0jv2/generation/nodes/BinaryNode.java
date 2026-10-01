package bl0.bl0jv2.generation.nodes;

import bl0.bl0jv2.generation.Operator;

public class BinaryNode extends Node {
    public final Node left;
    public final Node right;
    public final Operator op;
    public BinaryNode(Node left, Operator op, Node right) {
        this.left = left;
        this.op = op;
        this.right = right;
    }
}
