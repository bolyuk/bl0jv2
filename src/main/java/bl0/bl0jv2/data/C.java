package bl0.bl0jv2.data;

public final class C {
    private C() {}

    // 06.25.26
    // byte as constant
    // new opcode order
    // 09.27.26
    // a lot of stuff
    // 09.28.26
    // widened instruction operands from 1 byte to 2 bytes (255 -> 65535
    // registers/instructions per scope)
    public static final int VERSION = 5;

    public static final int MAGIC = 0x426C306A;

    // instruction = [opcode:1][a:2][b:2], big-endian, fixed width
    public static final int INSTR_WIDTH = 5;
    public static final int MAX_OPERAND = 0xFFFF;
}
