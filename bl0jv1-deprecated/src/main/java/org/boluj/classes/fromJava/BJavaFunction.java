package org.boluj.classes.fromJava;

import org.boluj.DebugUtil;
import org.boluj.Interpreter;
import org.boluj.Kovalski;
import org.boluj.classes.BJException;
import org.boluj.classes.BJStatement;
import org.boluj.classes.BJfunction;
import org.boluj.classes.BJobject;

import java.lang.reflect.Method;

public class BJavaFunction extends BJfunction {
    Method method;
    Object jContext;
    public BJavaFunction(Interpreter context) {
        super(context);
    }

    public BJavaFunction parseJava(Method f){
        method=f;
        String t =f.getGenericReturnType().getTypeName();
        /*if(!t.equals("java.lang.String")
                && !t.equals("void")
                && !t.equals("boolean")
                && !t.equals("int")){
            Kovalski.l("wrong java function type [ "+f.getName()+" : "+t+" ]");
            return null;
        }*/

        return this;
    }

    public BJavaFunction setJContext(Object j){
        this.jContext=j;
        return this;
    }

    @Override
    public BJobject exec(BJStatement args) {
        try {
            Object r =method.invoke(jContext, BJavaUtil.argsToObj(args, _context, method));
            if(r != null)
                return new BJobject( r.toString(), "string");
            else
               return this;
        }catch (Exception e){
            DebugUtil.print(args);
            return new BJException(e.toString());
        }
    }
}
