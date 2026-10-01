package bl0.bl0jv2.cli;

import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import bl0.bl0jv2.runtime.device.BlockDevice;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies host files onto a disk image (--disk-put). There is no second
 * implementation of the on-disk format here: a small bl0 program using the very
 * same stdlib/fs/fs.bl0 the guest uses is generated and run in a throwaway VM
 * attached to the image, so the image is always written the way the guest
 * reads it. A file named *.bl0 is compiled first (imports resolved, -I
 * respected) and stored as a .bl0c, ready for the shell's exec.
 */
public final class DiskImport {
    private DiskImport() {}

    /** one --disk-put argument: HOSTFILE or HOSTFILE:NAME on the disk */
    public record Spec(Path host, String name) {
        public static Spec parse(String text) {
            int colon = text.lastIndexOf(':');
            // a Windows drive letter (C:\x) is not a name separator
            if (colon > 1 || (colon == 1 && !Character.isLetter(text.charAt(0)))) {
                return new Spec(Path.of(text.substring(0, colon)), text.substring(colon + 1));
            }
            return new Spec(Path.of(text), null);
        }
    }

    // what one spec turns into: a file on the host and the name it gets on the disk
    private record Item(Path host, String name) {}

    // a directory spec copies every file in it (recursively) under the name prefix
    private static List<Item> expand(List<Spec> specs) throws IOException {
        List<Item> items = new ArrayList<>();
        for (Spec spec : specs) {
            if (Files.isDirectory(spec.host())) {
                String prefix = spec.name() != null ? spec.name() : spec.host().getFileName().toString();
                try (var walk = Files.walk(spec.host())) {
                    for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
                        String relative = spec.host().relativize(file).toString().replace('\\', '/');
                        items.add(new Item(file, prefix + "/" + (relative.endsWith(".bl0") ? relative + "c" : relative)));
                    }
                }
            } else {
                String fileName = spec.host().getFileName().toString();
                String name = spec.name() != null ? spec.name() : (fileName.endsWith(".bl0") ? fileName + "c" : fileName);
                items.add(new Item(spec.host(), name));
            }
        }
        return items;
    }

    public static void put(BlockDevice disk, List<Spec> specs, List<Path> includeDirs) throws IOException {
        put(disk, specs, includeDirs, null);
    }

    /**
     * With 'shared' the programs are built against those libraries (their code is not in them),
     * and the libraries themselves are put on the disk as lib/NAME.bl0c with lib/MANIFEST listing
     * them in load order.
     */
    public static void put(BlockDevice disk, List<Spec> specs, List<Path> includeDirs, SharedLibs shared) throws IOException {
        StringBuilder src = new StringBuilder("import 'stdlib/fs/fs.bl0'; Disk.init(8192); ")
                .append("if (!Fs.mount()) { Fs.format(); } ")
                // a file travels as hex text in string constants (a push per byte would overflow
                // the 16-bit instruction addresses for a program of any size)
                .append("def unhex(chunks) { out = []; c = 0; while (c < len(chunks)) { s = chunks[c]; i = 0; ")
                .append("while (i < len(s)) { a = int(s[i]); b = int(s[i + 1]); ")
                .append("push(out, ((a < 58 ? a - 48 : a - 87) << 4) | (b < 58 ? b - 48 : b - 87)); i += 2; } c += 1; } return out; } ");
        if (shared != null) {
            StringBuilder manifest = new StringBuilder();
            for (Path lib : shared.files()) {
                String name = SharedLibs.imageName(lib);
                appendFile(src, "lib/" + name, compileSource(Bl0jv2_Linker.read(lib), lib, includeDirs, shared));
                manifest.append("lib/").append(name).append('\n');
            }
            appendFile(src, "lib/MANIFEST", manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        for (Item item : expand(specs)) {
            byte[] data = item.host().getFileName().toString().endsWith(".bl0")
                    ? compileSource(Files.readString(item.host()), item.host(), includeDirs, shared)
                    : Files.readAllBytes(item.host());
            appendFile(src, item.name(), data);
        }

        var parser = new Bl0jv2_Parser();
        String source = src.toString();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        // the entry's own directory is irrelevant: 'stdlib/...' comes from the classpath
        byte[] program = new Bl0jv2_Compiler().compile(Bl0jv2_Linker.resolveImports(ast, Path.of("disk-put.bl0")));

        var vm = new Bl0jv2_jVM();
        StringWriter log = new StringWriter();
        vm.set_out_writer(new PrintWriter(log));
        vm.feed_compiled_file(ByteBuffer.wrap(program));
        vm.attach_disk(disk);
        try {
            vm.run_instructions();
        } catch (RuntimeException e) {
            throw new IOException("--disk-put failed: " + e.getMessage(), e);
        }
    }

    // statements that write 'data' to the file 'name' on the disk
    private static void appendFile(StringBuilder src, String name, byte[] data) {
        src.append("Fs.writeData('").append(name.replace("\\", "\\\\").replace("'", "\\'")).append("', unhex([");
        StringBuilder hex = new StringBuilder();
        boolean firstChunk = true;
        for (int i = 0; i < data.length; i++) {
            hex.append(Character.forDigit((data[i] >> 4) & 15, 16)).append(Character.forDigit(data[i] & 15, 16));
            if (hex.length() >= 8000 || i == data.length - 1) {
                if (!firstChunk) src.append(',');
                src.append('\'').append(hex).append('\'');
                firstChunk = false;
                hex.setLength(0);
            }
        }
        src.append("])); ");
    }

    private static byte[] compileSource(String source, Path file, List<Path> includeDirs, SharedLibs shared) {
        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        var linked = shared == null
                ? Bl0jv2_Linker.resolveImports(ast, file, includeDirs)
                : Bl0jv2_Linker.resolveImports(ast, file, includeDirs, shared.keys());
        return new Bl0jv2_Compiler().compile(linked);
    }
}
