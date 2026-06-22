package org.boluj.classes;

import org.boluj.Interpreter;

public class BJobject {
    public String value = "";
    public String type = "";
    public boolean isFinal=false;

    public BJobject(String value, String type){
        this.value=value;
        this.type=type;
    }

    public BJobject(){
    }

    public boolean isNumber(){
        return type.equals("number");
    }

    public boolean isString(){
        return type.equals("string");
    }

    public boolean isLogic(){
        return type.equals("logic") || value.equals("true") || value.equals("false") || value.equals("0") || value.equals("1");
    }

    public boolean isStatement(){
       return type.equals("statement");
    }

    public boolean isFunction(){ return type.equals("function");}

    public boolean isClass(){return type.equals("class");}


    public Integer getNumber(){
       return Integer.parseInt(value);
    }

    public boolean isFinal() {return isFinal;};

    public BJobject setFinal(boolean isFinal){this.isFinal=isFinal; return this;}

    public boolean getLogic(){
        if(value.equals("true") || value.equals("1"))
            return true;
         return false;
    }

    //TODO TEST
    public BJobject getStatement(Interpreter context){
        return context.exec((BJStatement)this,new BJobject(),0);
    }
}
