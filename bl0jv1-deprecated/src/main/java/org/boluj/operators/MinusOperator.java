package org.boluj.operators;

import org.boluj.Interpreter;
import org.boluj.classes.BJException;
import org.boluj.classes.BJobject;

public class MinusOperator implements AbstractOperator {

    @Override
    public boolean canExec(BJobject p, BJobject n) {
        return p != null && n != null;
    }

    @Override
    public String getOperator() {
        return "-";
    }

    @Override
    public BJobject exec(BJobject p, BJobject n, Interpreter context) {
        p = (p.isStatement())? p.getStatement(context):p;
        n = (n.isStatement())? n.getStatement(context):n;


        if(p.isNumber() && n.isNumber())
            return new BJobject(String.valueOf(p.getNumber()-n.getNumber()),"number");
        if(p.isString() && n.isNumber())
            return new BJobject(p.value.substring(0,p.value.length()-n.getNumber()), "string");
        return new BJException("can not execute [ "+p.type+" "+getOperator()+" "+n.type+" ]");
    }
}
