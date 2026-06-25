package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

import java.util.List;

public class TupleNode extends Node {
    public final List<Node> values;

    public TupleNode(List<Node> values) {
        this.values = values;
    }
}
