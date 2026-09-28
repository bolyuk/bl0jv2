package bl0.bl0jv2.runtime;

import bl0.bl0jv2.data.*;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final long[] EMPTY_CELLS = new long[0];

    private Writer out;
    private BufferedReader stdin;

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

    // active try/catch handlers, innermost on top
    private final ArrayDeque<Handler> handlerStack = new ArrayDeque<>();

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
        // ignores its (dummy nil) input; CALL_NATIVE below writes whatever
        // this returns back into a register, which is what actually makes
        // read() usable as an expression
        nativeMethods.put(NativeMethods.READ, (ignored) -> readLine());

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
        addTable.add(String.class,  Bl0jTuple.class, (a, b) -> a.toString() + b.toString());
        addTable.add(Bl0jTuple.class, String.class,  (a, b) -> a.toString() + b.toString());
        addTable.add(String.class,  Bl0jError.class, (a, b) -> a.toString() + b.toString());
        addTable.add(Bl0jError.class, String.class,  (a, b) -> a.toString() + b.toString());
        addTable.add(String.class,  Bl0jInstance.class, (a, b) -> a.toString() + b.toString());
        addTable.add(Bl0jInstance.class, String.class,  (a, b) -> a.toString() + b.toString());
        // NIL_OBJECT's type is an anonymous class, so it's registered via
        // .getClass() here rather than a named Foo.class literal
        addTable.add(String.class,  NIL_OBJECT.getClass(), (a, b) -> a.toString() + b.toString());
        addTable.add(NIL_OBJECT.getClass(), String.class,  (a, b) -> a.toString() + b.toString());
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
    // implicit promotion already used by +, -, *, /, %, ** ; a class that
    // declares its own 'equals' method gets to decide for its own
    // instances; everything else falls back to plain value equality.
    // Package-private so Bl0jTuple can reuse it for its own (recursive)
    // content equality.
    static boolean valuesEqual(Object left, Object right) {
        if (isNumeric(left) && isNumeric(right))
            return toDouble(left) == toDouble(right);
        if (left instanceof Bl0jInstance li && li.cls.hasMethod("equals")) {
            Object result = li.owner.invoke(li.cls.method("equals"), li.owner.box(li), li.owner.box(right));
            return result instanceof Boolean b && b;
        }
        return Objects.equals(left, right);
    }

    private static Object negate(Object value) {
        if (value instanceof Integer i) return -i;
        if (value instanceof Double d) return -d;
        throw new Bl0j_VM_Exception("cannot negate " + value.getClass().getSimpleName());
    }

    private static int length(Object value) {
        if (value instanceof Bl0jArray arr) return arr.length();
        if (value instanceof Bl0jTuple t) return t.length();
        if (value instanceof String s) return s.length();
        throw new Bl0j_VM_Exception("cannot take length of " + value.getClass().getSimpleName());
    }

    private static Bl0jArray requireMutableArray(Object target) {
        if (target instanceof Bl0jTuple)
            throw new Bl0j_VM_Exception("cannot mutate a tuple");
        if (target instanceof Bl0jArray arr)
            return arr;
        throw new Bl0j_VM_Exception("expected an array, got " + target.getClass().getSimpleName());
    }

    private static int bitNot(Object value) {
        if (value instanceof Integer i) return ~i;
        throw new Bl0j_VM_Exception("cannot apply '~' to " + value.getClass().getSimpleName());
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
        if (value instanceof Bl0jTuple) return "tuple";
        if (value instanceof Bl0jError) return "err";
        if (value instanceof FunDef) return "function";
        if (value instanceof Bl0jClosure) return "function";
        if (value instanceof Bl0jClass) return "class";
        if (value instanceof Bl0jInstance instance) return instance.cls.name;
        if (value == NIL_OBJECT) return "nil";
        throw new Bl0j_VM_Exception("unknown type: " + value.getClass().getSimpleName());
    }

    private String readLine() {
        if (stdin == null)
            stdin = new BufferedReader(new InputStreamReader(System.in));
        try {
            return stdin.readLine(); // null on EOF
        } catch (IOException e) {
            throw new Bl0j_VM_Exception("read failed: " + e.getMessage());
        }
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
        handlerStack.clear();
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
                        bytes.getInt() & 0xFFFF,
                        bytes.getShort(),
                        bytes.getShort()));
                case Constants.BYTE -> consts[i] = NanBox.ofInt(bytes.get());
                case Constants.FLOAT -> consts[i] = Double.doubleToLongBits(bytes.getDouble());
                // methods are always registered (and thus loaded) before
                // the class itself, so consts[methodConstIdx] is already
                // populated whenever we get here - see ClassDef's javadoc
                case Constants.CLASS -> {
                    String className = get_str(bytes);
                    int fieldCount = bytes.getShort() & 0xFFFF;
                    List<String> fieldNames = new ArrayList<>();
                    long[] fieldDefaults = new long[fieldCount];
                    Arrays.fill(fieldDefaults, NanBox.NIL);
                    for (int f = 0; f < fieldCount; f++) {
                        fieldNames.add(get_str(bytes));
                        if (bytes.get() != 0) // hasDefault
                            fieldDefaults[f] = consts[bytes.getShort() & 0xFFFF];
                    }
                    int methodCount = bytes.getShort() & 0xFFFF;
                    Map<String, FunDef> methods = new HashMap<>();
                    for (int m = 0; m < methodCount; m++) {
                        String methodName = get_str(bytes);
                        int methodConstIdx = bytes.getShort() & 0xFFFF;
                        methods.put(methodName, (FunDef) unbox(consts[methodConstIdx]));
                    }
                    int staticFieldCount = bytes.getShort() & 0xFFFF;
                    consts[i] = boxRef(new Bl0jClass(className, fieldNames, fieldDefaults, methods, staticFieldCount));
                }
                default -> throw new Bl0j_VM_Exception("Unknown const type: " + type);
            }
        }

        int remaining = bytes.remaining();

        if(remaining % C.INSTR_WIDTH != 0)
            throw new Bl0j_VM_Exception("wrong amount of instructions");

        instructions = new byte[remaining];
        bytes.get(instructions);
    }

    public void set_out_writer(Writer out){
        this.out = out;
    }

    public void run_instructions() throws IOException {
        execute(0, -1);
    }

    // synchronously calls a bl0jv2 function from native Java code (used by
    // a class's own toString()/equals() override, which native code paths
    // like Bl0jInstance.toString() or the == operator can't otherwise
    // reach) and returns its unboxed result. Pushes one frame and runs
    // until exactly that frame returns, rather than until HALT.
    Object invoke(FunDef fun, long... args) {
        int stopAtDepth = callStack.size();
        long[] regs = new long[fun.regs()];
        for (int i = 0; i < args.length; i++)
            regs[i + 1] = args[i];
        // resultReg=0 is safe to reuse here: every frame's own reg[0] is
        // never assigned to a real variable by the compiler (regIndex
        // starts at 1), so it's free scratch space for exactly this
        callStack.push(new Frame(regs, -1, 0));
        try {
            execute(fun.address() * C.INSTR_WIDTH, stopAtDepth);
        } catch (IOException e) {
            throw new Bl0j_VM_Exception("invoke failed: " + e.getMessage());
        }
        return unbox(callStack.peek().regs()[0]);
    }

    // startAddr/stopAtDepth let invoke() re-enter this same loop for a
    // synchronous nested call: run_instructions() calls this with
    // stopAtDepth=-1 (run to HALT); invoke() passes the depth its own
    // pushed frame will pop back to, so execution returns to Java once
    // that one frame's RETURN runs, without disturbing the enclosing call.
    private void execute(int startAddr, int stopAtDepth) throws IOException {

            for(int addr = startAddr; addr < instructions.length;){
                try {
                byte opcode = (byte) (instructions[addr] & 0xFF);

                int a = ((instructions[addr+1] & 0xFF) << 8) | (instructions[addr+2] & 0xFF);
                int b = ((instructions[addr+3] & 0xFF) << 8) | (instructions[addr+4] & 0xFF);
                addr += C.INSTR_WIDTH;

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

                    case OpCodes.JUMP -> addr = a * C.INSTR_WIDTH;
                    case OpCodes.JUMP_IF -> { if ( (boolean) unbox(reg[a])) addr = b * C.INSTR_WIDTH; }
                    case OpCodes.JUMP_IF_NOT -> { if (!(boolean) unbox(reg[a])) addr = b * C.INSTR_WIDTH; }

                    case OpCodes.EQ -> reg[a] = NanBox.ofBoolean(valuesEqual(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LESS -> reg[a] = NanBox.ofBoolean(toDouble(unbox(reg[a])) < toDouble(unbox(reg[b])));
                    case OpCodes.GREATER  -> reg[a] = NanBox.ofBoolean(toDouble(unbox(reg[a])) > toDouble(unbox(reg[b])));
                    case OpCodes.NOT -> reg[a] = NanBox.ofBoolean(!(boolean) unbox(reg[a]));

                    case OpCodes.MOV -> reg[a] = reg[b];
                    case OpCodes.SET -> reg[a] = NanBox.ofInt(b);
                    case OpCodes.NEG  -> reg[a] = box(negate(unbox(reg[a])));

                    // reg[a] holds either a plain FunDef (an ordinary named
                    // function, called directly) or a Bl0jClosure (a
                    // lambda) - a closure's own captured cells are
                    // prepended before the caller's own args, landing in
                    // exactly the leading parameter slots the compiler
                    // reserved for them (see Bl0jv2_Compiler's LambdaNode
                    // handling)
                    case OpCodes.CALL -> {
                        Object callee = unbox(reg[a]);
                        FunDef fun;
                        long[] capturedCells;
                        if (callee instanceof Bl0jClosure closure) {
                            fun = closure.funDef();
                            capturedCells = closure.capturedCells();
                        } else {
                            fun = (FunDef) callee;
                            capturedCells = EMPTY_CELLS;
                        }

                        long[] args = new long[fun.arity()];
                        System.arraycopy(capturedCells, 0, args, 0, capturedCells.length);
                        for (int i = capturedCells.length; i < fun.arity(); i++)
                            args[i] = reg[b + 1 + (i - capturedCells.length)];

                        gen_frame(fun, args, addr, b);
                        addr = fun.address() * C.INSTR_WIDTH;
                    }

                    // writes the native function's own return value back
                    // into its operand register - harmless for print/
                    // println/wait (their status code lands somewhere
                    // nothing reads, since they're only ever used as bare
                    // statements), and what makes a value-producing native
                    // like read() usable as an expression at all
                    case OpCodes.CALL_NATIVE -> {
                        var nativeFun = nativeMethods.get((byte) a);
                        if (nativeFun == null)
                            throw new Bl0j_VM_Exception("unknown native method: " + a);
                        Object result = nativeFun.apply(unbox(reg[b]));
                        if (result instanceof Integer code && code == -1)
                            throw new Bl0j_VM_Exception("native method " + a + " returned error");
                        reg[b] = result == null ? NanBox.NIL : box(result);
                    }

                    // elements sit at reg[a+1 .. a+b], mirroring CALL's
                    // args-adjacent-to-the-base-register convention
                    case OpCodes.NEW_ARRAY -> {
                        int count = b;
                        long[] elements = new long[count];
                        for (int i = 0; i < count; i++) elements[i] = reg[a + 1 + i];
                        reg[a] = boxRef(new Bl0jArray(elements, this));
                    }

                    case OpCodes.NEW_TUPLE -> {
                        int count = b;
                        long[] elements = new long[count];
                        for (int i = 0; i < count; i++) elements[i] = reg[a + 1 + i];
                        reg[a] = boxRef(new Bl0jTuple(elements, this));
                    }

                    case OpCodes.INDEX_GET -> {
                        Object target = unbox(reg[a]);
                        int index = (int) unbox(reg[b]);
                        reg[a] = switch (target) {
                            case Bl0jArray array -> array.getRaw(index);
                            case Bl0jTuple tuple -> tuple.getRaw(index);
                            case String s -> NanBox.ofChar(charAt(s, index));
                            default -> throw new Bl0j_VM_Exception("cannot index " + target.getClass().getSimpleName());
                        };
                    }

                    // index and value sit at reg[b] and reg[b+1]
                    case OpCodes.INDEX_SET -> {
                        Bl0jArray array = requireMutableArray(unbox(reg[a]));
                        int index = (int) unbox(reg[b]);
                        array.setRaw(index, reg[b + 1]);
                    }

                    case OpCodes.LENGTH -> reg[a] = NanBox.ofInt(length(unbox(reg[a])));

                    // mutates the Bl0jArray object the reference points at,
                    // not the register holding that reference - reg[a]
                    // (the array's own slot) is never overwritten
                    case OpCodes.PUSH -> requireMutableArray(unbox(reg[a])).push(reg[b]);
                    case OpCodes.POP -> reg[a] = requireMutableArray(unbox(reg[b])).pop();

                    // count consecutive elements land in reg[a+1 .. a+count],
                    // mirroring NEW_ARRAY/NEW_TUPLE's own convention
                    case OpCodes.UNPACK -> {
                        Object target = unbox(reg[a]);
                        int count = b;
                        int len = length(target);
                        if (len != count)
                            throw new Bl0j_VM_Exception("cannot unpack " + len + " values into " + count + " targets");
                        for (int i = 0; i < count; i++)
                            reg[a + 1 + i] = switch (target) {
                                case Bl0jArray arr -> arr.getRaw(i);
                                case Bl0jTuple tup -> tup.getRaw(i);
                                default -> throw new Bl0j_VM_Exception("cannot unpack " + target.getClass().getSimpleName());
                            };
                    }

                    case OpCodes.TO_INT -> reg[a] = box(toInt(unbox(reg[a])));
                    case OpCodes.TO_FLOAT -> reg[a] = box(toFloat(unbox(reg[a])));
                    case OpCodes.TO_STRING -> reg[a] = boxRef(unbox(reg[a]).toString());
                    case OpCodes.TYPE_OF -> reg[a] = boxRef(typeName(unbox(reg[a])));

                    // b holds the catch block's address (patched by the
                    // compiler), a the register the caught error lands in
                    case OpCodes.TRY_ENTER -> handlerStack.push(new Handler(b * C.INSTR_WIDTH, a, callStack.size()));
                    case OpCodes.TRY_EXIT -> handlerStack.pop();
                    case OpCodes.MAKE_ERR -> reg[a] = boxRef(new Bl0jError(String.valueOf(unbox(reg[a]))));

                    // mutates a's own slot: class-ref in, instance-ref out
                    case OpCodes.NEW_INSTANCE -> reg[a] = boxRef(new Bl0jInstance((Bl0jClass) unbox(reg[a]), this));

                    case OpCodes.GET_FIELD -> {
                        Bl0jInstance instance = (Bl0jInstance) unbox(reg[a]);
                        String name = (String) unbox(consts[b]);
                        reg[a] = instance.getFieldRaw(name);
                    }

                    // field name's const index and the value sit at reg[b]
                    // and reg[b+1], same packing trick as INDEX_SET
                    case OpCodes.SET_FIELD -> {
                        Bl0jInstance instance = (Bl0jInstance) unbox(reg[a]);
                        String name = (String) unbox(consts[(int) unbox(reg[b])]);
                        instance.setFieldRaw(name, reg[b + 1]);
                    }

                    // b is the static field's own index, resolved at
                    // compile time - class-ref in, value out
                    case OpCodes.GET_STATIC_FIELD -> {
                        Bl0jClass cls = (Bl0jClass) unbox(reg[a]);
                        reg[a] = cls.getStaticFieldRaw(b);
                    }

                    // the field index and the value sit at reg[b] and
                    // reg[b+1], same packing trick as SET_FIELD
                    case OpCodes.SET_STATIC_FIELD -> {
                        Bl0jClass cls = (Bl0jClass) unbox(reg[a]);
                        int fieldIndex = (int) unbox(reg[b]);
                        cls.setStaticFieldRaw(fieldIndex, reg[b + 1]);
                    }

                    // mutates a's own slot: object in, resolved FunDef out
                    case OpCodes.LOOKUP_METHOD -> {
                        Bl0jInstance instance = (Bl0jInstance) unbox(reg[a]);
                        String name = (String) unbox(consts[b]);
                        reg[a] = box(instance.cls.method(name));
                    }

                    case OpCodes.MAKE_CELL -> reg[a] = boxRef(new Bl0jCell());

                    // mutates a's own slot: cell-ref in, its current value out
                    case OpCodes.CELL_GET -> reg[a] = ((Bl0jCell) unbox(reg[a])).value;

                    case OpCodes.CELL_SET -> ((Bl0jCell) unbox(reg[a])).value = reg[b];

                    // a's own slot already holds the lambda's FunDef (from
                    // a prior LOAD_CONST); the captured cells' own
                    // references sit at reg[a+1..a+b], mirroring
                    // NEW_ARRAY/NEW_TUPLE's convention
                    case OpCodes.MAKE_CLOSURE -> {
                        FunDef fun = (FunDef) unbox(reg[a]);
                        long[] cells = new long[b];
                        for (int i = 0; i < b; i++) cells[i] = reg[a + 1 + i];
                        reg[a] = boxRef(new Bl0jClosure(fun, cells));
                    }

                    case OpCodes.RETURN -> {
                        if(callStack.size() == 1)
                            throw new Bl0j_VM_Exception("return call for last stack frame");

                        // any handler registered inside the frame being
                        // returned from goes out of scope with it, exactly
                        // like it would on an exception unwinding past it
                        while (!handlerStack.isEmpty() && handlerStack.peek().callStackDepth() >= callStack.size())
                            handlerStack.pop();

                        Frame frame = callStack.pop();
                        callStack.peek().regs[frame.resultReg]  = reg[a];
                        addr = frame.addressToReturn;

                        // the frame invoke() pushed has just returned -
                        // hand control back to the native Java caller
                        // instead of continuing to interpret whatever
                        // bytecode happens to sit at addressToReturn
                        if (stopAtDepth >= 0 && callStack.size() == stopAtDepth)
                            return;
                    }
                    case OpCodes.HALT -> {
                        return;
                    }
                    default -> throw new Bl0j_VM_Exception("Unknown opcode: " + opcode);
                }
                } catch (Exception e) {
                    // a handler registered before this execute() call
                    // started (i.e. outside a nested invoke()) doesn't
                    // belong to it - let the exception propagate to the
                    // enclosing execute() call instead of catching it here
                    // with a callStack/handlerStack state this call isn't
                    // entitled to unwind
                    boolean handlerIsInThisCall = !handlerStack.isEmpty()
                            && (stopAtDepth < 0 || handlerStack.peek().callStackDepth() > stopAtDepth);

                    if (handlerIsInThisCall) {
                        Handler handler = handlerStack.pop();
                        while (callStack.size() > handler.callStackDepth())
                            callStack.pop();
                        String message = e.getMessage() != null ? e.getMessage() : e.toString();
                        callStack.peek().regs()[handler.errReg()] = box(new Bl0jError(message));
                        addr = handler.catchAddr();
                        continue;
                    }
                    throw new Bl0j_VM_Exception("Exception on address: "+addr/C.INSTR_WIDTH+" - "+ e);
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

    // package-private so Bl0jv2_jVM.valuesEqual can box an instance/its
    // 'other' operand before passing them into a user-defined equals()
    long box(Object value) {
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

    // callStackDepth is callStack.size() at the moment TRY_ENTER ran, so a
    // RETURN that unwinds past this depth knows the handler no longer
    // applies (see the RETURN and exception-catch cases below)
    private record Handler(int catchAddr, int errReg, int callStackDepth) {}
}
