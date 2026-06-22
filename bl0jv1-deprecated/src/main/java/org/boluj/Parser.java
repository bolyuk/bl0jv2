package org.boluj;

import org.boluj.classes.BJException;
import org.boluj.classes.BJStatement;
import org.boluj.classes.BJobject;

import java.util.ArrayList;

public class Parser {

    public static ArrayList<BJStatement> parseCode(String BJcode, Interpreter context){
        ArrayList<BJStatement> _parsedStatements = new ArrayList<>();
        int i=0;
        BJobject r;

        for(; i < BJcode.length(); ){
            r=parseStatement(BJcode,i, context);
            i=Integer.parseInt(r.value);
            if(r instanceof BJException){
                Kovalski.l(r.value);
                return null;
            } else {
                _parsedStatements.add((BJStatement)r);
                System.out.println();
                DebugUtil.print((BJStatement)r);
                System.out.println();
            }

        }
        return _parsedStatements;
    }

    public static BJobject parseStatement(String BJcode, int i, Interpreter context){
        BJStatement _result = new BJStatement(context);
        BJobject _Cobj = new BJobject();

        try {
            for (; i < BJcode.length(); i++) {
                char c = BJcode.charAt(i);
                switch (c) {
                    case '\"':
                        if (_Cobj.value != "")
                            _result.add(_Cobj);
                        String _sbuf ="";
                        for(i++;i<BJcode.length();i++)
                            if(BJcode.charAt(i) != '\"')
                                _sbuf+=BJcode.charAt(i);
                            else
                                break;
                        _result.add(new BJobject(_sbuf,"string"));
                        break;
                    case ')':
                    case '}':
                    case ']':
                        if (!_Cobj.value.equals(""))
                            _result.add(_Cobj);
                        _result.value=String.valueOf(i);
                        return _result;
                    case ';':
                    case ',':
                    case ' ':
                        if (!_Cobj.value.equals(""))
                            _result.add(_Cobj);
                        _Cobj = new BJobject();
                        break;
                    case '(':
                    case '[':
                    case '{':
                        if (!_Cobj.value.equals(""))
                            _result.add(_Cobj);
                        _Cobj = new BJobject();
                        BJStatement _buf =(BJStatement) parseStatement(BJcode, i+1, context);
                        i=Integer.parseInt(_buf.value);
                        _buf.type="statement";
                        _result.add(_buf);
                        break;
                    default:
                        if (Character.isDigit(c)) {
                            _Cobj.value += c;
                            if (_Cobj.type != "variable")
                                _Cobj.type = "number";
                        } else if (Character.isLetter(c)) {
                            _Cobj.value += c;
                            _Cobj.type = "variable";
                        } else if(c == '\n'){}
                        else{
                            if (!_Cobj.value.equals(""))
                                _result.add(_Cobj);
                            _Cobj = new BJobject();
                            _result.add(new BJobject(String.valueOf(c), "operator"));
                        }
                }
            }
            if (!_Cobj.value.equals(""))
                _result.add(_Cobj);
            i++;
            _result.value=String.valueOf(i);
            return _result;
        }catch (Exception err){
            return new BJException("BJnterp:parseStatement() error!  [ " + err.getMessage()+ " ]");
        }
    }
}
