package bl0.bl0jv2.generation.nodes.statements;

import bl0.bl0jv2.generation.nodes.Node;

// resolved before compilation (see Bl0jv2_Linker), never reaches the
// compiler itself - a lingering ImportNode there is a bug in the linker,
// not something compileInner knows how to handle
public class ImportNode extends Node {
    public final String path;

    public ImportNode(String path) {
        this.path = path;
    }
}
