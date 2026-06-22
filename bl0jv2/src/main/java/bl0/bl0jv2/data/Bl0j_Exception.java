package bl0.bl0jv2.data;

import bl0.bl0jv2.interfaces.Bl0j_Object;

public class Bl0j_Exception extends RuntimeException implements Bl0j_Object {
    public Bl0j_Exception(String message) {
        super(message);
    }
}
