package org.boluj;

import java.io.*;

public class DFile {


    public static boolean isExist(String path){
        return new File(path).exists();
    }

    public static boolean isDir(String path){
        return new File(path).isDirectory();
    }

    public static void append(String content, String path) {
        try {
            File file = new File(path);

            FileWriter writer = new FileWriter(file);
            writer.append(content);
            writer.flush();
            writer.close();
        } catch (Exception e) {Kovalski.l(e.toString()); }
    }

    public static void write(String content, String path) {
        try {
            File file = new File(path);

            if (!file.exists()) file.createNewFile();

            FileWriter writer = new FileWriter(file);
            writer.write(content);
            writer.flush();
            writer.close();
        } catch (Exception e){ Kovalski.l(e.toString()); }
    }

    public static String read(String path) throws Exception{
        try {
            File file = new File(path);
            StringBuilder text = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;

            while ((line = br.readLine()) != null) {
                text.append(line);
                text.append('\n');
            }
            br.close();
            return text.toString();
        }catch (Exception e){Kovalski.l(e.toString());}
        throw new Exception();
    }

    public static void mkDir(String path){
        try {
            File f = new File(path);
            f.mkdir();
        }catch (Exception e){
            Kovalski.l(e.toString());
        }
    }

    public static String getName(String path){
        return new File(path).getName();
    }
}
