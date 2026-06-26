package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

import java.util.List;

public class PARAMS_N extends Node {
    public final List<String> args;
    public PARAMS_N(List<String> args) {
        this.args = args;
    }
}
