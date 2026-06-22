package org.boluj.operators;

import org.boluj.Interpreter;
import org.boluj.classes.BJException;
import org.boluj.classes.BJobject;

// TODO не работает
public class StatementSaveOperator implements AbstractOperator{
    @Override
    public boolean canExec(BJobject p, BJobject n) {
        return n != null;
    }

    @Override
    public String getOperator() {
        return "@";
    }

    @Override
    public BJobject exec(BJobject p, BJobject n, Interpreter context) {
        if(!n.isStatement())
            return new BJException("can not execute [ @ ] n is not statement ");
        return n;
    }
}
