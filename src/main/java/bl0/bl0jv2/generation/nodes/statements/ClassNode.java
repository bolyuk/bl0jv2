package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

import java.util.List;

public class ClassNode extends Node {
    public final String name;
    public final List<String> fieldNames;
    public final List<FunNode> methods;
    public final List<FunNode> staticMethods;

    public ClassNode(String name, List<String> fieldNames, List<FunNode> methods, List<FunNode> staticMethods) {
        this.name = name;
        this.fieldNames = fieldNames;
        this.methods = methods;
        this.staticMethods = staticMethods;
    }
}
