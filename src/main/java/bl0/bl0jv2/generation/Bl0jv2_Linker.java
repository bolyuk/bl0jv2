package bl0.bl0jv2.generation;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.generation.nodes.Node;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.generation.nodes.statements.ImportNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves 'import "path";' statements by splicing the imported file's own
 * top-level statements in, source-AST-level, the same way Bl0jv2_Prelude's
 * content is merged in - not real relocatable-bytecode linking (the format
 * has no notion of that; addresses are absolute and assigned once, at
 * compile time). A path is resolved relative to the file that imports it,
 * so a chain of imports each sees paths relative to its own location.
 *
 * <p>Already-imported files are skipped silently on a repeat import
 * (diamond dependencies don't duplicate definitions), and the entry file's
 * own path seeds the visited set so an import cycle can't recurse forever.
 */
public final class Bl0jv2_Linker {
    private Bl0jv2_Linker() {}

    public static PROGRAM_N resolveImports(PROGRAM_N entryProgram, Path entryPath) {
        Set<Path> visited = new HashSet<>();
        visited.add(entryPath.toAbsolutePath().normalize());

        List<Node> resolved = new ArrayList<>();
        resolveInto(entryProgram, entryPath.toAbsolutePath().getParent(), visited, resolved);
        return new PROGRAM_N(resolved);
    }

    private static void resolveInto(PROGRAM_N program, Path baseDir, Set<Path> visited, List<Node> out) {
        for (Node node : program.nodes) {
            if (!(node instanceof ImportNode importNode)) {
                out.add(node);
                continue;
            }

            Path resolvedPath = baseDir.resolve(importNode.path).normalize();
            if (!visited.add(resolvedPath))
                continue; // already imported (directly or via another import) - skip quietly

            String source;
            try {
                source = Files.readString(resolvedPath);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot import '" + importNode.path + "': " + e.getMessage(), e);
            }

            var lexer = new Bl0jv2_Lexer();
            var parser = new Bl0jv2_Parser();
            parser.setSourceCode(source);
            Node importedAst = parser.getAST(lexer.getTokens(source));
            if (!(importedAst instanceof PROGRAM_N importedProgram))
                throw new Bl0j_CompilerException("imported file did not parse to a program: " + importNode.path);

            resolveInto(importedProgram, resolvedPath.getParent(), visited, out);
        }
    }
}
