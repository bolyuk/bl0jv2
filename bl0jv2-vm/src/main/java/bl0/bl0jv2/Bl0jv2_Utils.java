package bl0.bl0jv2;

import bl0.bl0jv2.data.Constants;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class Bl0jv2_Utils {
    public static void dump_file(byte[] file, Writer writer) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(file);

        writer.append(String.format("%n=== HEADER (10 bytes)===%n"));
        writer.append(String.format("magic   = 0x%08X%n", bytes.getInt()));
        writer.append(String.format("version = %d%n",  bytes.getShort()));

        int constCount = bytes.getShort();

        writer.append(String.format("consts  = %d%n", constCount));
        writer.append(String.format("regs    = %d%n", bytes.getShort()));

        writer.append(String.format("%n=== CONSTANTS ===%n"));

        for (int i = 0; i < constCount; i++) {
            int type = bytes.get();

            switch (type) {
                case Constants.INT -> writer.append(
                        String.format("[%d] INT    = %d  (4 bytes)%n",
                                i, bytes.getInt()));

                case Constants.STRING -> {
                    String val = get_str(bytes);
                    writer.append(String.format("[%d] STRING = %s  (%d bytes)%n", i, val , val.length()));
                }

                case Constants.BOOL ->
                    writer.append(String.format("[%d] BOOL   = %b  (1 bytes)%n", i, bytes.get() != 0));

                case Constants.FUN -> {
                    String s = get_str(bytes);
                    int len = s.length();

                    writer.append(
                            String.format("[%d] FUN = %s ad:%d ag:%d rg:%d (%d bytes)%n", i, s, bytes.getInt(), bytes.getShort(), bytes.getShort(), len+10));
                }

                default -> throw new RuntimeException("Unknown const type: " + type);
            }
        }
        int instr = 0;

        writer.append(String.format("%n=== BYTECODE ===%n"));

        int p = 0;
        int remaining_len = bytes.remaining();

        byte[] remaining = new byte[remaining_len];
        bytes.get(remaining);
        while (p + 2 <= remaining.length) {

            int op = remaining[p++] & 0xFF;
            int a  = remaining[p++] & 0xFF;
            int b  = remaining[p++] & 0xFF;

            writer.append(String.format("%04d: 0x%02X ", instr, op));

            switch (op) {
                case 0x00 -> writer.append(String.format("LOAD_NIL %d", a));
                case 0x01 -> writer.append(String.format("LOAD_CONST r%d = const[%d]", a, b));
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
    }

    private static String get_str(ByteBuffer bytes){
        int len = bytes.getShort() & 0xFFFF;
        byte[] strBytes = new byte[len];
        bytes.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8);
    }
}
