package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

public class TryNode extends Node {
    public final Node tryBody;
    public final String catchVarName;
    public final Node catchBody;

    public TryNode(Node tryBody, String catchVarName, Node catchBody) {
        this.tryBody = tryBody;
        this.catchVarName = catchVarName;
        this.catchBody = catchBody;
    }
}
