package bl0.bl0jv2.exceptions;

public class Bl0j_VM_Exception extends Bl0j_Exception{
    // true once the VM has prefixed the message with the bytecode address it
    // happened at ("Exception on address: N - ..."). An error that crosses
    // several nested execute() calls (a toString() calling toString() ...)
    // must not collect one prefix per level, and a program's catch variable
    // must see the message WITHOUT the prefix - plainMessage keeps it.
    private boolean located;
    private final String plainMessage;

    public Bl0j_VM_Exception(String message) {
        super(message);
        this.plainMessage = message;
    }

    private Bl0j_VM_Exception(String message, String plainMessage) {
        super(message);
        this.plainMessage = plainMessage;
        this.located = true;
    }

    /** a copy of this error whose message says where it happened */
    public Bl0j_VM_Exception locatedAt(int instructionIndex) {
        return new Bl0j_VM_Exception("Exception on address: " + instructionIndex + " - " + plainMessage, plainMessage);
    }

    public boolean isLocated() {
        return located;
    }

    public String plainMessage() {
        return plainMessage;
    }
}
