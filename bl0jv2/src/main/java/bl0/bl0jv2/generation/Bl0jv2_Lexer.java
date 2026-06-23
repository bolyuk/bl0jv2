package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.generation.tokens.Token;
import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.generation.tokens.OpToken;
import bl0.bl0jv2.data.generation.tokens.NumberToken;
import bl0.bl0jv2.data.generation.tokens.paren.LParenToken;
import bl0.bl0jv2.data.generation.tokens.paren.RParenToken;

import java.util.ArrayList;
import java.util.List;

public class Bl0jv2_Lexer {
    private char[] data;
    private int pos;
    private int len;

    private int line = 0;
    private int line_index = 0;

    public List<Token> getTokens(String input) {
        data = input.toCharArray();
        pos = 0;
        len = data.length;

        List<Token> tokens = new ArrayList<>();

        for (; pos < len;) {
            char c  = data[pos];
            switch (c) {
                case '\n':
                    line++;
                    line_index = 0;
                    break;
                case '(':
                    tokens.add(new LParenToken(line, line_index));
                    break;
                case ')':
                    tokens.add(new RParenToken(line, line_index));
                    break;
                case '+':
                    tokens.add(new OpToken(line, line_index, Op.PLUS));
                    break;
                case '-':
                    tokens.add(new OpToken(line, line_index, Op.MINUS));
                    break;
                case '*':
                    tokens.add(new OpToken(line, line_index, Op.STAR));
                    break;
                case '/':
                    tokens.add(new OpToken(line, line_index, Op.DIV));
                    break;
                default:
                    if(isNumber(c)){
                        String buf = ""+c;
                        while (pos+1 < len) {
                            if(isNumber(lookAhead())) {
                                buf += peek();
                                line_index++;
                            } else
                                break;
                        }
                        tokens.add(new NumberToken(line, line_index, buf));
                    }
            }
            line_index++;
            pos++;
        }

        return  tokens;
    }

    private char lookAhead() {
        return data[pos+1];
    }

    private char peek() {
        return data[++pos];
    }

    private boolean isNext(char c) {
        if (pos + 1 >= len)
            return false;
        return data[pos + 1] == c;
    }

    private boolean isNumber(char c) {
        return  Character.isDigit(c);
    }
}
