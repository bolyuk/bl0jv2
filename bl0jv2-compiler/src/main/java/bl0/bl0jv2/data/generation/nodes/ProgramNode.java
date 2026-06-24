package bl0.bl0jv2.data.generation.nodes;

import java.util.ArrayList;
import java.util.List;

public class ProgramNode extends Node {
    public final List<Node> nodes;

    public ProgramNode(final List<Node> nodes) {
        this.nodes = nodes;
    }
}
