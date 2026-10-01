package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

import java.util.List;

public class ArrayLiteralNode extends Node {
    public final List<Node> elements;

    public ArrayLiteralNode(List<Node> elements) {
        this.elements = elements;
    }
}
