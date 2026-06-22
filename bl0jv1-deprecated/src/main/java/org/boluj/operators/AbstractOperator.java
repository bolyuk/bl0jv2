package org.boluj.operators;

import org.boluj.Interpreter;
import org.boluj.classes.BJobject;

public interface AbstractOperator {
     boolean canExec(BJobject p, BJobject n);
     String getOperator();
     BJobject exec(BJobject p, BJobject n, Interpreter context);
}
