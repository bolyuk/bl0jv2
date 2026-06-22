package org.boluj;

import org.boluj.Interpreter;
import org.boluj.Kovalski;
import org.boluj.classes.BJobject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainClass {
     public static void main(String[] args) {
         Interpreter e = new Interpreter(null);
        while(true){
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                System.out.print(">");

                String code =br.readLine();

                e.parse(code);

                BJobject _response = e.exec();
                if(_response.value != "")
                    Kovalski.l("returned : "+_response.value);

            }catch (Exception err) {}
            e.clearParsedCode();
        }
    }
}
