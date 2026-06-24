package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

import java.util.ArrayList;
import java.util.List;

public class FunCall extends Node {
    public final Node left;
    public final List<Node> args;
    public FunCall(Node left, List<Node> args) {
        this.left = left;
        this.args = args;
    }
}
