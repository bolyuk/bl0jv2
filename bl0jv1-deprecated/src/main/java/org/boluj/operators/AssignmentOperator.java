package org.boluj.operators;

import org.boluj.Interpreter;
import org.boluj.classes.BJException;
import org.boluj.classes.BJobject;

public class AssignmentOperator implements AbstractOperator{
    @Override
    public boolean canExec(BJobject p, BJobject n) {
        return p != null && n != null;
    }

    @Override
    public String getOperator() {
        return "=";
    }

    @Override
    public BJobject exec(BJobject p, BJobject n, Interpreter context) {
        p = (p.isStatement())? p.getStatement(context):p;
        n = (n.isStatement())? n.getStatement(context):n;
        try {
            if(context.getVariable(p.value) != null && context.getVariable(p.value).isFinal)
                return new BJException("variable "+p.value+" can not be modified");
            return context.putVariable(p.value, n);
        }catch (Exception err){}
        return new BJException("can not execute [ "+p.type+" "+getOperator()+" "+n.type+" ]");
    }
}
