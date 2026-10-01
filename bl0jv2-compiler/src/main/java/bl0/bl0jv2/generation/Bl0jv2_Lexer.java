package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.NativeMethods;
import bl0.bl0jv2.exceptions.Bl0j_LexerException;
import bl0.bl0jv2.generation.tokens.ClassToken;
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
                        tokens.add(new OpToken(line, line_index, peekIfNext('>') ? Operator.SHIFT_RIGHT_UNSIGNED : Operator.SHIFT_RIGHT));
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
                case '.':
                    tokens.add(new DotToken(line, line_index));
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
                    if (peekIfNext('>'))
                        tokens.add(new ArrowToken(line, line_index));
                    else
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
                    if (c == '\'' || c == '"') {
                        // 'text' or "text": same escapes in both, the other quote
                        // character needs none. A newline inside is kept and
                        // counted, so later tokens still report the right line.
                        char quote = c;
                        int start_index = line_index;
                        int start_line = line;
                        StringBuilder buf = new StringBuilder();
                        boolean closed = false;
                        while (pos + 1 < len) {
                            if (isNext(quote)) {
                                pos++; // consume the closing quote
                                closed = true;
                                break;
                            }
                            char ch = peek();
                            line_index++;
                            if (ch == '\n') {
                                line++;
                                line_index = 0;
                                buf.append(ch);
                            } else if (ch == '\\' && pos + 1 < len) {
                                char escaped = peek();
                                line_index++;
                                if (escaped == 'x') {
                                    // \xNN: exactly two hex digits, one byte value
                                    if (pos + 2 >= len || !isHexDigit(data[pos + 1]) || !isHexDigit(data[pos + 2]))
                                        gen_exception(line, line_index, "expected two hex digits after \\x");
                                    buf.append((char) Integer.parseInt("" + data[pos + 1] + data[pos + 2], 16));
                                    pos += 2;
                                    line_index += 2;
                                } else
                                    buf.append(unescape(escaped, line, line_index));
                            } else
                                buf.append(ch);
                        }
                        if (!closed)
                            gen_exception(start_line, start_index, "unterminated string literal");
                        tokens.add(new StringToken(start_line, start_index, buf.toString()));
                    } else if (isNumber(c) && c == '0' && pos + 1 < len && isRadixPrefix(lookAhead())) {
                        // 0x.../0b... - kept with their prefix in the
                        // token's own text; the parser picks the radix off
                        // it (see Bl0jv2_Parser.data()). Parsed with
                        // Integer.parseUnsignedInt there, not parseInt, so
                        // a full-width bit pattern like 0xFFFFFFFF is a
                        // valid literal even though it's negative as a
                        // signed int - the whole point of writing one in
                        // hex instead of decimal in the first place
                        int start_index = line_index;
                        boolean isHex = lookAhead() == 'x' || lookAhead() == 'X';
                        String buf = "" + c + peek(); // consume the prefix char
                        line_index++;

                        while (pos + 1 < len && (isHex ? isHexDigit(lookAhead()) : isBinaryDigit(lookAhead()))) {
                            buf += peek();
                            line_index++;
                        }

                        if (buf.length() == 2)
                            gen_exception(line, start_index, "expected digits after '" + buf + "'");

                        tokens.add(new NumberToken(line, start_index, buf));
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
                        // exponent: 1e5, 2.5E-3, 4e+2 - only when digits really
                        // follow, otherwise the letter is a stray one (below)
                        if (pos + 1 < len && (lookAhead() == 'e' || lookAhead() == 'E')) {
                            int sign = (pos + 2 < len && (data[pos + 2] == '+' || data[pos + 2] == '-')) ? 1 : 0;
                            if (pos + 2 + sign < len && isNumber(data[pos + 2 + sign])) {
                                buf += peek(); // e
                                line_index++;
                                if (sign == 1) {
                                    buf += peek(); // sign
                                    line_index++;
                                }
                                while (pos + 1 < len && isNumber(lookAhead())) {
                                    buf += peek();
                                    line_index++;
                                }
                            }
                        }
                        if (pos + 1 < len && (isIdentity(lookAhead())))
                            gen_exception(line, line_index + 1, "invalid number literal - '" + buf + lookAhead() + "' (letters directly after digits)");
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
                            case "break" -> tokens.add(new BreakToken(line, start_index));
                            case "continue" -> tokens.add(new ContinueToken(line, start_index));
                            case "try" -> tokens.add(new TryToken(line, start_index));
                            case "catch" -> tokens.add(new CatchToken(line, start_index));
                            case "switch" -> tokens.add(new SwitchToken(line, start_index));
                            case "case" -> tokens.add(new CaseToken(line, start_index));
                            case "default" -> tokens.add(new DefaultToken(line, start_index));
                            case "enum" -> tokens.add(new EnumToken(line, start_index));

                            case "def" -> tokens.add(new DefToken(line, start_index));
                            case "return" -> tokens.add(new ReturnToken(line, start_index));

                            case "class" -> tokens.add(new ClassToken(line, start_index));
                            case "field" -> tokens.add(new FieldToken(line, start_index));
                            case "new" -> tokens.add(new NewToken(line, start_index));
                            case "this" -> tokens.add(new ThisToken(line, start_index));
                            case "static" -> tokens.add(new StaticToken(line, start_index));
                            case "const" -> tokens.add(new ConstToken(line, start_index));
                            case "import" -> tokens.add(new ImportToken(line, start_index));

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
            case '"' -> '"';
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

    private boolean isRadixPrefix(char c) {
        return c == 'x' || c == 'X' || c == 'b' || c == 'B';
    }

    private boolean isHexDigit(char c) {
        return isNumber(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private boolean isBinaryDigit(char c) {
        return c == '0' || c == '1';
    }

    private boolean isIdentity(char c) {
        return  Character.isAlphabetic(c) || c == '_';
    }

    private boolean isIdentityContinuation(char c) {
        return isIdentity(c) || isNumber(c);
    }
}
