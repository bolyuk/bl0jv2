package bl0.bl0jv2.cli;

import bl0.bl0jv2.Bl0jv2_Utils;
import bl0.bl0jv2.Bl0jv2_jVM;
import bl0.bl0jv2.data.C;
import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import picocli.CommandLine;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "bl0jv2", description = "", version = C.VERSION+"", mixinStandardHelpOptions = true)
public class Bl0jv2_CLI implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "source")
    private Path source;

    @CommandLine.Parameters(index = "1", description = "destination", arity = "0..1")
    private Path dest;

    @CommandLine.Option(names = {"-c", "--compile"}, description = "compile")
    private boolean compile;

    @CommandLine.Option(names = {"-d", "--dump"}, description = "shows file info")
    private boolean dump;

    @CommandLine.Option(names = {"-e", "--execute"}, description = "execute")
    private boolean execute;

    @Override
    public Integer call() throws IOException {
        if (Files.notExists(source))
            throw new RuntimeException("source not exists: " + source);

        byte[] bytes;
        Timer timer = new Timer();
        Path pathToExec = source;

        if (compile) {
            if (dest == null)
                dest = source.getParent().resolve(source.getFileName() + ".bl0c");

            var lexer    = new Bl0jv2_Lexer();
            var parser   = new Bl0jv2_Parser();
            var compiler = new Bl0jv2_Compiler();

            System.out.println();
            var tokens = lexer.getTokens(Files.readString(source));
            timer.mark("lexer");
            System.out.printf("lexer done: (%d tokens)%n", tokens.size());

            var ast = parser.getAST(tokens);
            timer.mark("parser");
            System.out.println("parser done");

            bytes = compiler.compile(ast);
            timer.mark("compiler");
            System.out.printf("compiler done: (%d bytes)%n", bytes.length);

            System.out.println();
            timer.report();
            Files.write(dest, bytes);
            System.out.println();
            System.out.println("written: " + dest);

            pathToExec = dest;
        } else {
            bytes = Files.readAllBytes(source);
        }

        // writer нужен только для dump/execute — открываем здесь
        if (dump || execute) {
            var writer = new PrintWriter(System.out);
            System.out.println();

            if (dump)
                Bl0jv2_Utils.dump_file(bytes, writer);

            if (execute) {
                var vm = new Bl0jv2_jVM();
                vm.feed_compiled_file(ByteBuffer.wrap(bytes));
                vm.set_out_writer(writer);
                System.out.println();
                vm.run_instructions();
                writer.flush();
            }
        }

        System.out.println();
        System.out.println("done: " + pathToExec);
        System.out.println();
        return 0;
    }

    public static void main(String[] args) {
        int code = new CommandLine(new Bl0jv2_CLI()).execute(args);
        System.exit(code);
    }
}
