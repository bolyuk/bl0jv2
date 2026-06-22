package org.boluj.operators;

import org.boluj.Interpreter;
import org.boluj.classes.*;
import org.boluj.classes.fromJava.BJavaFunction;

import java.lang.reflect.Method;

public class FunctionOperator implements AbstractOperator {
    @Override
    public boolean canExec(BJobject p, BJobject n) {
        return (p != null && n != null);
    }

    @Override
    public String getOperator() {
        return ":";
    }

    @Override
    public BJobject exec(BJobject p, BJobject n, Interpreter context) {
        p = (p.isStatement())? p.getStatement(context):p;

    if(context.getVariable(p.value) == null || context.getVariable(p.value).isFunction()) {
        if(!n.isStatement())
            return new BJException("can not execute [ expected function:(arguments) or function:((arguments)=(body))  ]");

        BJStatement _n = (BJStatement) n;
        if (_n._objects.size() == 3) {
            switch (_n._objects.get(1).value) {
                case "=":
                    return context.setVariable(p.value,
                            new BJfunction(context)
                                    .setArguments((BJStatement) _n.get(0))
                                    .setBody((BJStatement) _n.get(2)));
                case ">":
                    String v = _n.get(2).isStatement() ? _n.get(2).getStatement(context).value : _n.get(2).value;
                    Class c;
                    try {
                        c = Class.forName(v);
                    } catch (Exception e) {
                        return new BJException("function can not be created [ java class is not exist ]");
                    }
                    Method[] m = c.getMethods();
                    Method r = null;
                    for (Method _m : m)
                        if (_m.getName().equals(p.value) && ((BJStatement) _n.get(0)).size() == _m.getParameters().length)
                            r = _m;
                    if (r == null)
                        return new BJException("java function in this class is not founded!");

                    context.setVariable(p.value, new BJavaFunction(context).parseJava(r));
                    return context.getVariable(p.value);
                default:
                    return new BJException("can not execute [ expected function:((arguments)=(body)) but "+p.value+"  ]");
            }
        } else {
            if (context.getVariable(p.value) == null)
                return new BJException("can not execute [ function " + p.value + " is not exist ]");

            if (!context.getVariable(p.value).isFunction())
                return new BJException("can not execute [ " + p.value + " is not function]");
            try {
                return ((BJfunction) context.getVariable(p.value)).exec((BJStatement) n);
            } catch (Exception err) {
                return new BJException("can not execute [ " + getOperator() + " " + n.type + " ]"+err.toString());
            }

        }
    } else {
        return ((BJclass)context.getVariable(p.value)).exec((BJStatement) n);
    }
    }
}
