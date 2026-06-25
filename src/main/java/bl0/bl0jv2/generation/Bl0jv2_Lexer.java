package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.NativeMethods;
import bl0.bl0jv2.exceptions.Bl0j_LexerException;
import bl0.bl0jv2.generation.tokens.EOFToken;
import bl0.bl0jv2.generation.tokens.NativeCallToken;
import bl0.bl0jv2.generation.tokens.OpToken;
import bl0.bl0jv2.generation.tokens.Token;
import bl0.bl0jv2.generation.tokens.blocks.LBraceToken;
import bl0.bl0jv2.generation.tokens.blocks.RBraceToken;
import bl0.bl0jv2.generation.tokens.blocks.LParenToken;
import bl0.bl0jv2.generation.tokens.blocks.RParenToken;
import bl0.bl0jv2.generation.tokens.data.*;
import bl0.bl0jv2.generation.tokens.statements.*;

import java.util.ArrayList;
import java.util.List;

public final class Bl0jv2_Lexer {
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
                case ',':
                    tokens.add(new SeparatorToken(line, line_index));
                    break;
                case ';':
                    tokens.add(new SemicolonToken(line, line_index));
                    break;
                case '%':
                    tokens.add(new OpToken(line, line_index, Operator.REMAINDER));
                    break;
                case '=':
                    if (peekIfNext('='))
                        tokens.add(new OpToken(line, line_index, Operator.EQUALS));
                    else
                        tokens.add(new OpToken(line, line_index, Operator.ASSIGNMENT));
                    break;
                case '>':
                    tokens.add(new OpToken(line, line_index, peekIfNext('=') ? Operator.GREATER_EQUALS : Operator.GREATER));
                    break;
                case '<':
                    tokens.add(new OpToken(line, line_index, peekIfNext('=') ? Operator.LESS_EQUALS : Operator.LESS));
                    break;
                case '(':
                    tokens.add(new LParenToken(line, line_index));
                    break;
                case ')':
                    tokens.add(new RParenToken(line, line_index));
                    break;
                case '+':
                    tokens.add(new OpToken(line, line_index, peekIfNext('+') ? Operator.PLUS_PLUS : Operator.PLUS));
                    break;
                case '-':
                    tokens.add(new OpToken(line, line_index, peekIfNext('-') ? Operator.MINUS_MINUS : Operator.MINUS));
                    break;
                case '*':
                    tokens.add(new OpToken(line, line_index, peekIfNext('*') ? Operator.STAR_STAR : Operator.STAR));
                    break;
                case '/':
                    tokens.add(new OpToken(line, line_index, Operator.DIV));
                    break;
                case '!':
                    if (peekIfNext('='))
                        tokens.add(new OpToken(line, line_index, Operator.NOT_EQUALS));
                    else
                        tokens.add(new OpToken(line, line_index, Operator.NOT));
                    break;
                default:
                    if (c == '\'') {
                        String buf = "";
                        while (pos + 1 < len) {
                            if (!isNext('\'')) {
                                buf += peek();
                                line_index++;
                            } else {
                                pos++; // consume '
                                break;
                            }
                        }
                        tokens.add(new StringToken(line, line_index, buf));
                    } else if (isNumber(c)) {
                        String buf = "" + c;
                        while (pos + 1 < len) {
                            if (isNumber(lookAhead())) {
                                buf += peek();
                                line_index++;
                            } else
                                break;
                        }
                        tokens.add(new NumberToken(line, line_index, buf));
                    } else if (isIdentity(c)) {
                        String buf = "" + c;
                        while (pos + 1 < len) {
                            if (isIdentity(lookAhead())) {
                                buf += peek();
                                line_index++;
                            } else
                                break;
                        }
                        switch (buf) {
                            case "true" -> tokens.add(new BooleanToken(line, line_index, true));
                            case "false" -> tokens.add(new BooleanToken(line, line_index, false));
                            case "nil" -> tokens.add(new NilToken(line, line_index));

                            case "if" -> tokens.add(new IfToken(line, line_index));
                            case "else" -> tokens.add(new ElseToken(line, line_index));
                            case "while" -> tokens.add(new WhileToken(line, line_index));

                            case "def" -> tokens.add(new DefToken(line, line_index));
                            case "return" -> tokens.add(new ReturnToken(line, line_index));

                            case "println" -> tokens.add(new NativeCallToken(line, line_index, NativeMethods.PRINT_LN));
                            case "print" -> tokens.add(new NativeCallToken(line, line_index, NativeMethods.PRINT));
                            case "wait" -> tokens.add(new NativeCallToken(line, line_index, NativeMethods.WAIT));
                            default -> tokens.add(new IdentityToken(line, line_index, buf));
                        }
                    } else if (!Character.isWhitespace(c))
                        throw new Bl0j_LexerException(line, line_index, "unexpected character - " + c);
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
