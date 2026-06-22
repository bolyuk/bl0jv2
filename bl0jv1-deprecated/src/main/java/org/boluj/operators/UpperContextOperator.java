package org.boluj.operators;

import org.boluj.Interpreter;
import org.boluj.classes.BJException;
import org.boluj.classes.BJStatement;
import org.boluj.classes.BJobject;

public class UpperContextOperator implements AbstractOperator{
    @Override
    public boolean canExec(BJobject p, BJobject n) {
        return n != null;
    }

    @Override
    public String getOperator() {
        return "`";
    }

    @Override
    public BJobject exec(BJobject p, BJobject n, Interpreter context) {
        if(context.getInner() == null)
            return new BJException("upper context is not exist");
        if(!n.isStatement())
            return new BJException("expected `(statement)");
        return context.getInner().exec((BJStatement)n,new BJobject(),0 );
    }
}
