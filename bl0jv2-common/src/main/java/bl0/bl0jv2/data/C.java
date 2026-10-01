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
    // 10.01.26
    // third operand (c): CALL carries its argument count so the VM can check
    // arity, and SET_FIELD/SET_STATIC_FIELD name their member directly
    // instead of packing it into a register pair; a FUN constant records
    // whether its first parameter is a receiver ('this')
    // 10.02.26
    // binary operators are three-operand (reg[c] = reg[a] OP reg[b]) instead of
    // 'reg[a] = reg[a] OP reg[b]' after a MOV of the left operand
    // 10.03.26
    // GET_FIELD / LOOKUP_METHOD write their result to c instead of overwriting
    // the object register (no MOV of the object first)
    public static final int VERSION = 8;

    public static final int MAGIC = 0x426C306A;

    // instruction = [opcode:1][a:2][b:2][c:2], big-endian, fixed width
    public static final int INSTR_WIDTH = 7;
    public static final int MAX_OPERAND = 0xFFFF;
}
