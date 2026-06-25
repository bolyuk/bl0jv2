package bl0.bl0jv2.data;

public final class OpCodes {

    public static final byte LOAD_NIL = 0x00;
    public static final byte LOAD_CONST = 0x01;

    public static final byte LR_ADD = 0x02;
    public static final byte LR_SUB = 0x03;
    public static final byte LR_MUL = 0x04;
    public static final byte LR_DIV = 0x05;
    public static final byte LR_REM = 0x06;

    public static final byte EQ = 0x07;
    public static final byte LESS = 0x08;
    public static final byte GREATER = 0x09;

    public static final byte MOV = 0x0A;
    public static final byte SET = 0x0B;

    public static final byte NEG = 0x0C;
    public static final byte NOT = 0x0D;

    public static final byte RESERVED_0E = 0x0E;
    public static final byte RESERVED_0F = 0x0F;

    public static final byte JUMP = 0x10;
    public static final byte JUMP_IF = 0x11;
    public static final byte JUMP_IF_NOT = 0x12;

    public static final byte CALL = 0x13;
    public static final byte RETURN = 0x14;
    public static final byte CALL_NATIVE = 0x15;

    public static final byte RESERVED_16 = 0x16;
    public static final byte RESERVED_17 = 0x17;
    public static final byte RESERVED_18 = 0x18;
    public static final byte RESERVED_19 = 0x19;
    public static final byte RESERVED_1A = 0x1A;
    public static final byte RESERVED_1B = 0x1B;
    public static final byte RESERVED_1C = 0x1C;
    public static final byte RESERVED_1D = 0x1D;
    public static final byte RESERVED_1E = 0x1E;
    public static final byte RESERVED_1F = 0x1F;

    public static final byte HALT = (byte) 0xFF;
}
