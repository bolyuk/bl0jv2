package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

// obj.field - also doubles as the callee shape for obj.method(args): the
// compiler special-cases a FunCall whose left is a FieldAccessNode into a
// method call rather than a plain field read
public class FieldAccessNode extends Node {
    public final Node target;
    public final String fieldName;

    public FieldAccessNode(Node target, String fieldName) {
        this.target = target;
        this.fieldName = fieldName;
    }
}
