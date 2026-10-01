package bl0.bl0jv2.generation;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.generation.nodes.Node;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.generation.nodes.statements.ClassNode;
import bl0.bl0jv2.generation.nodes.statements.FunNode;
import bl0.bl0jv2.generation.nodes.statements.ImportNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves 'import "path";' statements by splicing the imported file's own
 * top-level statements in, source-AST-level, not real
 * relocatable-bytecode linking (the format
 * has no notion of that; addresses are absolute and assigned once, at
 * compile time). A path is resolved relative to the file that imports it,
 * so a chain of imports each sees paths relative to its own location.
 *
 * <p>Already-imported files are skipped silently on a repeat import
 * (diamond dependencies don't duplicate definitions), and the entry file's
 * own path seeds the visited set so an import cycle can't recurse forever.
 *
 * <p>stdlib itself does not have to sit as loose files next to whatever is
 * being compiled: it ships as a classpath resource inside bl0jv2-compiler
 * (src/main/resources/stdlib/**), the module whose code reads it (see
 * readSource()). A path
 * that doesn't exist as a real file (an on-disk stdlib checkout being
 * absent - e.g. running from a packaged jar) falls back to the classpath
 * instead of failing outright, so every existing 'import "../stdlib/...'"'
 * / absolute-path import in this project keeps working unchanged either
 * way - disk, when developing against a real checkout; classpath, when
 * running from just the built jars.
 */
public final class Bl0jv2_Linker {
    private Bl0jv2_Linker() {}

    public static PROGRAM_N resolveImports(PROGRAM_N entryProgram, Path entryPath) {
        return resolveImports(entryProgram, entryPath, List.of());
    }

    /**
     * An import that is not found next to the file that names it is looked up in
     * each of 'searchPaths' in turn (the CLI's -I directories) before the stdlib
     * classpath fallback applies.
     */
    public static PROGRAM_N resolveImports(PROGRAM_N entryProgram, Path entryPath, List<Path> searchPaths) {
        return resolveImports(entryProgram, entryPath, searchPaths, Set.of());
    }

    /**
     * 'shared' names the files that live in shared libraries (see {@link #libraryKey}): an import
     * of one of them does not splice its code in. The program only gets to know what the file
     * declares - its functions and classes, with their parameters, fields and methods, so calls
     * are checked as strictly as for inlined code - and refers to them by name; the loader links
     * them to the library's own copy. A shared file may itself import only shared files (it is
     * a library: whatever it needs is a library too).
     */
    public static PROGRAM_N resolveImports(PROGRAM_N entryProgram, Path entryPath, List<Path> searchPaths, Set<String> shared) {
        return resolveImports(entryProgram, entryPath, searchPaths, shared, true);
    }

    /**
     * 'shake' removes the imported functions and classes nothing uses (see {@link Bl0jv2_Shaker}):
     * the entry file's own definitions are always kept, so a library built as an entry file exports
     * all it declares, and an imported file that has {@code @library} in a comment among its first
     * lines is never thinned.
     */
    public static PROGRAM_N resolveImports(PROGRAM_N entryProgram, Path entryPath, List<Path> searchPaths, Set<String> shared,
                                           boolean shake) {
        Set<Path> visited = new HashSet<>();
        visited.add(entryPath.toAbsolutePath().normalize());

        List<Node> resolved = new ArrayList<>();
        Set<Node> candidates = Bl0jv2_Shaker.identitySet();
        resolveInto(entryProgram, entryPath.toAbsolutePath().getParent(), searchPaths, shared, false, false, visited, resolved, candidates);
        PROGRAM_N linked = new PROGRAM_N(resolved);
        return shake ? Bl0jv2_Shaker.shake(linked, candidates) : linked;
    }

    // a file declares that it is a library - its definitions are all kept - with a comment among its
    // first lines: // @library
    private static boolean isMarkedLibrary(String source) {
        int checked = 0;
        for (String line : source.split("\n", 12)) {
            if (checked++ >= 10) break;
            String t = line.strip();
            if (t.startsWith("//") && t.contains("@library"))
                return true;
        }
        return false;
    }

    /**
     * How a file is named in a set of shared libraries: its path from a "stdlib" directory
     * onward when it has one on the way (stdlib is found on disk or in the jar alike), else
     * its absolute normalized path.
     */
    public static String libraryKey(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        String stdlib = classpathResourceNameFor(normalized);
        return stdlib != null ? stdlib : normalized.toString().replace('\\', '/');
    }

    private static void resolveInto(PROGRAM_N program, Path baseDir, List<Path> searchPaths, Set<String> shared,
                                    boolean declaringOnly, boolean thinnable, Set<Path> visited, List<Node> out, Set<Node> candidates) {
        for (Node node : program.nodes) {
            if (!(node instanceof ImportNode importNode)) {
                if (!declaringOnly) {
                    out.add(node);
                    if (thinnable && (node instanceof FunNode || node instanceof ClassNode))
                        candidates.add(node);
                } else if (node instanceof FunNode f) {
                    f.external = true;
                    out.add(f);
                } else if (node instanceof ClassNode c) {
                    c.external = true;
                    out.add(c);
                }   // anything else (top-level statements) belongs to the library's own initialisation
                continue;
            }

            Path resolvedPath = baseDir.resolve(importNode.path).normalize();
            // a file next to the importer wins, then the stdlib that ships with the compiler, and only
            // then the -I directories: an import of 'net.bl0' from inside stdlib/net must not be taken
            // by some unrelated net.bl0 that happens to sit in a search directory
            if (!Files.exists(resolvedPath) && !isBundledStdlib(resolvedPath)) {
                for (Path dir : searchPaths) {
                    Path candidate = dir.toAbsolutePath().resolve(importNode.path).normalize();
                    if (Files.exists(candidate)) {
                        resolvedPath = candidate;
                        break;
                    }
                }
            }
            boolean isShared = shared.contains(libraryKey(resolvedPath));
            if (declaringOnly && !isShared)
                throw new Bl0j_CompilerException("a shared library cannot import '" + importNode.path + "': it is not a shared library itself");
            if (!visited.add(resolvedPath))
                continue; // already imported (directly or via another import) - skip quietly

            String source = readSource(resolvedPath, importNode.path);

            var lexer = new Bl0jv2_Lexer();
            var parser = new Bl0jv2_Parser();
            parser.setSourceCode(source);
            Node importedAst = parser.getAST(lexer.getTokens(source));
            if (!(importedAst instanceof PROGRAM_N importedProgram))
                throw new Bl0j_CompilerException("imported file did not parse to a program: " + importNode.path);

            resolveInto(importedProgram, resolvedPath.getParent(), searchPaths, shared, isShared, !isMarkedLibrary(source), visited, out, candidates);
        }
    }

    private static boolean isBundledStdlib(Path resolvedPath) {
        String resource = classpathResourceNameFor(resolvedPath);
        return resource != null && Bl0jv2_Linker.class.getResource("/" + resource) != null;
    }

    /** the source of a file, from disk or - for stdlib files - from the classpath, as an import would read it */
    public static String read(Path file) {
        return readSource(file.toAbsolutePath().normalize(), file.toString());
    }

    // real file on disk first (a developer's own checkout, where stdlib
    // sits wherever bl0jv2-compiler/src/main/resources/stdlib/ puts it,
    // relative to whatever the importer's own path chain resolved to) -
    // falling back to a classpath resource only when that fails, so this
    // never pays classloader lookup cost for the common case. The fallback is
    // a plain getResourceAsStream() call against this module's own classpath
    private static String readSource(Path resolvedPath, String importPath) {
        if (Files.exists(resolvedPath)) {
            try {
                return Files.readString(resolvedPath);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot import '" + importPath + "': " + e.getMessage(), e);
            }
        }

        String resourceName = classpathResourceNameFor(resolvedPath);
        if (resourceName != null) {
            try (InputStream in = Bl0jv2_Linker.class.getResourceAsStream("/" + resourceName)) {
                if (in != null)
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // falls through to the "cannot import" exception below
            }
        }

        throw new UncheckedIOException("cannot import '" + importPath + "': " +
                "no such file, and no classpath resource '" + (resourceName == null ? "stdlib/..." : resourceName) + "'",
                new IOException(resolvedPath.toString()));
    }

    // a resolved, normalized path that passes through a "stdlib" directory
    // (however many '../' or how absolute the original import path was)
    // maps to that same path FROM "stdlib" onward, '/'-separated - the
    // classpath resource name bl0jv2-runtime's own src/main/resources/
    // stdlib/** packages it under. Returns null for an import that was
    // never going to be stdlib in the first place (nothing to fall back to).
    private static String classpathResourceNameFor(Path resolvedPath) {
        Path normalized = resolvedPath.normalize();
        int count = normalized.getNameCount();
        for (int i = 0; i < count; i++) {
            if (normalized.getName(i).toString().equals("stdlib")) {
                StringBuilder sb = new StringBuilder("stdlib");
                for (int j = i + 1; j < count; j++)
                    sb.append('/').append(normalized.getName(j));
                return sb.toString();
            }
        }
        return null;
    }
}
