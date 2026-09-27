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
import bl0.bl0jv2.generation.tokens.blocks.LBracketToken;
import bl0.bl0jv2.generation.tokens.blocks.RBracketToken;
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
                    if (peekIfNext('>'))
                        tokens.add(new OpToken(line, line_index, Operator.SHIFT_RIGHT));
                    else
                        tokens.add(new OpToken(line, line_index, peekIfNext('=') ? Operator.GREATER_EQUALS : Operator.GREATER));
                    break;
                case '<':
                    if (peekIfNext('<'))
                        tokens.add(new OpToken(line, line_index, Operator.SHIFT_LEFT));
                    else
                        tokens.add(new OpToken(line, line_index, peekIfNext('=') ? Operator.LESS_EQUALS : Operator.LESS));
                    break;
                case '&':
                    tokens.add(new OpToken(line, line_index, peekIfNext('&') ? Operator.AND : Operator.BIT_AND));
                    break;
                case '|':
                    tokens.add(new OpToken(line, line_index, peekIfNext('|') ? Operator.OR : Operator.BIT_OR));
                    break;
                case '^':
                    tokens.add(new OpToken(line, line_index, Operator.BIT_XOR));
                    break;
                case '~':
                    tokens.add(new OpToken(line, line_index, Operator.BIT_NOT));
                    break;
                case '(':
                    tokens.add(new LParenToken(line, line_index));
                    break;
                case ')':
                    tokens.add(new RParenToken(line, line_index));
                    break;
                case '[':
                    tokens.add(new LBracketToken(line, line_index));
                    break;
                case ']':
                    tokens.add(new RBracketToken(line, line_index));
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
                    if (peekIfNext('/')) {
                        // line comment: skip everything up to (not incl.) the newline
                        while (pos + 1 < len && lookAhead() != '\n')
                            pos++;
                    } else
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
                        int start_index = line_index;
                        StringBuilder buf = new StringBuilder();
                        boolean closed = false;
                        while (pos + 1 < len) {
                            if (isNext('\'')) {
                                pos++; // consume '
                                closed = true;
                                break;
                            }
                            char ch = peek();
                            line_index++;
                            if (ch == '\\' && pos + 1 < len) {
                                char escaped = peek();
                                line_index++;
                                buf.append(unescape(escaped, line, line_index));
                            } else
                                buf.append(ch);
                        }
                        if (!closed)
                            gen_exception(line, start_index, "unterminated string literal");
                        tokens.add(new StringToken(line, start_index, buf.toString()));
                    } else if (isNumber(c)) {
                        int start_index = line_index;
                        String buf = "" + c;
                        boolean isFloat = false;
                        while (pos + 1 < len) {
                            if (isNumber(lookAhead())) {
                                buf += peek();
                                line_index++;
                            } else if (!isFloat && lookAhead() == '.' && isNumber(lookAhead2())) {
                                // only treat '.' as a decimal point when a digit
                                // follows it, so a trailing '.' is left alone
                                isFloat = true;
                                buf += peek();
                                line_index++;
                            } else
                                break;
                        }
                        tokens.add(new NumberToken(line, start_index, buf));
                    } else if (isIdentity(c)) {
                        int start_index = line_index;
                        String buf = "" + c;
                        while (pos + 1 < len) {
                            if (isIdentityContinuation(lookAhead())) {
                                buf += peek();
                                line_index++;
                            } else
                                break;
                        }
                        switch (buf) {
                            case "true" -> tokens.add(new BooleanToken(line, start_index, true));
                            case "false" -> tokens.add(new BooleanToken(line, start_index, false));
                            case "nil" -> tokens.add(new NilToken(line, start_index));

                            case "if" -> tokens.add(new IfToken(line, start_index));
                            case "else" -> tokens.add(new ElseToken(line, start_index));
                            case "while" -> tokens.add(new WhileToken(line, start_index));
                            case "for" -> tokens.add(new ForToken(line, start_index));

                            case "def" -> tokens.add(new DefToken(line, start_index));
                            case "return" -> tokens.add(new ReturnToken(line, start_index));

                            case "println" -> tokens.add(new NativeCallToken(line, start_index, NativeMethods.PRINT_LN));
                            case "print" -> tokens.add(new NativeCallToken(line, start_index, NativeMethods.PRINT));
                            case "wait" -> tokens.add(new NativeCallToken(line, start_index, NativeMethods.WAIT));
                            default -> tokens.add(new IdentityToken(line, start_index, buf));
                        }
                    } else if (!Character.isWhitespace(c))
                        gen_exception(line, line_index, "unexpected character - " + c);
            }
            line_index++;
            pos++;
        }

        tokens.add(new EOFToken(line, line_index));

        return  tokens;
    }

    private void gen_exception(int line, int line_index, String reason) {
        String[] lines = new String(data).split("\n", -1);
        String context = "";

        if (line >= 0 && line < lines.length) {
            String srcLine = lines[line];
            String caret = " ".repeat(Math.max(0, line_index)) + "^";
            context = srcLine + "\n" + caret + "\n";
        }

        throw new Bl0j_LexerException(line, line_index, context + reason);
    }

    private char unescape(char escaped, int line, int line_index) {
        return switch (escaped) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '0' -> '\0';
            case '\'' -> '\'';
            case '\\' -> '\\';
            default -> {
                gen_exception(line, line_index, "unknown escape sequence - \\" + escaped);
                yield escaped; // unreachable, gen_exception always throws
            }
        };
    }

    private char lookAhead() {
        if (pos + 1 >= len)
            return '\0';
        return data[pos+1];
    }

    private char lookAhead2() {
        if (pos + 2 >= len)
            return '\0';
        return data[pos+2];
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

    private boolean isIdentityContinuation(char c) {
        return isIdentity(c) || isNumber(c);
    }
}
