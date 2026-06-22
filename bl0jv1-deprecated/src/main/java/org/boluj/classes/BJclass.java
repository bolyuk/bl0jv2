package org.boluj.classes;

import org.boluj.BJC;
import org.boluj.Interpreter;
import org.boluj.classes.fromJava.BJavaFunction;

import java.lang.reflect.Method;

public class BJclass extends BJobject{
   public  Interpreter in_context;
   public BJclass(Interpreter context){
        super(",","class");
        in_context = new Interpreter(context);
        for(Method m : BJobject.class.getMethods())
            in_context.setVariable(m.getName(), new BJavaFunction(in_context).parseJava(m).setJContext(this));
    }


    public BJobject exec(BJStatement args){
        return in_context.exec(args, new BJobject(), 0);
    }
}
