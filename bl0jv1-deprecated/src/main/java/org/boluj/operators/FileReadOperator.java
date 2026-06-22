package org.boluj.operators;

import org.boluj.DFile;
import org.boluj.Interpreter;
import org.boluj.classes.BJException;
import org.boluj.classes.BJobject;

public class FileReadOperator implements AbstractOperator{
    @Override
    public boolean canExec(BJobject p, BJobject n) {
        return n != null;
    }

    @Override
    public String getOperator() {
        return "^";
    }

    @Override
    public BJobject exec(BJobject p, BJobject n, Interpreter context) {
        n = n.isStatement()?n.getStatement(context):n;
        try{
            return new BJobject(DFile.read(n.value),"string");
        }catch (Exception e ){}
         return new BJException("can not execute [ ^ ] file ["+n.value+"] is not exist ");
    }
}
