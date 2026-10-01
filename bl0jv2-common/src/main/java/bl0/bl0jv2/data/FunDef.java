package bl0.bl0jv2.data;

// arity counts every parameter slot the callee has, including leading
// implicit ones: a lambda's captured cells and an instance method's 'this'.
// receiver is true exactly for the latter (an instance method's first
// parameter is 'this', passed by the call site rather than written by the
// caller) - it only exists so arity errors can talk about the arguments the
// caller actually wrote.
public record FunDef(String name, int address, short arity, short regs, boolean receiver) {
    public FunDef(String name, int address, short arity, short regs) {
        this(name, address, arity, regs, false);
    }
}
