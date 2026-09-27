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

    public static final byte LR_POW = 0x0E;
    public static final byte NEW_ARRAY = 0x0F;

    public static final byte JUMP = 0x10;
    public static final byte JUMP_IF = 0x11;
    public static final byte JUMP_IF_NOT = 0x12;

    public static final byte CALL = 0x13;
    public static final byte RETURN = 0x14;
    public static final byte CALL_NATIVE = 0x15;

    public static final byte INDEX_GET = 0x16;
    public static final byte INDEX_SET = 0x17;
    public static final byte LENGTH = 0x18;
    // 0x19 (formerly TO_ARRAY) is free - toArr() moved to Bl0jv2_Prelude
    // once push()/len()/indexing made it expressible without an opcode

    public static final byte LR_AND = 0x1A;
    public static final byte LR_OR = 0x1B;
    public static final byte LR_XOR = 0x1C;
    public static final byte LR_SHL = 0x1D;
    public static final byte LR_SHR = 0x1E;
    public static final byte BIT_NOT = 0x1F;

    // 0x20 onward is unallocated - nothing requires staying within the
    // original 0x00-0x1F block, that was just the first author's reserved
    // buffer, not a format limit
    public static final byte PUSH = 0x20;
    public static final byte POP = 0x21;

    public static final byte TO_INT = 0x22;
    public static final byte TO_FLOAT = 0x23;
    public static final byte TO_STRING = 0x24;
    public static final byte TYPE_OF = 0x25;

    public static final byte NEW_TUPLE = 0x26;
    public static final byte UNPACK = 0x27;

    public static final byte READ = 0x28;

    public static final byte TRY_ENTER = 0x29;
    public static final byte TRY_EXIT = 0x2A;
    public static final byte MAKE_ERR = 0x2B;

    public static final byte NEW_INSTANCE = 0x2C;
    public static final byte GET_FIELD = 0x2D;
    public static final byte SET_FIELD = 0x2E;
    public static final byte LOOKUP_METHOD = 0x2F;

    public static final byte HALT = (byte) 0xFF;
}
