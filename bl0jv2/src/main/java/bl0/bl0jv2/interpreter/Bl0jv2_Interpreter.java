package bl0.bl0jv2.interpreter;

import bl0.bl0jv2.interfaces.Bl0j_Executable;
import bl0.bl0jv2.interfaces.Bl0j_Object;

public class Bl0jv2_Interpreter {
    public static Bl0j_Object parse_code(String code) {
        var result = new Bl0jv2_SemanticTree();

        String[] lines = code.split("\n");

        int lineNumber = 0;

        for (String line : lines) {
            line = line.trim();
            lineNumber++;

            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }


        }

        return result;
    }
}
