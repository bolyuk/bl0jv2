package bl0.bl0jv2.runtime;

import bl0.bl0jv2.data.*;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class Bl0jv2_jVM {

    // the Java object nil unboxes to; only ever reached through NanBox.NIL,
    // so its identity is never observed outside this class
    private static final Object NIL_OBJECT = new Object() {
        @Override
        public String toString() { return "nil"; }
    };

    private Writer out;

    private final OperatorTable addTable = new OperatorTable();
    private final OperatorTable subTable = new OperatorTable();
    private final OperatorTable divTable = new OperatorTable();
    private final OperatorTable mulTable = new OperatorTable();
    private final OperatorTable remTable = new OperatorTable();
    private final OperatorTable powTable = new OperatorTable();
    private final OperatorTable andTable = new OperatorTable();
    private final OperatorTable orTable = new OperatorTable();
    private final OperatorTable xorTable = new OperatorTable();
    private final OperatorTable shlTable = new OperatorTable();
    private final OperatorTable shrTable = new OperatorTable();

    private ArrayDeque<Frame> callStack = new ArrayDeque<>();
    private Map<Byte, Function<Object, Object>> nativeMethods = new HashMap<>();

    // reference-typed values (strings, FunDefs, ...) that registers/consts
    // hold as a NanBox REF index rather than inline
    private final List<Object> heap = new ArrayList<>();

    private long[] consts;
    private byte[] instructions;

    public Bl0jv2_jVM() {
        nativeMethods.put(NativeMethods.PRINT, (d) -> {
            if (out != null) {
                try {
                    out.append(d.toString());
                } catch (IOException e) {
                    return -1;
                }
            }
            else System.out.print(d);
            return 0;
        });
        nativeMethods.put(NativeMethods.PRINT_LN, (d) -> {
            if (out != null) {
                try {
                    out.append("\n").append(d.toString());
                } catch (IOException e) {
                    return -1;
                }
            }
            else System.out.println(d);
            return 0;
        });
        nativeMethods.put(NativeMethods.WAIT, (d) -> {
            try {
                Thread.sleep((int) d);
            } catch (InterruptedException e) {
                return -1;
            }
            return 0;
        });

        addTable.add(Integer.class, Integer.class, (a, b) -> (int)a + (int)b);
        addTable.add(String.class,  String.class,  (a, b) -> a.toString() + b.toString());
        // concatenation is order-sensitive ('x=' + 5 must read "x=5", not
        // "5x="), so both directions are registered explicitly rather than
        // relying on OperatorTable to guess an order
        addTable.add(String.class,  Integer.class, (a, b) -> a.toString() + b.toString());
        addTable.add(Integer.class, String.class,  (a, b) -> a.toString() + b.toString());
        addTable.add(String.class,  Boolean.class, (a, b) -> a.toString() + b.toString());
        addTable.add(Boolean.class, String.class,  (a, b) -> a.toString() + b.toString());
        addTable.add(String.class,  Double.class,  (a, b) -> a.toString() + b.toString());
        addTable.add(Double.class,  String.class,  (a, b) -> a.toString() + b.toString());
        addTable.add(String.class,  Bl0jArray.class, (a, b) -> a.toString() + b.toString());
        addTable.add(Bl0jArray.class, String.class,  (a, b) -> a.toString() + b.toString());
        addTable.add(String.class,  Character.class, (a, b) -> a.toString() + b.toString());
        addTable.add(Character.class, String.class,  (a, b) -> a.toString() + b.toString());
        // integer arithmetic stays integer (10/3 truncates); any operand
        // that is already a double promotes the whole operation to double
        addTable.add(Double.class,  Double.class,  (a, b) -> toDouble(a) + toDouble(b));
        addTable.add(Integer.class, Double.class,  (a, b) -> toDouble(a) + toDouble(b));
        addTable.add(Double.class,  Integer.class, (a, b) -> toDouble(a) + toDouble(b));

        subTable.add(Integer.class, Integer.class, (a, b) -> (int)a - (int)b);
        subTable.add(Double.class,  Double.class,  (a, b) -> toDouble(a) - toDouble(b));
        subTable.add(Integer.class, Double.class,  (a, b) -> toDouble(a) - toDouble(b));
        subTable.add(Double.class,  Integer.class, (a, b) -> toDouble(a) - toDouble(b));

        mulTable.add(Integer.class, Integer.class, (a, b) -> (int)a * (int)b);
        // string repetition's count can legitimately appear on either side
        mulTable.add(String.class,  Integer.class, (a, b) -> a.toString().repeat((int)b));
        mulTable.add(Integer.class, String.class,  (a, b) -> b.toString().repeat((int)a));
        mulTable.add(Double.class,  Double.class,  (a, b) -> toDouble(a) * toDouble(b));
        mulTable.add(Integer.class, Double.class,  (a, b) -> toDouble(a) * toDouble(b));
        mulTable.add(Double.class,  Integer.class, (a, b) -> toDouble(a) * toDouble(b));

        divTable.add(Integer.class, Integer.class, (a, b) -> {
            if ((int)b == 0) throw new Bl0j_VM_Exception("division by zero");
            return (int)a / (int)b;
        });
        // float division follows IEEE754 (1.0 / 0 is Infinity, not an
        // error) - only pure integer division treats zero as a hard error
        divTable.add(Double.class,  Double.class,  (a, b) -> toDouble(a) / toDouble(b));
        divTable.add(Integer.class, Double.class,  (a, b) -> toDouble(a) / toDouble(b));
        divTable.add(Double.class,  Integer.class, (a, b) -> toDouble(a) / toDouble(b));

        remTable.add(Integer.class, Integer.class, (a, b) -> {
            if ((int)b == 0) throw new Bl0j_VM_Exception("division by zero");
            return (int)a % (int)b;
        });
        remTable.add(Double.class,  Double.class,  (a, b) -> toDouble(a) % toDouble(b));
        remTable.add(Integer.class, Double.class,  (a, b) -> toDouble(a) % toDouble(b));
        remTable.add(Double.class,  Integer.class, (a, b) -> toDouble(a) % toDouble(b));

        powTable.add(Integer.class, Integer.class, (a, b) -> {
            int base = (int) a, exp = (int) b;
            if (exp < 0) throw new Bl0j_VM_Exception("negative exponent");
            int result = 1;
            for (int i = 0; i < exp; i++) result *= base;
            return result;
        });
        // unlike the pure-integer case above, a double base/exponent can
        // represent fractional results, so Math.pow handles negative and
        // fractional exponents directly instead of throwing
        powTable.add(Double.class,  Double.class,  (a, b) -> Math.pow(toDouble(a), toDouble(b)));
        powTable.add(Integer.class, Double.class,  (a, b) -> Math.pow(toDouble(a), toDouble(b)));
        powTable.add(Double.class,  Integer.class, (a, b) -> Math.pow(toDouble(a), toDouble(b)));

        // bitwise operators only make sense on integers
        andTable.add(Integer.class, Integer.class, (a, b) -> (int)a & (int)b);
        orTable.add(Integer.class, Integer.class, (a, b) -> (int)a | (int)b);
        xorTable.add(Integer.class, Integer.class, (a, b) -> (int)a ^ (int)b);
        shlTable.add(Integer.class, Integer.class, (a, b) -> (int)a << (int)b);
        shrTable.add(Integer.class, Integer.class, (a, b) -> (int)a >> (int)b);
    }

    private static double toDouble(Object numeric) {
        return (numeric instanceof Integer i) ? i.doubleValue() : (Double) numeric;
    }

    private static boolean isNumeric(Object value) {
        return value instanceof Integer || value instanceof Double;
    }

    // numeric equality crosses int/double (5 == 5.0 is true), matching the
    // implicit promotion already used by +, -, *, /, %, ** ; everything else
    // falls back to plain value equality
    private static boolean valuesEqual(Object left, Object right) {
        if (isNumeric(left) && isNumeric(right))
            return toDouble(left) == toDouble(right);
        return Objects.equals(left, right);
    }

    private static Object negate(Object value) {
        if (value instanceof Integer i) return -i;
        if (value instanceof Double d) return -d;
        throw new Bl0j_VM_Exception("cannot negate " + value.getClass().getSimpleName());
    }

    private static int length(Object value) {
        if (value instanceof Bl0jArray arr) return arr.length();
        if (value instanceof String s) return s.length();
        throw new Bl0j_VM_Exception("cannot take length of " + value.getClass().getSimpleName());
    }

    private static int bitNot(Object value) {
        if (value instanceof Integer i) return ~i;
        throw new Bl0j_VM_Exception("cannot apply '~' to " + value.getClass().getSimpleName());
    }

    private static Bl0jArray toCharArray(Object value, Bl0jv2_jVM owner) {
        if (!(value instanceof String s))
            throw new Bl0j_VM_Exception("toArr expects a string, got " + value.getClass().getSimpleName());
        long[] chars = new long[s.length()];
        for (int i = 0; i < s.length(); i++)
            chars[i] = NanBox.ofChar(s.charAt(i));
        return new Bl0jArray(chars, owner);
    }

    // shares arr[-1]-style negative indexing with Bl0jArray.getRaw
    private static char charAt(String s, int index) {
        int i = Bl0jArray.normalizeIndex(index, s.length());
        if (i < 0 || i >= s.length())
            throw new Bl0j_VM_Exception("string index out of bounds: " + index + " (length " + s.length() + ")");
        return s.charAt(i);
    }

    private static Object toInt(Object value) {
        if (value instanceof Integer) return value;
        if (value instanceof Double d) return d.intValue();
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Character c) return (int) c;
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new Bl0j_VM_Exception("cannot convert '" + s + "' to int");
            }
        }
        throw new Bl0j_VM_Exception("cannot convert " + value.getClass().getSimpleName() + " to int");
    }

    private static Object toFloat(Object value) {
        if (value instanceof Double) return value;
        if (value instanceof Integer i) return i.doubleValue();
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new Bl0j_VM_Exception("cannot convert '" + s + "' to float");
            }
        }
        throw new Bl0j_VM_Exception("cannot convert " + value.getClass().getSimpleName() + " to float");
    }

    private static String typeName(Object value) {
        if (value instanceof Integer) return "int";
        if (value instanceof Double) return "float";
        if (value instanceof Boolean) return "bool";
        if (value instanceof Character) return "char";
        if (value instanceof String) return "string";
        if (value instanceof Bl0jArray) return "array";
        if (value instanceof FunDef) return "function";
        if (value == NIL_OBJECT) return "nil";
        throw new Bl0j_VM_Exception("unknown type: " + value.getClass().getSimpleName());
    }

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

        callStack.clear();
        heap.clear();
        callStack.add(new Frame(new long[registers_length], -1, -1));
        consts = new long[constants_length];

        for (int i = 0; i < constants_length; i++) {
            byte type = bytes.get();
            switch (type) {
                case Constants.INT -> consts[i] = NanBox.ofInt(bytes.getInt());
                case Constants.STRING -> consts[i] = boxRef(get_str(bytes));
                case Constants.BOOL -> consts[i] = NanBox.ofBoolean(bytes.get() != 0);
                case Constants.FUN -> consts[i] = boxRef(new FunDef(
                        get_str(bytes),
                        bytes.getInt() & 0xFF,
                        bytes.getShort(),
                        bytes.getShort()));
                case Constants.BYTE -> consts[i] = NanBox.ofInt(bytes.get());
                case Constants.FLOAT -> consts[i] = Double.doubleToLongBits(bytes.getDouble());
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
                try {
                byte opcode = (byte) (instructions[addr] & 0xFF);

                int a = instructions[addr+1] & 0xFF;
                int b = instructions[addr+2] & 0xFF;
                addr += 3;

                long[] reg = callStack.peek().regs();

                switch (opcode) {
                    case OpCodes.LOAD_NIL -> reg[a] = NanBox.NIL;
                    case OpCodes.LOAD_CONST -> reg[a] = consts[b];

                    case OpCodes.LR_ADD -> reg[a] = box(addTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_SUB -> reg[a] = box(subTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_MUL -> reg[a] = box(mulTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_DIV -> reg[a] = box(divTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_REM -> reg[a] = box(remTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_POW -> reg[a] = box(powTable.calculate(unbox(reg[a]), unbox(reg[b])));

                    case OpCodes.LR_AND -> reg[a] = box(andTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_OR -> reg[a] = box(orTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_XOR -> reg[a] = box(xorTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_SHL -> reg[a] = box(shlTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_SHR -> reg[a] = box(shrTable.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.BIT_NOT -> reg[a] = NanBox.ofInt(bitNot(unbox(reg[a])));

                    case OpCodes.JUMP -> addr = a * 3;
                    case OpCodes.JUMP_IF -> { if ( (boolean) unbox(reg[a])) addr = b * 3; }
                    case OpCodes.JUMP_IF_NOT -> { if (!(boolean) unbox(reg[a])) addr = b * 3; }

                    case OpCodes.EQ -> reg[a] = NanBox.ofBoolean(valuesEqual(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LESS -> reg[a] = NanBox.ofBoolean(toDouble(unbox(reg[a])) < toDouble(unbox(reg[b])));
                    case OpCodes.GREATER  -> reg[a] = NanBox.ofBoolean(toDouble(unbox(reg[a])) > toDouble(unbox(reg[b])));
                    case OpCodes.NOT -> reg[a] = NanBox.ofBoolean(!(boolean) unbox(reg[a]));

                    case OpCodes.MOV -> reg[a] = reg[b];
                    case OpCodes.SET -> reg[a] = NanBox.ofInt(b);
                    case OpCodes.NEG  -> reg[a] = box(negate(unbox(reg[a])));

                    case OpCodes.CALL -> {
                        FunDef fun = (FunDef) unbox(reg[a]);
                        long[] args = new long[fun.arity()];

                        for (int i = 0; i < fun.arity(); i++)
                            args[i] = reg[b + 1 + i];

                        gen_frame(fun, args, addr, b);
                        addr = fun.address() * 3;
                    }

                    case OpCodes.CALL_NATIVE -> {
                        var nativeFun = nativeMethods.get((byte) a);
                        if (nativeFun == null)
                            throw new Bl0j_VM_Exception("unknown native method: " + a);
                        Object result = nativeFun.apply(unbox(reg[b]));
                        if (result instanceof Integer code && code == -1)
                            throw new Bl0j_VM_Exception("native method " + a + " returned error");
                    }

                    // elements sit at reg[a+1 .. a+b], mirroring CALL's
                    // args-adjacent-to-the-base-register convention
                    case OpCodes.NEW_ARRAY -> {
                        int count = b;
                        long[] elements = new long[count];
                        for (int i = 0; i < count; i++) elements[i] = reg[a + 1 + i];
                        reg[a] = boxRef(new Bl0jArray(elements, this));
                    }

                    case OpCodes.INDEX_GET -> {
                        Object target = unbox(reg[a]);
                        int index = (int) unbox(reg[b]);
                        reg[a] = switch (target) {
                            case Bl0jArray array -> array.getRaw(index);
                            case String s -> NanBox.ofChar(charAt(s, index));
                            default -> throw new Bl0j_VM_Exception("cannot index " + target.getClass().getSimpleName());
                        };
                    }

                    // index and value sit at reg[b] and reg[b+1]
                    case OpCodes.INDEX_SET -> {
                        Bl0jArray array = (Bl0jArray) unbox(reg[a]);
                        int index = (int) unbox(reg[b]);
                        array.setRaw(index, reg[b + 1]);
                    }

                    case OpCodes.LENGTH -> reg[a] = NanBox.ofInt(length(unbox(reg[a])));
                    case OpCodes.TO_ARRAY -> reg[a] = boxRef(toCharArray(unbox(reg[a]), this));

                    // mutates the Bl0jArray object the reference points at,
                    // not the register holding that reference - reg[a]
                    // (the array's own slot) is never overwritten
                    case OpCodes.PUSH -> ((Bl0jArray) unbox(reg[a])).push(reg[b]);
                    case OpCodes.POP -> reg[a] = ((Bl0jArray) unbox(reg[b])).pop();

                    case OpCodes.TO_INT -> reg[a] = box(toInt(unbox(reg[a])));
                    case OpCodes.TO_FLOAT -> reg[a] = box(toFloat(unbox(reg[a])));
                    case OpCodes.TO_STRING -> reg[a] = boxRef(unbox(reg[a]).toString());
                    case OpCodes.TYPE_OF -> reg[a] = boxRef(typeName(unbox(reg[a])));

                    case OpCodes.RETURN -> {
                        if(callStack.size() == 1)
                            throw new Bl0j_VM_Exception("return call for last stack frame");

                        Frame frame = callStack.pop();
                        callStack.peek().regs[frame.resultReg]  = reg[a];
                        addr = frame.addressToReturn;
                    }
                    case OpCodes.HALT -> {
                        return;
                    }
                    default -> throw new Bl0j_VM_Exception("Unknown opcode: " + opcode);
                }
                } catch (Exception e) {
                    throw new Bl0j_VM_Exception("Exception on address: "+addr/3+" - "+ e);
                }
            }
    }

    private void gen_frame(FunDef fun, long[] args, int addressToReturn, int resultReg) {
        long[] regs = new long[fun.regs()];

        regs[0] = NanBox.NIL;
        for (int i = 0; i < args.length; i++)
            regs[i+1] = args[i];

        callStack.push(new Frame(regs, addressToReturn, resultReg));
    }

    private long boxRef(Object value) {
        heap.add(value);
        return NanBox.ofRef(heap.size() - 1);
    }

    // converts a NaN-boxed register/const value into the plain Java object
    // it represents, for the (currently still Object-based) OperatorTable
    // and native methods to work with. Package-private so Bl0jArray can
    // unbox its own elements (e.g. for toString) without duplicating this.
    Object unbox(long bits) {
        if (!NanBox.isBoxed(bits))
            return Double.longBitsToDouble(bits);
        return switch (NanBox.tagOf(bits)) {
            case NanBox.TAG_INT -> NanBox.asInt(bits);
            case NanBox.TAG_BOOL -> NanBox.asBoolean(bits);
            case NanBox.TAG_NIL -> NIL_OBJECT;
            case NanBox.TAG_REF -> heap.get(NanBox.asRefIndex(bits));
            case NanBox.TAG_CHAR -> NanBox.asChar(bits);
            default -> throw new Bl0j_VM_Exception("unreachable NanBox tag");
        };
    }

    private long box(Object value) {
        if (value instanceof Integer i) return NanBox.ofInt(i);
        if (value instanceof Boolean b) return NanBox.ofBoolean(b);
        // Double.doubleToLongBits (not the raw variant) canonicalizes every
        // NaN to a single fixed pattern outside NanBox's reserved tag space,
        // so a genuine float NaN can never be mistaken for a boxed value
        if (value instanceof Double d) return Double.doubleToLongBits(d);
        if (value instanceof Character c) return NanBox.ofChar(c);
        if (value == NIL_OBJECT) return NanBox.NIL;
        return boxRef(value); // String, FunDef, ...
    }

    private String get_str(ByteBuffer bytes){
        int len = bytes.getShort() & 0xFFFF;
        byte[] strBytes = new byte[len];
        bytes.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8);
    }

    private record Frame(long[] regs, int addressToReturn, int resultReg) {}
}
