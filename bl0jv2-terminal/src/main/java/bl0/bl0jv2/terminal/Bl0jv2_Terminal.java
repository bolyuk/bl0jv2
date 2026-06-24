package bl0.bl0jv2.terminal;

import bl0.bl0jv2.data.generation.nodes.Node;
import bl0.bl0jv2.data.generation.tokens.Token;
import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import org.bl0.bl0jv2.vm.Bl0jv2_jVM;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Scanner;

import static org.bl0.bl0jv2.vm.Bl0jv2_jVM.dump_file;
import static org.bl0.bl0jv2.vm.Bl0jv2_jVM.mock_header;

public class Bl0jv2_Terminal {

    private static final Bl0jv2_jVM vm = new Bl0jv2_jVM();

    private static final Bl0jv2_Lexer lexer = new Bl0jv2_Lexer();
    private static final Bl0jv2_Parser parser = new Bl0jv2_Parser();
    private static final Bl0jv2_Compiler compiler = new Bl0jv2_Compiler();

    public static void main(String[] args) throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        History history = new DefaultHistory();

        LineReader reader = LineReaderBuilder.builder()
                .history(history)
                .terminal(terminal)
                .build();

        PrintWriter writer = terminal.writer();

        vm.set_out_writer(writer);

        writer.print("> ");

        while (true) {
            try {
                String line = reader.readLine().trim();

                List<Token> tokens = lexer.getTokens(line);
                Node ast = parser.getAST(tokens);
                byte[] instructions = compiler.compile(ast);
                Bl0jv2_jVM.dump_file(instructions, writer);
                writer.println();

                vm.feed_compiled_file(ByteBuffer.wrap(instructions));
                vm.run_instructions();

                writer.println();
                writer.print("> ");
            } catch (Exception e) {
               writer.println(e.getMessage());
            }
        }
    }
}
