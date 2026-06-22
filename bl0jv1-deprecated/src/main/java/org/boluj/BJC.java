package org.boluj;

import org.boluj.operators.*;

public class BJC {
    public static char[]numbers = {'1','2','3','4','5','6','7','8','9','0'};
    public static char[]letters = {'q','w','e','r','t','y','u','i','o','p','a','s','d','f','g','h','j','k','l','z','x','c','v','b','n','m'};
    public static char[]symbols = {'+','-','=',';'};

    public static AbstractOperator[] operators=
             {
                     new PlusOperator(),
                     new MinusOperator(),
                     new MultiplicationOperator(),
                     new DivisionOperator(),
                     new AssignmentOperator(),
                     new GrabeOperator(),
                     new MoreOperator(),
                     new LessOperator(),
                     new ConditionOperator(),
                     new StatementExecOperator(),
                     new StatementSaveOperator(),
                     new InversionOperator(),
                     new OrOperator(),
                     new AndOperator(),
                     new FunctionOperator(),
                     new FileReadOperator(),
                     new ClassOperator(),
                     new UpperContextOperator()
             };

}
