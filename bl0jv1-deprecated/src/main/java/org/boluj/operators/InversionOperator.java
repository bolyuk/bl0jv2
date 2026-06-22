package org.boluj.operators;

import org.boluj.Interpreter;
import org.boluj.classes.BJException;
import org.boluj.classes.BJobject;

public class InversionOperator implements AbstractOperator{
    @Override
    public boolean canExec(BJobject p, BJobject n) {
        return n != null;
    }

    @Override
    public String getOperator() {
        return "!";
    }

    @Override
    public BJobject exec(BJobject p, BJobject n, Interpreter context) {
        n = (n.isStatement())? n.getStatement(context):n;
        try {
            return new BJobject(String.valueOf(!n.getLogic()),"logic");
        }catch (Exception err){}
        return new BJException("can not execute [ "+getOperator()+" "+n.type+" ]");
    }
}
