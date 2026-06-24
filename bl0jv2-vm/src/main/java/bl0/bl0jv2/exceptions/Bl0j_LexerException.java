package bl0.bl0jv2.exceptions;

public class Bl0j_LexerException extends Bl0j_Exception {
    public Bl0j_LexerException(int line, int line_index, String message)
    {
        super("Lexer Exception [ line: "+line+" index: "+line_index+" ] - "+message);
    }
}
