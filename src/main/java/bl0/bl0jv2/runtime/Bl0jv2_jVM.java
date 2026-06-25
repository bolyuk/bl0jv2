package bl0.bl0jv2.runtime;

import bl0.bl0jv2.data.*;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class Bl0jv2_jVM {

    public static final Object NIL = new Object() {
        @Override
        public String toString() { return "nil"; }
    };

    private Writer out;

    private final OperatorTable addTable = new OperatorTable();
    private final OperatorTable subTable = new OperatorTable();
    private final OperatorTable divTable = new OperatorTable();
    private final OperatorTable mulTable = new OperatorTable();
    private final OperatorTable remTable = new OperatorTable();

    private ArrayDeque<Frame> callStack = new ArrayDeque<>();
    private Map<Byte, Function<Object, Object>> nativeMethods = new HashMap<>();
    private Object[] consts;
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
        addTable.add(String.class,  Integer.class, (a, b) -> a.toString() + b.toString());
        addTable.add(String.class,  Boolean.class, (a, b) -> a.toString() + b.toString());

        subTable.add(Integer.class, Integer.class, (a, b) -> (int)a - (int)b);

        mulTable.add(Integer.class, Integer.class, (a, b) -> (int)a * (int)b);
        mulTable.add(String.class,  Integer.class, (a, b) -> a.toString().repeat((int)b));

        divTable.add(Integer.class, Integer.class, (a, b) -> {
            if ((int)b == 0) throw new Bl0j_VM_Exception("division by zero");
            return (int)a / (int)b;
        });

        remTable.add(Integer.class, Integer.class, (a, b) -> {
            if ((int)b == 0) throw new Bl0j_VM_Exception("division by zero");
            return (int)a % (int)b;
        });
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
        callStack.add(new Frame(new Object[registers_length], -1, -1));
        consts = new Object[constants_length];

        for (int i = 0; i < constants_length; i++) {
            byte type = bytes.get();
            switch (type) {
                case Constants.INT -> consts[i] = bytes.getInt();
                case Constants.STRING -> consts[i] = get_str(bytes);
                case Constants.BOOL -> consts[i] = bytes.get() != 0;
                case Constants.FUN -> consts[i] = new FunDef(
                        get_str(bytes),
                        bytes.getInt() & 0xFF,
                        bytes.getShort(),
                        bytes.getShort());
                case Constants.BYTE -> consts[i] = bytes.get();
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

                Object[] reg = callStack.peek().regs();

                switch (opcode) {
                    case OpCodes.LOAD_NIL -> reg[a] = NIL;
                    case OpCodes.LOAD_CONST -> reg[a] = consts[b];

                    case OpCodes.LR_ADD -> reg[a] = addTable.calculate(reg[a], reg[b]);
                    case OpCodes.LR_SUB -> reg[a] = subTable.calculate(reg[a], reg[b]);
                    case OpCodes.LR_MUL -> reg[a] = mulTable.calculate(reg[a], reg[b]);
                    case OpCodes.LR_DIV -> reg[a] = divTable.calculate(reg[a], reg[b]);
                    case OpCodes.LR_REM -> reg[a] = remTable.calculate(reg[a], reg[b]);

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

                    case OpCodes.CALL -> {
                        FunDef fun = (FunDef) reg[a];
                        Object[] args = new Object[fun.arity()];

                        for (int i = 0; i < fun.arity(); i++)
                            args[i] = reg[b + 1 + i];

                        gen_frame(fun, args, addr, b);
                        addr = fun.address() * 3;
                    }

                    case OpCodes.CALL_NATIVE -> {
                        var nativeFun = nativeMethods.get((byte) a);
                        if (nativeFun == null)
                            throw new Bl0j_VM_Exception("unknown native method: " + a);
                        Object result = nativeFun.apply(reg[b]);
                        if (result instanceof Integer code && code == -1)
                            throw new Bl0j_VM_Exception("native method " + a + " returned error");
                    }

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

    private void gen_frame(FunDef fun, Object[] args, int addressToReturn, int resultReg) {
        Object[] regs = new Object[fun.regs()];

        regs[0] = NIL;
        for (int i = 0; i < args.length; i++)
            regs[i+1] = args[i];

        callStack.push(new Frame(regs, addressToReturn, resultReg));
    }

    private String get_str(ByteBuffer bytes){
        int len = bytes.getShort() & 0xFFFF;
        byte[] strBytes = new byte[len];
        bytes.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8);
    }

    private record Frame(Object[] regs, int addressToReturn, int resultReg) {}
}

