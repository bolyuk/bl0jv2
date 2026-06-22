package bl0.bl0jv2;

import bl0.bl0jv2.data.generation.nodes.Node;
import bl0.bl0jv2.data.generation.tokens.Token;
import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import org.bl0.bl0jv2.vm.Bl0jv2_jVM;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Scanner;

import static org.bl0.bl0jv2.vm.Bl0jv2_jVM.mock_header;

public class Bl0jv2_Console {

    private static final Bl0jv2_jVM vm = new Bl0jv2_jVM();

    private static final Bl0jv2_Lexer lexer = new Bl0jv2_Lexer();
    private static final Bl0jv2_Parser parser = new Bl0jv2_Parser();
    private static final Bl0jv2_Compiler compiler = new Bl0jv2_Compiler();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("> ");

        while (true) {
            String line = scanner.nextLine().trim();

            List<Token> tokens = lexer.getTokens(line);
            Node ast = parser.getAST(tokens);
            byte[] instructions = compiler.compile(ast);

            vm.feed_compiled_file(ByteBuffer.wrap(instructions));
            vm.run_instructions();

            System.out.print("> ");
        }
    }
}
