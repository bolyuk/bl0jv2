package bl0.bl0jv2;

import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

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
        byte[] instructions = compile(source);

        var vm = new Bl0jv2_jVM();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw);
        vm.set_out_writer(writer);
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
