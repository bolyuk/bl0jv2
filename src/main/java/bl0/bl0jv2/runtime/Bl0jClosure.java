package bl0.bl0jv2.runtime;

import bl0.bl0jv2.data.FunDef;

/**
 * A lambda value: its compiled FunDef plus the cells it captured from
 * enclosing scopes, in the same order they were prepended as its own
 * leading (implicit) parameters at compile time - mirrors how an instance
 * method gets 'this' prepended, just with N captures instead of one fixed
 * receiver.
 */
public final class Bl0jClosure {
    private final FunDef funDef;
    private final long[] capturedCells;

    Bl0jClosure(FunDef funDef, long[] capturedCells) {
        this.funDef = funDef;
        this.capturedCells = capturedCells;
    }

    public FunDef funDef() {
        return funDef;
    }

    public long[] capturedCells() {
        return capturedCells;
    }

    @Override
    public String toString() {
        return "function";
    }
}
