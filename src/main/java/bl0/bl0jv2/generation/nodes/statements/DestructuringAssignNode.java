package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

import java.util.List;

// a, b = <expr>  -  targets are always plain identifiers for now (no
// arr[i], b = ... support yet)
public class DestructuringAssignNode extends Node {
    public final List<Node> targets;
    public final Node right;

    public DestructuringAssignNode(List<Node> targets, Node right) {
        this.targets = targets;
        this.right = right;
    }
}
