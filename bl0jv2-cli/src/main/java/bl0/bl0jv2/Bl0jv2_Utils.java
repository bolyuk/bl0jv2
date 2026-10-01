package bl0.bl0jv2;

import bl0.bl0jv2.data.C;
import bl0.bl0jv2.data.Constants;
import bl0.bl0jv2.data.OpCodes;

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
                            String.format("[%d] FUN = %s ad:%d ag:%d rg:%d%s (%d bytes)%n", i, s, bytes.getInt() & 0xFFFF, bytes.getShort(), bytes.getShort(),
                                    bytes.get() != 0 ? " method" : "", len+11));
                }
                case Constants.BYTE -> {
                    writer.append(String.format("[%d] BYTE   = 0x%02X %n", i, bytes.get()));
                }

                case Constants.FLOAT ->
                    writer.append(String.format("[%d] FLOAT  = %s  (8 bytes)%n", i, bytes.getDouble()));

                // mirrors Bl0jv2_Compiler's ClassDef serialization exactly -
                // every field must be consumed in order or every constant
                // after this one desyncs
                case Constants.CLASS -> {
                    String className = get_str(bytes);

                    int fieldCount = bytes.getShort() & 0xFFFF;
                    StringBuilder fields = new StringBuilder();
                    for (int f = 0; f < fieldCount; f++) {
                        if (f > 0) fields.append(", ");
                        fields.append(get_str(bytes));
                        if (bytes.get() != 0) // hasDefault
                            fields.append("=const[").append(bytes.getShort() & 0xFFFF).append("]");
                    }

                    int methodCount = bytes.getShort() & 0xFFFF;
                    StringBuilder methods = new StringBuilder();
                    for (int m = 0; m < methodCount; m++) {
                        if (m > 0) methods.append(", ");
                        String methodName = get_str(bytes);
                        int methodConstIdx = bytes.getShort() & 0xFFFF;
                        methods.append(methodName).append("->const[").append(methodConstIdx).append("]");
                    }

                    int staticFieldCount = bytes.getShort() & 0xFFFF;

                    writer.append(String.format("[%d] CLASS  = %s  fields=[%s]  methods=[%s]  staticFields=%d%n",
                            i, className, fields, methods, staticFieldCount));
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
        while (p + C.INSTR_WIDTH - 1 <= remaining.length) {

            byte op = (byte) (remaining[p++] & 0xFF);
            int a = ((remaining[p++] & 0xFF) << 8) | (remaining[p++] & 0xFF);
            int b = ((remaining[p++] & 0xFF) << 8) | (remaining[p++] & 0xFF);
            int c = ((remaining[p++] & 0xFF) << 8) | (remaining[p++] & 0xFF);

            writer.append(String.format("%04d: 0x%02X ", instr, op));

            switch (op) {
                case OpCodes.LOAD_NIL -> writer.append(String.format("LOAD_NIL %d", a));
                case OpCodes.LOAD_CONST -> writer.append(String.format("LOAD_CONST r%d = const[%d]", a, b));
                case OpCodes.LR_ADD -> writer.append(String.format("ADD r%d = r%d + r%d", c, a, b));
                case OpCodes.LR_SUB -> writer.append(String.format("SUB r%d = r%d - r%d", c, a, b));
                case OpCodes.LR_MUL -> writer.append(String.format("MUL r%d = r%d * r%d", c, a, b));
                case OpCodes.LR_DIV -> writer.append(String.format("DIV r%d = r%d / r%d", c, a, b));
                case OpCodes.LR_REM -> writer.append(String.format("REM r%d = r%d % r%d", c, a, b));
                case OpCodes.LR_POW -> writer.append(String.format("POW r%d = r%d ** r%d", c, a, b));
                case OpCodes.LR_AND -> writer.append(String.format("AND r%d = r%d & r%d", c, a, b));
                case OpCodes.LR_OR -> writer.append(String.format("OR r%d = r%d | r%d", c, a, b));
                case OpCodes.LR_XOR -> writer.append(String.format("XOR r%d = r%d ^ r%d", c, a, b));
                case OpCodes.LR_SHL -> writer.append(String.format("SHL r%d = r%d << r%d", c, a, b));
                case OpCodes.LR_SHR -> writer.append(String.format("SHR r%d = r%d >> r%d", c, a, b));
                case OpCodes.LR_USHR -> writer.append(String.format("USHR r%d = r%d >>> r%d", c, a, b));
                case OpCodes.BIT_NOT -> writer.append(String.format("BIT_NOT r%d", a));

                case OpCodes.JUMP -> writer.append(String.format("JUMP %d", a));
                case OpCodes.JUMP_IF -> writer.append(String.format("JUMP_IF r%d -> %d", a, b));
                case OpCodes.JUMP_IF_NOT -> writer.append(String.format("JUMP_IF_NOT r%d -> %d", a, b));

                case OpCodes.EQ -> writer.append(String.format("EQ r%d = r%d == r%d", c, a, b));
                case OpCodes.LESS -> writer.append(String.format("LESS r%d = r%d < r%d", c, a, b));
                case OpCodes.GREATER -> writer.append(String.format("GREATER r%d = r%d > r%d", c, a, b));
                case OpCodes.LESS_EQ -> writer.append(String.format("LESS_EQ r%d = r%d <= r%d", c, a, b));
                case OpCodes.GREATER_EQ -> writer.append(String.format("GREATER_EQ r%d = r%d >= r%d", c, a, b));

                case OpCodes.MOV -> writer.append(String.format("MOV r%d = r%d", a, b));
                case OpCodes.SET -> writer.append(String.format("SET r%d = r%d", a, b));
                case OpCodes.NEG -> writer.append(String.format("NEG r%d", a));
                case OpCodes.NOT -> writer.append(String.format("NOT r%d", a));

                case OpCodes.HALT -> writer.append("HALT");
                case OpCodes.CALL_NATIVE -> writer.append(String.format("CALL_NATIVE r%d r%d", a, b));
                case OpCodes.CALL -> writer.append(String.format("CALL r%d args@%d argc=%d", a, b, c));
                case OpCodes.RETURN -> writer.append(String.format("RETURN r%d", a));
                case OpCodes.NEW_ARRAY -> writer.append(String.format("NEW_ARRAY r%d elements@%d count=%d", a, a + 1, b));
                case OpCodes.NEW_TUPLE -> writer.append(String.format("NEW_TUPLE r%d elements@%d count=%d", a, a + 1, b));
                case OpCodes.UNPACK -> writer.append(String.format("UNPACK r%d into @%d count=%d", a, a + 1, b));
                case OpCodes.INDEX_GET -> writer.append(String.format("INDEX_GET r%d = r%d[r%d]", a, a, b));
                case OpCodes.INDEX_SET -> writer.append(String.format("INDEX_SET r%d[r%d] = r%d", a, b, b + 1));
                case OpCodes.LENGTH -> writer.append(String.format("LENGTH r%d = len(r%d)", a, a));
                case OpCodes.PUSH -> writer.append(String.format("PUSH r%d, r%d", a, b));
                case OpCodes.POP -> writer.append(String.format("POP r%d = pop(r%d)", a, b));
                case OpCodes.TO_INT -> writer.append(String.format("TO_INT r%d = int(r%d)", a, a));
                case OpCodes.TO_FLOAT -> writer.append(String.format("TO_FLOAT r%d = float(r%d)", a, a));
                case OpCodes.TO_STRING -> writer.append(String.format("TO_STRING r%d = str(r%d)", a, a));
                case OpCodes.TYPE_OF -> writer.append(String.format("TYPE_OF r%d = typeOf(r%d)", a, a));
                case OpCodes.TRY_ENTER -> writer.append(String.format("TRY_ENTER catch@%d errReg=r%d", b, a));
                case OpCodes.TRY_EXIT -> writer.append("TRY_EXIT");
                case OpCodes.MAKE_ERR -> writer.append(String.format("MAKE_ERR r%d = err(r%d)", a, a));
                case OpCodes.NEW_INSTANCE -> writer.append(String.format("NEW_INSTANCE r%d = new(r%d)", a, a));
                case OpCodes.GET_FIELD -> writer.append(String.format("GET_FIELD r%d = r%d.field[const %d]", c, a, b));
                case OpCodes.SET_FIELD -> writer.append(String.format("SET_FIELD r%d.const[%d] = r%d", a, c, b));
                case OpCodes.LOOKUP_METHOD -> writer.append(String.format("LOOKUP_METHOD r%d = r%d.method[const %d]", c, a, b));
                case OpCodes.GET_STATIC_FIELD -> writer.append(String.format("GET_STATIC_FIELD r%d = r%d.staticField[%d]", a, a, b));
                case OpCodes.SET_STATIC_FIELD -> writer.append(String.format("SET_STATIC_FIELD r%d.staticField[%d] = r%d", a, c, b));
                case OpCodes.MAKE_CELL -> writer.append(String.format("MAKE_CELL r%d", a));
                case OpCodes.CELL_GET -> writer.append(String.format("CELL_GET r%d = *r%d", a, a));
                case OpCodes.CELL_SET -> writer.append(String.format("CELL_SET *r%d = r%d", a, b));
                case OpCodes.MAKE_CLOSURE -> writer.append(String.format("MAKE_CLOSURE r%d fun=r%d captures@%d count=%d", a, a, a + 1, b));
                case OpCodes.FREE -> writer.append(String.format("FREE r%d", a));
                case OpCodes.PEEK -> writer.append(String.format("PEEK r%d = *r%d (%d-bit)", a, a, b));
                // b is a register holding [width, value] (SET_FIELD's own
                // packing trick), not an immediate the way PEEK's b is -
                // the actual width isn't known until runtime
                case OpCodes.POKE -> writer.append(String.format("POKE *r%d width=r%d value=r%d", a, b, b + 1));
                case OpCodes.REGISTER_HANDLER -> writer.append(String.format("REGISTER_HANDLER fn=r%d vector=r%d priority=r%d", a, b, b + 1));
                case OpCodes.RESERVE -> writer.append(String.format("RESERVE addr=r%d size=r%d", a, b));
                case OpCodes.DISPATCH -> writer.append(String.format("DISPATCH fn=r%d core=r%d arg=r%d", a, b, b + 1));
                case OpCodes.PORT_IN -> writer.append(String.format("PORT_IN r%d = in(r%d) (%d-bit)", a, a, b));
                case OpCodes.PORT_OUT -> writer.append(String.format("PORT_OUT out(*r%d) width=r%d value=r%d", a, b, b + 1));
                case OpCodes.SYSCALL -> writer.append(String.format("SYSCALL r%d = syscall(vector=r%d, arg=r%d)", a, a, b));
                case OpCodes.ATOMIC_ADD -> writer.append(String.format("ATOMIC_ADD r%d = atomicAdd(*r%d, r%d)", a, a, b));
                case OpCodes.ATOMIC_CAS -> writer.append(String.format("ATOMIC_CAS r%d = atomicCas(*r%d, expected=r%d, new=r%d)", a, a, b, b + 1));
                default -> writer.append("UNKNOWN");
            }
            writer.append(String.format("%n"));
            instr++;
        }
        writer.flush();
    }

    private static String get_str(ByteBuffer bytes){
        int len = bytes.getShort() & 0xFFFF;
        byte[] strBytes = new byte[len];
        bytes.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8);
    }
}
