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

    public static void put(BlockDevice disk, List<Spec> specs, List<Path> includeDirs) throws IOException {
        StringBuilder src = new StringBuilder("import 'stdlib/fs/fs.bl0'; Disk.init(8192); ")
                .append("if (!Fs.mount()) { Fs.format(); } ");
        for (Spec spec : specs) {
            byte[] data;
            String name = spec.name();
            String fileName = spec.host().getFileName().toString();
            if (fileName.endsWith(".bl0")) {
                data = compile(spec.host(), includeDirs);
                if (name == null) name = fileName + "c";
            } else {
                data = Files.readAllBytes(spec.host());
                if (name == null) name = fileName;
            }
            src.append("d = []; ");
            for (byte b : data) src.append("push(d, ").append(b & 0xFF).append("); ");
            src.append("Fs.writeData('").append(name.replace("\\", "\\\\").replace("'", "\\'")).append("', d); ");
            src.append("free(d); ");
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

    private static byte[] compile(Path file, List<Path> includeDirs) throws IOException {
        String source = Files.readString(file);
        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        return new Bl0jv2_Compiler().compile(Bl0jv2_Linker.resolveImports(ast, file, includeDirs));
    }
}
