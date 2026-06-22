package org.boluj;

import org.boluj.classes.BJStatement;

public class DebugUtil {
    public static <T> void print(T[] arr){
        for(T a : arr){
            System.out.print(a+" , ");
        }
    }

    public static void print(BJStatement s){

        System.out.print("[ ");
        for(int i=0; i< s._objects.size(); i++)
             if(s._objects.get(i).type == "statement"){
                 System.out.print(" (statement) ");
                 print((BJStatement)s._objects.get(i));
             } else
            System.out.print("("+s._objects.get(i).type+")"+s._objects.get(i).value+((i < s._objects.size()-1)?" , ": ""));
        System.out.print(" ]");
    }
}
