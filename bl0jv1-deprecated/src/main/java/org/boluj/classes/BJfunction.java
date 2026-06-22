package org.boluj.classes;

import org.boluj.Interpreter;

//TODO доделать бы....
public class BJfunction extends BJobject{
   protected Interpreter in_context;
   protected Interpreter _context;
   protected BJStatement args;

    public BJfunction(Interpreter context){
        super(",","function");
        _context=context;
        in_context= new Interpreter(_context);
    }

    public BJfunction setArguments(BJStatement args){
        this.args=args;
        return this;
    }

    public BJfunction setBody(BJStatement bjs){
        in_context.clearParsedCode();
        in_context.put(bjs);
        return this;
    }


    public BJobject exec(BJStatement args){
        if(args.size() < this.args.size())
            return new BJException("function can not be executed [number of arguments less than expected]");
        for(int i=0; i < this.args.size(); i++) {
            BJobject c = args._objects.get(i);
            c = c.isStatement()?c.getStatement(_context):c;
            in_context.setVariable(this.args._objects.get(i).value, c);
        }

        return in_context.exec();

    }
}
