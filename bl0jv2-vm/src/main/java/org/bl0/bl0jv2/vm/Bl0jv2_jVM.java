package org.bl0.bl0jv2.vm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class Bl0jv2_jVM {

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
            byte opcode = instructions[addr];

            int a = instructions[addr+1] & 0xFF;
            int b = instructions[addr+2] & 0xFF;
            addr += 3;

            switch (opcode) {
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
                    addr = (int) reg[a];
                    break;
                case 0x07: // JUMP_IF
                    if ((boolean) reg[a])
                        addr = (int) reg[b];
                    break;
                case 0x08: // JUMP_IF_NOT
                    if (!(boolean) reg[a])
                        addr = (int) reg[b];
                    break;
                case 0x09: // EQ
                    reg[a] = reg[a].equals(reg[b]);
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

                case (byte) 0xFE: // PRINT
                    System.out.println(reg[a]);
                    break;

                case (byte) 0xFF: // stop
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
}
