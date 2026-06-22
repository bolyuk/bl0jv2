package org.boluj.classes;

import org.boluj.Interpreter;

import java.util.ArrayList;

public class BJStatement extends BJobject{
    public Interpreter _context;
    public ArrayList<BJobject> _objects = new ArrayList<>();

    public BJStatement(Interpreter context){
        _context=context;
    }
    public void add(BJobject bjo){
        _objects.add(bjo);
    }

    public BJobject get(int i){
        return  _objects.get(i);
    }
    public int size() {return _objects.size();}
}
