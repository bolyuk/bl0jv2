package org.boluj.classes.fromJava;

import org.boluj.Interpreter;
import org.boluj.classes.BJStatement;
import org.boluj.classes.BJfunction;
import org.boluj.classes.BJobject;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;

public class BJavaUtil {

    public static Object[] argsToObj(BJStatement args, Interpreter context, Method m){
        Object[] r = new Object[args.size()];
        for(int i=0; i <args.size(); i++) {
            BJobject c = args.get(i).isStatement() ? args.get(i).getStatement(context) : (BJobject) args.get(i);
            switch (m.getParameterTypes()[i].getTypeName()){
                case "boolean":
                    r[i]=c.getLogic();
                    break;
                case "int":
                    r[i]=c.getNumber();
                    break;
                default:
                    r[i]= c.value;
            }
        }
        return r;
    }
}
