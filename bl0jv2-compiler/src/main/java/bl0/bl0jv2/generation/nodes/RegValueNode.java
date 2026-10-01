package bl0.bl0jv2.generation.nodes;

/**
 * Compiler-internal: "the value already sitting in register {@code reg}".
 * Lets a construct that has computed a value (x++ computing x + 1) hand it to
 * compileAssign() as the thing to store, without re-evaluating anything.
 * The parser never produces one.
 */
public class RegValueNode extends Node {
    public final int reg;

    public RegValueNode(int reg) {
        this.reg = reg;
    }
}
