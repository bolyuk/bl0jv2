package bl0.bl0jv2.data.generation.nodes.statements;

import bl0.bl0jv2.data.generation.nodes.Node;

import java.util.List;

public class ArgumentNode extends Node {
    public final List<String> args;
    public ArgumentNode(List<String> args) {
        this.args = args;
    }
}
