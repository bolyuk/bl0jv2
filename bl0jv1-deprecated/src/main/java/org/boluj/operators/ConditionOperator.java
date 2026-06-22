package org.boluj.operators;

import org.boluj.Interpreter;
import org.boluj.Kovalski;
import org.boluj.classes.BJException;
import org.boluj.classes.BJStatement;
import org.boluj.classes.BJobject;

public class ConditionOperator implements AbstractOperator{
    @Override
    public boolean canExec(BJobject p, BJobject n) {
        return n != null;
    }

    @Override
    public String getOperator() {
        return "?";
    }

    @Override
    public BJobject exec(BJobject p, BJobject n, Interpreter context) {

        try {
            if (!n.isStatement())
                return new BJException("can not execute [ "+getOperator()+" ] - [ ?((condition)>(code)) or ?((condition)>(code):(else code)) ] expected [ n is not statement]");

            BJStatement _n = (BJStatement) n;
            if (!(_n.size() == 3 || _n.size() == 5))
                return new BJException("can not execute [ "+getOperator()+" ] - [ ?((condition)>(code)) or ?((condition)>(code):(else code)) ] expected [ size of statement is not 3 or 5]");
            if (!_n.get(1).value.equals(">"))
                return new BJException("can not execute [ "+getOperator()+" ] - [ ?((condition)>(code)) or ?((condition)>(code):(else code)) ] expected [ operator is not > ]");

            if (((_n.get(0).isStatement()) ? _n.get(0).getStatement(context) : _n.get(0)).getLogic()) {
                return ((_n.get(2).isStatement()) ? _n.get(2).getStatement(context) : _n.get(2));
            }
            else
                if(_n.size() == 5) {
                    if (!_n.get(3).value.equals(":"))
                        return new BJException("can not execute [ "+getOperator()+" ] - [ ?((condition)>(code)) or ?((condition)>(code):(else code)) ] expected [ else operator is not : ]");
                    return ((_n.get(4).isStatement()) ? _n.get(4).getStatement(context) : _n.get(4));
                }
                else
                    return new BJobject();

        }catch (Exception err) {
            return new BJException("can not execute [ " + err.getMessage() + " ]");
        }
    }
}
