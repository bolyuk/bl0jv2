package org.bl0.bl0jv2.vm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class Bl0jv2_jVM {

    public static final Object NIL = new Object() {
        @Override
        public String toString() { return "nil"; }
    };

    private final int version = 1;

    private Object[] reg;
    private Object[] constants;
    private byte[] instructions;

    public Bl0jv2_jVM() {}

    public void feed_compiled_file(ByteBuffer bytes){
        bytes.order(ByteOrder.BIG_ENDIAN);

        int magic = bytes.getInt();

        if(magic != 0x426C306A){
            throw new RuntimeException("Wrong magic number");
        }

        short version = bytes.getShort();

        if(version != this.version){
            throw new RuntimeException("Incompatible Bl0jv2_jVM version: [ " + this.version + " != " + version+" ]");
        }

        short constants_length = bytes.getShort();
        short registers_length = bytes.getShort();

        reg = new Object[registers_length];
        constants = new Object[constants_length];

        for (int i = 0; i < constants_length; i++) {
            byte type = bytes.get();
            if (type == 0x01) {
                constants[i] = bytes.getInt();
            } else if (type == 0x02) {
                int len = bytes.getShort() & 0xFFFF;
                byte[] strBytes = new byte[len];
                bytes.get(strBytes);
                constants[i] = new String(strBytes, StandardCharsets.UTF_8);
            } else if(type == 0x03)
            {
                constants[i] = bytes.get() != 0;
            }
        }

        int remaining = bytes.remaining();
        instructions = new byte[remaining];
        bytes.get(instructions);
    }

    public void set_instructions(byte[] instruction){
        instructions = instruction;
    }

    public void run_instructions(){
        for(int addr = 0; addr < instructions.length;){
            int opcode = instructions[addr] & 0xFF;

            int a = instructions[addr+1] & 0xFF;
            int b = instructions[addr+2] & 0xFF;
            addr += 3;

            switch (opcode) {
                case 0x00: // LOAD_NIL
                    reg[a] = NIL;
                    break;
                case 0x01: // LOAD_CONST
                    reg[a] = constants[b];
                    break;
                case 0x02: // ADD
                    reg[a] = (int) reg[a] + (int) reg[b];
                    break;
                case 0x03: // SUB
                    reg[a] = (int) reg[a] - (int) reg[b];
                    break;
                case 0x04: // MUL
                    reg[a] = (int) reg[a] * (int) reg[b];
                    break;
               case 0x05: // DIV
                    reg[a] = (int) reg[a] / (int) reg[b];
                    break;
                case 0x06: // JUMP
                    addr = a*3;
                    break;
                case 0x08: // JUMP_IF_NOT
                    if (!(boolean) reg[a])
                        addr = b*3;
                    break;
                case 0x09: // EQ
                    reg[a] = Objects.equals(reg[a], reg[b]);
                    break;
                case 0x0A: // LESS_THAN
                    reg[a] = (int) reg[a] < (int) reg[b];
                    break;
                case 0x0B: // GREATER_THAN
                    reg[a] = (int) reg[a] > (int) reg[b];
                    break;
                case 0x0C: // MOV
                    reg[a] = reg[b];
                    break;
                case 0x0D: // SET
                    reg[a] = b;
                    break;
                case 0x0E: // NEG
                    reg[a] = -(int) reg[a];
                    break;
                case 0x0F: // NOT
                    reg[a] = !(boolean) reg[a];
                    break;
                case 0xFE: // PRINT
                    System.out.println(reg[a]);
                    break;

                case 0xFF: // stop
                    return;
            }
        }
    }

    public void set_register(int index, Object value) {
        reg[index] = value;
    }

    public static ByteBuffer mock_header() {
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putInt(0x426C306A);   // magic
        buf.putShort((short) 1); // version
        buf.putShort((short) 0); // constants_length = 0
        buf.putShort((short) 256); // registers_length
        buf.flip();
        return buf;
    }

    public static void dump_file(byte[] file) {
        int p = 0;

        int magic =
                ((file[p] & 0xFF) << 24) |
                        ((file[p + 1] & 0xFF) << 16) |
                        ((file[p + 2] & 0xFF) << 8) |
                        (file[p + 3] & 0xFF);
        p += 4;

        short version = (short) ((file[p] & 0xFF) << 8 | (file[p + 1] & 0xFF));
        p += 2;

        short constCount = (short) ((file[p] & 0xFF) << 8 | (file[p + 1] & 0xFF));
        p += 2;

        short regCount = (short) ((file[p] & 0xFF) << 8 | (file[p + 1] & 0xFF));
        p += 2;

        System.out.println("=== HEADER ===");
        System.out.printf("magic   = 0x%08X%n", magic);
        System.out.printf("version = %d%n", version);
        System.out.printf("consts  = %d%n", constCount);
        System.out.printf("regs    = %d%n", regCount);

        System.out.println("\n=== CONSTANTS ===");

        Object[] constants = new Object[constCount];

        for (int i = 0; i < constCount; i++) {

            int type = file[p++] & 0xFF;

            switch (type) {
                case 0x01 -> {
                    int val =
                            (file[p++] & 0xFF) << 24 |
                                    (file[p++] & 0xFF) << 16 |
                                    (file[p++] & 0xFF) << 8 |
                                    (file[p++] & 0xFF);

                    constants[i] = val;
                    System.out.printf("[%d] INT    = %d%n", i, val);
                }

                case 0x02 -> {
                    int len = ((file[p] & 0xFF) << 8) | (file[p + 1] & 0xFF);
                    p += 2;

                    String s = new String(file, p, len);
                    p += len;

                    constants[i] = s;
                    System.out.printf("[%d] STRING = %s%n", i, s);
                }

                case 0x03 -> {
                    boolean val = (file[p++] & 0xFF) != 0;

                    constants[i] = val;
                    System.out.printf("[%d] BOOL   = %b%n", i, val);
                }

                default -> throw new RuntimeException("Unknown const type: " + type);
            }
        }

        System.out.println("\n=== BYTECODE ===");

        int pc = 0;
        int instr = 0;

        while (p + 2 < file.length) {

            int op = file[p++] & 0xFF;
            int a  = file[p++] & 0xFF;
            int b  = file[p++] & 0xFF;

            System.out.printf("%04d: 0x%02X ", instr, op);

            switch (op) {

                case 0x00 -> System.out.printf("LOAD_NIL %d", a);
                case 0x01 -> System.out.printf("LOAD_CONST r%d = const[%d] (%s)", a, b, constants[b]);
                case 0x02 -> System.out.printf("ADD r%d = r%d + r%d", a, a, b);
                case 0x03 -> System.out.printf("SUB r%d = r%d - r%d", a, a, b);
                case 0x04 -> System.out.printf("MUL r%d = r%d * r%d", a, a, b);
                case 0x05 -> System.out.printf("DIV r%d = r%d / r%d", a, a, b);

                case 0x06 -> System.out.printf("JUMP %d", a);
                case 0x07 -> System.out.printf("JUMP_IF r%d -> %d", a, b);
                case 0x08 -> System.out.printf("JUMP_IF_NOT r%d -> %d", a, b);

                case 0x09 -> System.out.printf("EQ r%d = r%d == r%d", a, a, b);
                case 0x0A -> System.out.printf("LESS r%d = r%d < r%d", a, a, b);
                case 0x0B -> System.out.printf("GREATER r%d = r%d > r%d", a, a, b);

                case 0x0C -> System.out.printf("MOV r%d = r%d", a, b);
                case 0x0D -> System.out.printf("SET r%d = r%d", a, b);
                case 0x0E -> System.out.printf("NEG r%d", a);
                case 0x0F -> System.out.printf("NOT r%d", a);

                case 0xFE -> System.out.print("PRINT r" + a);
                case 0xFF -> System.out.print("HALT");

                default -> System.out.print("UNKNOWN");
            }

            System.out.println();
            instr++;
        }
    }
}
