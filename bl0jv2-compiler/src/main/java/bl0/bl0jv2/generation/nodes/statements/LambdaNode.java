package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

// (params) -> expr | (params) -> { block }. An expression body is wrapped
// in an implicit ReturnNode by the parser, so 'body' here is always
// statement-shaped, same as a FunNode's.
public class LambdaNode extends Node {
    public final PARAMS_N params;
    public final Node body;

    public LambdaNode(PARAMS_N params, Node body) {
        this.params = params;
        this.body = body;
    }
}
