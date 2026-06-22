package org.boluj.operators;

import org.boluj.Interpreter;
import org.boluj.classes.BJException;
import org.boluj.classes.BJobject;

public class OrOperator implements AbstractOperator{
    @Override
    public boolean canExec(BJobject p, BJobject n) {
        return (p != null && n != null);
    }

    @Override
    public String getOperator() {
        return "|";
    }

    @Override
    public BJobject exec(BJobject p, BJobject n, Interpreter context) {
        p = (p.isStatement())? p.getStatement(context):p;
        n = (n.isStatement())? n.getStatement(context):n;
    try{
        return new BJobject(String.valueOf( p.getLogic() || n.getLogic()),"logic");
    }catch (Exception err){}
        return new BJException("can not execute [ "+getOperator()+" "+n.type+" ]");
}


}
