package org.bl0.bl0jv2.vm;

import java.io.IOException;
import java.io.Writer;
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

    private Writer out;

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

    public void set_out_writer(Writer out){
        this.out = out;
    }

    public void set_instructions(byte[] instruction){
        instructions = instruction;
    }

    public void run_instructions() throws IOException {
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
                    if(out != null)
                        out.append(reg[a].toString());
                    else
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

    public static void dump_file(byte[] file, Writer writer) throws IOException {
        int p = 0;
        int headerStart = p;

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

        int headerSize = p - headerStart;

        writer.append(String.format("%n=== HEADER === (%d bytes)%n", headerSize));
        writer.append(String.format("magic   = 0x%08X%n", magic));
        writer.append(String.format("version = %d%n", version));
        writer.append(String.format("consts  = %d%n", constCount));
        writer.append(String.format("regs    = %d%n", regCount));

        writer.append(String.format("%n=== CONSTANTS ===%n"));

        Object[] constants = new Object[constCount];
        int constsStart = p;

        for (int i = 0; i < constCount; i++) {
            int entryStart = p;
            int type = file[p++] & 0xFF;

            switch (type) {
                case 0x01 -> {
                    int val =
                            (file[p++] & 0xFF) << 24 |
                                    (file[p++] & 0xFF) << 16 |
                                    (file[p++] & 0xFF) << 8 |
                                    (file[p++] & 0xFF);

                    constants[i] = val;
                    writer.append(String.format("[%d] INT    = %d  (%d bytes)%n", i, val, p - entryStart));
                }

                case 0x02 -> {
                    int len = ((file[p] & 0xFF) << 8) | (file[p + 1] & 0xFF);
                    p += 2;

                    String s = new String(file, p, len);
                    p += len;

                    constants[i] = s;
                    writer.append(String.format("[%d] STRING = %s  (%d bytes)%n", i, s, p - entryStart));
                }

                case 0x03 -> {
                    boolean val = (file[p++] & 0xFF) != 0;

                    constants[i] = val;
                    writer.append(String.format("[%d] BOOL   = %b  (%d bytes)%n", i, val, p - entryStart));
                }

                default -> throw new RuntimeException("Unknown const type: " + type);
            }
        }

        int constsSize = p - constsStart;

        int bytecodeStart = p;
        int instr = 0;

        writer.append(String.format("%n=== BYTECODE ===%n"));

        while (p + 2 <= file.length) {

            int op = file[p++] & 0xFF;
            int a  = file[p++] & 0xFF;
            int b  = file[p++] & 0xFF;

            writer.append(String.format("%04d: 0x%02X ", instr, op));

            switch (op) {
                case 0x00 -> writer.append(String.format("LOAD_NIL %d", a));
                case 0x01 -> writer.append(String.format("LOAD_CONST r%d = const[%d] (%s)", a, b, constants[b]));
                case 0x02 -> writer.append(String.format("ADD r%d = r%d + r%d", a, a, b));
                case 0x03 -> writer.append(String.format("SUB r%d = r%d - r%d", a, a, b));
                case 0x04 -> writer.append(String.format("MUL r%d = r%d * r%d", a, a, b));
                case 0x05 -> writer.append(String.format("DIV r%d = r%d / r%d", a, a, b));

                case 0x06 -> writer.append(String.format("JUMP %d", a));
                case 0x07 -> writer.append(String.format("JUMP_IF r%d -> %d", a, b));
                case 0x08 -> writer.append(String.format("JUMP_IF_NOT r%d -> %d", a, b));

                case 0x09 -> writer.append(String.format("EQ r%d = r%d == r%d", a, a, b));
                case 0x0A -> writer.append(String.format("LESS r%d = r%d < r%d", a, a, b));
                case 0x0B -> writer.append(String.format("GREATER r%d = r%d > r%d", a, a, b));

                case 0x0C -> writer.append(String.format("MOV r%d = r%d", a, b));
                case 0x0D -> writer.append(String.format("SET r%d = r%d", a, b));
                case 0x0E -> writer.append(String.format("NEG r%d", a));
                case 0x0F -> writer.append(String.format("NOT r%d", a));

                case 0xFE -> writer.append(String.format("PRINT r%d", a));
                case 0xFF -> writer.append("HALT");

                default -> writer.append("UNKNOWN");
            }
            writer.append(String.format("%n"));
            instr++;
        }

        int bytecodeSize = p - bytecodeStart;

        writer.append(String.format("%n=== SUMMARY ===%n"));
        writer.append(String.format("header    = %4d bytes  (%5.1f%%)%n", headerSize,   100.0 * headerSize   / file.length));
        writer.append(String.format("constants = %4d bytes  (%5.1f%%)%n", constsSize,   100.0 * constsSize   / file.length));
        writer.append(String.format("bytecode  = %4d bytes  (%5.1f%%)%n", bytecodeSize, 100.0 * bytecodeSize / file.length));
        writer.append(String.format("total     = %4d bytes  (%d instrs)%n", file.length, instr));
    }
}
