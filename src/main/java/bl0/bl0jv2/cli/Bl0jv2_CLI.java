package bl0.bl0jv2.cli;

import bl0.bl0jv2.Bl0jv2_Utils;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import bl0.bl0jv2.data.C;
import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class Bl0jv2_CLI {

    private Path source;
    private Path dest;
    private boolean compile;
    private boolean dump;
    private boolean execute;
    private boolean help;
    private boolean version;
    private boolean terminal;

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-t", "-terminal" -> terminal = true;
                case "-c", "--compile" -> compile = true;
                case "-d", "--dump"    -> dump    = true;
                case "-e", "--execute" -> execute = true;
                case "-h", "--help"    -> help    = true;
                case "-V", "--version" -> version = true;
                default -> {
                    if (args[i].startsWith("-")) {
                        System.err.println("Unknown option: " + args[i]);
                        printHelp();
                        System.exit(1);
                    }
                    if (source == null)       source = Path.of(args[i]);
                    else if (dest == null)    dest   = Path.of(args[i]);
                    else {
                        System.err.println("Unexpected argument: " + args[i]);
                        printHelp();
                        System.exit(1);
                    }
                }
            }
        }
    }

    private void printHelp() {
        System.out.println("Usage: bl0jv2 [-cdehV] <source> [<dest>]");
        System.out.println();
        System.out.println("Parameters:");
        System.out.println("  <source>       source file");
        System.out.println("  [<dest>]       destination file (optional)");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -c, --compile  compile");
        System.out.println("  -d, --dump     shows file info");
        System.out.println("  -e, --execute  execute");
        System.out.println("  -h, --help     show this help message and exit");
        System.out.println("  -V, --version  print version information and exit");
    }

    private void printVersion() {
        System.out.println("bl0jv2 " + C.VERSION);
    }

    private void runTerminal() {
        var lexer = new Bl0jv2_Lexer();
        var parser = new Bl0jv2_Parser();
        var compiler = new Bl0jv2_Compiler();
        var vm = new Bl0jv2_jVM();

        var writer = new PrintWriter(System.out);
        vm.set_out_writer(writer);
        writer.print("!exit - to exit");
        writer.print("> ");

        var scanner = new Scanner(System.in);

        writer.flush();
        while (true) {
            try {
                String line = scanner.nextLine().trim();
                if(line.equals("!exit")) break;

                byte[] instructions = compiler.compile(parser.getAST(lexer.getTokens(line)));

                Bl0jv2_Utils.dump_file(instructions, writer);

                writer.println();
                vm.feed_compiled_file(ByteBuffer.wrap(instructions));
                vm.run_instructions();
            } catch (Exception e) {
                writer.println(e.getMessage());
                e.printStackTrace();
            } finally {
                writer.println();
                writer.print("> ");
                writer.flush();
            }
        }
    }

    private int run() throws IOException {

        if(terminal) {
            runTerminal();
            return 0;
        }

        if (help) {
            printHelp();
            return 0;
        }

        if (version) {
            printVersion();
            return 0;
        }

        if (source == null) {
            System.err.println("Missing required parameter: <source>");
            printHelp();
            return 1;
        }

        if (Files.notExists(source)) {
            System.err.println("source not exists: " + source);
            return 1;
        }

        byte[] bytes;
        Timer timer = new Timer();
        Path pathToExec = source;

        if (compile) {
            if (dest == null)
                dest = source.getParent().resolve(source.getFileName() + ".bl0c");

            var lexer = new Bl0jv2_Lexer();
            var parser = new Bl0jv2_Parser();
            var compiler = new Bl0jv2_Compiler();

            System.out.println();
            String data = Files.readString(source);
            var tokens = lexer.getTokens(data);
            timer.mark("lexer");
            System.out.printf("lexer done: (%d tokens)%n", tokens.size());

            parser.setSourceCode(data);
            var ast = parser.getAST(tokens);
            timer.mark("parser");
            System.out.println("parser done");

            if (!(ast instanceof PROGRAM_N program))
                throw new IllegalStateException("parser did not produce a program");
            var linked = Bl0jv2_Linker.resolveImports(program, source);

            bytes = compiler.compile(linked);
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
                // flush in finally: a crash mid-program must not discard
                // whatever it already printed before the exception
                try {
                    vm.run_instructions();
                } finally {
                    writer.flush();
                }
            }
        }

        System.out.println();
        System.out.println("done: " + pathToExec);
        System.out.println();
        return 0;
    }

    public static void main(String[] args) throws IOException {
        var cli = new Bl0jv2_CLI();
        cli.parseArgs(args);
        System.exit(cli.run());
    }
}