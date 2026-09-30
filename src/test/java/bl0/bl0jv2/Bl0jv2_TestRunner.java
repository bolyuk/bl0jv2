package bl0.bl0jv2;

import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Helper that drives the whole pipeline (lexer -> parser -> compiler -> VM)
 * for a source snippet and returns whatever was written to stdout.
 */
public final class Bl0jv2_TestRunner {
    private Bl0jv2_TestRunner() {}

    public static byte[] compile(String source) {
        var lexer = new Bl0jv2_Lexer();
        var parser = new Bl0jv2_Parser();
        var compiler = new Bl0jv2_Compiler();

        parser.setSourceCode(source);
        return compiler.compile(parser.getAST(lexer.getTokens(source)));
    }

    public static String run(String source) {
        return runInstructions(compile(source), null);
    }

    // lets a test configure the VM (set_max_heap_entries, set_max_raw_bytes,
    // set_in_reader, ...) before feed_compiled_file() runs - some of those
    // setters only take effect if called before it
    public static String run(String source, Consumer<Bl0jv2_jVM> configure) {
        return runInstructions(compile(source), configure);
    }

    /**
     * Same pipeline, but starting from a real file on disk and resolving its
     * 'import "path";' statements via Bl0jv2_Linker first - needed because
     * imports are resolved relative to the entry file's own location.
     */
    public static String runFile(Path entryFile) {
        return runFile(entryFile, null);
    }

    // same as the single-arg overload, but lets a test configure the VM
    // first (set_core_count(), in particular - needed for anything that
    // dispatch()es a second core, e.g. stdlib/net/http.bl0's own client+
    // server demo, which can't run on one core: httpServe() blocks the
    // whole way through accepting a connection)
    public static String runFile(Path entryFile, Consumer<Bl0jv2_jVM> configure) {
        try {
            String source = Files.readString(entryFile);
            var lexer = new Bl0jv2_Lexer();
            var parser = new Bl0jv2_Parser();
            var compiler = new Bl0jv2_Compiler();

            parser.setSourceCode(source);
            var ast = parser.getAST(lexer.getTokens(source));
            if (!(ast instanceof PROGRAM_N program))
                throw new IllegalStateException("parser did not produce a program");

            var linked = Bl0jv2_Linker.resolveImports(program, entryFile);
            return runInstructions(compiler.compile(linked), configure);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String runInstructions(byte[] instructions, Consumer<Bl0jv2_jVM> configure) {
        var vm = new Bl0jv2_jVM();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw);
        vm.set_out_writer(writer);
        if (configure != null) configure.accept(vm);
        vm.feed_compiled_file(ByteBuffer.wrap(instructions));

        try {
            vm.run_instructions();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        writer.flush();
        return sw.toString();
    }
}
