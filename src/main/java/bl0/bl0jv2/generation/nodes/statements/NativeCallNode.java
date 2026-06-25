package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

public class NativeCallNode extends Node {
    public final byte id;
    public final Node right;

    public NativeCallNode(byte id, Node right) {
        this.id = id;
        this.right = right;
    }
}
