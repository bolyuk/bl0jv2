package bl0.bl0jv2;

import bl0.bl0jv2.data.C;
import bl0.bl0jv2.data.Constants;
import bl0.bl0jv2.data.FunDef;
import bl0.bl0jv2.data.OpCodes;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class Bl0jv2_jVM {

    public static final Object NIL = new Object() {
        @Override
        public String toString() { return "nil"; }
    };

    private Writer out;

    private Object[] reg;
    private Object[] consts;
    private byte[] instructions;

    public Bl0jv2_jVM() {}

    public void feed_compiled_file(ByteBuffer bytes){
        bytes.order(ByteOrder.BIG_ENDIAN);

        if(bytes.getInt() != C.MAGIC){
            throw new Bl0j_VM_Exception("Wrong magic number");
        }

        short version = bytes.getShort();

        if(version != C.VERSION){
            throw new Bl0j_VM_Exception("Incompatible Bl0jv2_jVM version: [ " + C.VERSION + " != " + version+" ]");
        }

        short constants_length = bytes.getShort();
        short registers_length = bytes.getShort();

        reg = new Object[registers_length];
        consts = new Object[constants_length];

        for (int i = 0; i < constants_length; i++) {
            byte type = bytes.get();
            switch (type) {
                case Constants.INT -> consts[i] = bytes.getInt();
                case Constants.STRING -> consts[i] = get_str(bytes);
                case Constants.BOOL -> consts[i] = bytes.get() != 0;
                case Constants.FUN -> consts[i] = new FunDef(
                        get_str(bytes),
                        bytes.getInt(),
                        bytes.getShort(),
                        bytes.getShort());
                default -> throw new Bl0j_VM_Exception("Unknown const type: " + type);
            }
        }

        int remaining = bytes.remaining();

        if(remaining % 3 != 0)
            throw new Bl0j_VM_Exception("wrong amount of instructions");

        instructions = new byte[remaining];
        bytes.get(instructions);
    }

    public void set_out_writer(Writer out){
        this.out = out;
    }

    public void run_instructions() throws IOException {
        for(int addr = 0; addr < instructions.length;){
            byte opcode = (byte) (instructions[addr] & 0xFF);

            int a = instructions[addr+1] & 0xFF;
            int b = instructions[addr+2] & 0xFF;
            addr += 3;

            switch (opcode) {
                case OpCodes.LOAD_NIL -> reg[a] = NIL;
                case OpCodes.LOAD_CONST -> reg[a] = consts[b];

                case OpCodes.LR_ADD -> reg[a] = (int) reg[a] + (int) reg[b];
                case OpCodes.LR_SUB -> reg[a] = (int) reg[a] - (int) reg[b];
                case OpCodes.LR_MUL -> reg[a] = (int) reg[a] * (int) reg[b];
                case OpCodes.LR_DIV -> reg[a] = (int) reg[a] / (int) reg[b];

                case OpCodes.JUMP -> addr = a * 3;
                case OpCodes.JUMP_IF -> { if ( (boolean) reg[a]) addr = b * 3; }
                case OpCodes.JUMP_IF_NOT -> { if (!(boolean) reg[a]) addr = b * 3; }

                case OpCodes.EQ -> reg[a] = Objects.equals(reg[a], reg[b]);
                case OpCodes.LESS -> reg[a] = (int) reg[a] < (int) reg[b];
                case OpCodes.GREATER  -> reg[a] = (int) reg[a] > (int) reg[b];
                case OpCodes.NOT -> reg[a] = !(boolean) reg[a];

                case OpCodes.MOV -> reg[a] = reg[b];
                case OpCodes.SET -> reg[a] = b;
                case OpCodes.NEG  -> reg[a] = -(int) reg[a];

                case OpCodes.PRINT -> {
                    if (out != null) out.append(reg[a].toString());
                    else System.out.println(reg[a]);
                }
                case OpCodes.HALT -> {
                    return;
                }
            }
        }
    }

    private String get_str(ByteBuffer bytes){
        int len = bytes.getShort() & 0xFFFF;
        byte[] strBytes = new byte[len];
        bytes.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8);
    }
}
