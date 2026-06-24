package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.exceptions.Bl0j_ParserException;
import bl0.bl0jv2.data.generation.tokens.*;
import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.generation.tokens.blocks.LBraceToken;
import bl0.bl0jv2.data.generation.tokens.blocks.RBraceToken;
import bl0.bl0jv2.data.generation.tokens.data.*;
import bl0.bl0jv2.data.generation.tokens.blocks.LParenToken;
import bl0.bl0jv2.data.generation.tokens.blocks.RParenToken;
import bl0.bl0jv2.data.generation.tokens.statements.*;

import java.util.ArrayList;
import java.util.List;

public class Bl0jv2_Lexer {
    private char[] data;
    private int pos;
    private int len;

    public List<Token> getTokens(String input) {
        data = input.toCharArray();
        pos = 0;
        len = data.length;
        int line_index = 0;
        int line = 0;

        List<Token> tokens = new ArrayList<>();

        for (; pos < len;) {
            char c  = data[pos];
            switch (c) {
                case '\n':
                    line++;
                    line_index = 0;
                    break;
                case '?':
                    tokens.add(new Ternary_IfToken(line, line_index));
                    break;
                case ':':
                    tokens.add(new Ternary_ElseToken(line, line_index));
                    break;
                case '{':
                    tokens.add(new LBraceToken(line, line_index));
                    break;
                case '}':
                    tokens.add(new RBraceToken(line, line_index));
                    break;
                case ';':
                    tokens.add(new SemicolonToken(line, line_index));
                    break;
                case '=':
                    if(peekIfNext('='))
                        tokens.add(new OpToken(line, line_index, Op.EQUALS));
                    else
                        tokens.add(new OpToken(line, line_index, Op.ASSIGNMENT));
                    break;
                case '>':
                    tokens.add(new OpToken(line, line_index, peekIfNext('=') ? Op.GREATER_EQUALS : Op.GREATER));
                    break;
                case '<':
                    tokens.add(new OpToken(line, line_index, peekIfNext('=') ? Op.LESS_EQUALS : Op.LESS));
                    break;
                case '(':
                    tokens.add(new LParenToken(line, line_index));
                    break;
                case ')':
                    tokens.add(new RParenToken(line, line_index));
                    break;
                case '+':
                    tokens.add(new OpToken(line, line_index, peekIfNext('+') ? Op.PLUS_PLUS : Op.PLUS));
                    break;
                case '-':
                    tokens.add(new OpToken(line, line_index, peekIfNext('-') ? Op.MINUS_MINUS : Op.MINUS));
                    break;
                case '*':
                    tokens.add(new OpToken(line, line_index, peekIfNext('*') ? Op.STAR_STAR : Op.STAR));
                    break;
                case '/':
                    tokens.add(new OpToken(line, line_index, Op.DIV));
                    break;
                case '!':
                    if(peekIfNext('='))
                        tokens.add(new OpToken(line, line_index, Op.NOT_EQUALS));
                    else
                        tokens.add(new OpToken(line, line_index, Op.NOT));
                    break;
                default:
                    if(c == '\''){
                        String buf = "";
                        while (pos+1 < len){
                            if(!isNext('\'')) {
                                buf += peek();
                                line_index++;
                            } else {
                                pos++; // consume '
                                break;
                            }
                        }
                        tokens.add(new StringToken(line, line_index, buf));
                    } else if(isNumber(c)){
                        String buf = ""+c;
                        while (pos+1 < len) {
                            if(isNumber(lookAhead())) {
                                buf += peek();
                                line_index++;
                            } else
                                break;
                        }
                        tokens.add(new NumberToken(line, line_index, buf));
                    } else if(isIdentity(c)){
                        if((c == 't' || c == 'f')
                        && !isIdentity(lookAhead())){
                            tokens.add(new BooleanToken(line, line_index, c == 't'));
                        } else {
                            String buf = "" + c;
                            while (pos + 1 < len) {
                                if (isIdentity(lookAhead())) {
                                    buf += peek();
                                    line_index++;
                                } else
                                    break;
                            }
                            if(buf.equals("nil"))
                                tokens.add(new NilToken(line, line_index));
                            else if(buf.equals("print"))
                                tokens.add(new OpToken(line, line_index, Op.PRINT));
                            else if(buf.equals("if"))
                                tokens.add(new IfToken(line, line_index));
                            else if(buf.equals("else"))
                            tokens.add(new ElseToken(line, line_index));
                            else
                                tokens.add(new IdentityToken(line, line_index, buf));
                        }
                    } else if(!Character.isWhitespace(c))
                        throw new Bl0j_ParserException(line, line_index, "unexpected character - "+c);
            }
            line_index++;
            pos++;
        }

        tokens.add(new EOFToken(line, line_index));

        return  tokens;
    }

    private char lookAhead() {
        if (pos + 1 >= len)
            return '\0';
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

    private boolean peekIfNext(char c) {
        boolean result = isNext(c);

        if(result)
            pos++;

        return result;
    }

    private boolean isNumber(char c) {
        return Character.isDigit(c);
    }

    private boolean isIdentity(char c) {
        return  Character.isAlphabetic(c) || c == '_';
    }
}
