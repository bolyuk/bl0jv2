package bl0.bl0jv2.exceptions;

/**
 * Raised by the bl0jv2 builtin panic(msg). Unlike an ordinary runtime error
 * (division by zero, array bounds, ...), a panic is never routed to an
 * enclosing try/catch - see Bl0jv2_jVM.execute()'s exception-catch block,
 * which special-cases this type to let it propagate straight out instead of
 * unwinding to the nearest registered handler.
 */
public final class Bl0j_VM_Panic extends Bl0j_VM_Exception {
    public Bl0j_VM_Panic(String message) {
        super("panic: " + message);
    }
}
