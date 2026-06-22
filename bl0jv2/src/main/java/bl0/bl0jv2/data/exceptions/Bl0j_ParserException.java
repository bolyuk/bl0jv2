package bl0.bl0jv2.data.exceptions;

public class Bl0j_ParserException extends Bl0j_Exception {
    public Bl0j_ParserException(int line, int line_index, String message)
    {
        super("Parser Exception [ line: "+line+" index: "+line_index+" ] - "+message);
    }
}
