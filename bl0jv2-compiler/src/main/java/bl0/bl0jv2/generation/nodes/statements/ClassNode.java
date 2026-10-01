package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

import java.util.List;

public class ClassNode extends Node {
    // declared by a shared library (see Bl0jv2_Linker): the compiler checks calls against it but
    // emits no code - the loader supplies the real thing
    public boolean external;
    public final String name;
    public final List<String> fieldNames;
    // parallel to fieldNames - null where a field has no initializer.
    // Never anything but a literal leaf node (Number/Float/String/Boolean/
    // Nil): the parser rejects any other 'data' production here.
    public final List<Node> fieldDefaultNodes;
    // subset of fieldNames that are 'const field' - compile-time-only
    // enforcement (see Bl0jv2_Compiler's compileAssign), never carried
    // into the compiled bytecode
    public final List<String> constFieldNames;
    public final List<FunNode> methods;
    public final List<FunNode> staticMethods;
    public final List<String> staticFieldNames;

    public ClassNode(String name, List<String> fieldNames, List<Node> fieldDefaultNodes, List<String> constFieldNames, List<FunNode> methods, List<FunNode> staticMethods, List<String> staticFieldNames) {
        this.name = name;
        this.fieldNames = fieldNames;
        this.fieldDefaultNodes = fieldDefaultNodes;
        this.constFieldNames = constFieldNames;
        this.methods = methods;
        this.staticMethods = staticMethods;
        this.staticFieldNames = staticFieldNames;
    }
}
