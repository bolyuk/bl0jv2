package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.C;
import bl0.bl0jv2.data.Constants;
import bl0.bl0jv2.data.FunDef;
import bl0.bl0jv2.data.OpCodes;
import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.generation.nodes.BinaryNode;
import bl0.bl0jv2.generation.nodes.Node;
import bl0.bl0jv2.generation.nodes.ProgramNode;
import bl0.bl0jv2.generation.nodes.data.*;
import bl0.bl0jv2.generation.nodes.statements.*;
import bl0.bl0jv2.generation.nodes.unary.LUnaryNode;
import bl0.bl0jv2.generation.nodes.unary.RUnaryNode;
import bl0.bl0jv2.generation.nodes.unary.UnaryNode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Bl0jv2_Compiler {
    private final List<Byte> bytecode = new ArrayList<>();
    private final List<Object> constants = new ArrayList<>();
    private int regIndex = 0;
    private int regCount = 0;

    private final HashMap<String, Integer> identityMapping = new HashMap<>();
    private final List<FunNode> lazy_functions = new ArrayList<>();
    private final HashMap<String, Integer> functionMapping = new HashMap<>();
    public Bl0jv2_Compiler() {}

    public byte[] compile(Node node) {
        bytecode.clear();
        constants.clear();
        identityMapping.clear();
        lazy_functions.clear();
        functionMapping.clear();
        regIndex = 0;
        regCount = 0;

        if(!(node instanceof ProgramNode program))
            throw new Bl0j_CompilerException("node is not a ProgramNode");

        fetchFunctions(program);
        compileInner(program);
        _emit(OpCodes.HALT);
        compileFunctions();

        return build_header_bytecode();
    }

    private void fetchFunctions(ProgramNode program) {
        for(var node : program.nodes)
            if(node instanceof FunNode funNode){
                lazy_functions.add(funNode);
                int constIndex = constant(new FunDef(funNode.name, -1, (short)0, (short)0));
                functionMapping.put(funNode.name, constIndex);
            }
    }

    private void compileFunctions(){
        regCount = regIndex;
        int adress;
        int arity;

        for (var fun : lazy_functions) {
            identityMapping.clear();
            regIndex = 1; // first reg for return value

            adress = _instr_len();
            arity = fun.args.args.size();

            for (String arg : fun.args.args)
                map(arg);

            compileInner(fun.body);
            if(bytecode.get(bytecode.size()-3) != OpCodes.RETURN)
                _emit(OpCodes.RETURN, 0);

            int constIndex = functionMapping.get(fun.name);
            constants.set(constIndex, new FunDef(fun.name, adress,(short) arity ,(short) regIndex));;
        }

    }

    private int compileInner(Node node) {

        if(node instanceof ReturnNode returnNode){
            var reg = compileInner(returnNode.right);
            _emit(OpCodes.RETURN, reg);
            return reg;
        }

        if(node instanceof NativeCallNode nativeCallNode){
            int valReg = compileInner(nativeCallNode.right);

            _emit(OpCodes.CALL_NATIVE, nativeCallNode.id, valReg);

            return valReg;
        }

        if(node instanceof FunCall funCall){
            int[] valRegs = new int[funCall.args.size()];

            for(int i=0;i<funCall.args.size();i++) {
                valRegs[i] = compileInner(funCall.args.get(i));
            }

            int method = compileInner(funCall.left);

            int startReg = regIndex++;

            for(var val : valRegs) {
                _emit(OpCodes.MOV, regIndex, val);
                regIndex++;
            }

            _emit(OpCodes.CALL, method, startReg);
            return startReg;
        }

        if(node instanceof ProgramNode programNode){
            for(var n : programNode.nodes)
                compileInner(n);
            return -1;
        }

        if(node instanceof FunNode)
            return -1;


        if(node instanceof WhileNode whileNode){
            int startJump = _instr_len();
            int condReg = compileInner(whileNode.condition);

            int patchJumpIfNot = _emit(OpCodes.JUMP_IF_NOT, condReg) - 1;

            compileInner(whileNode.body); // -1

            _emit(OpCodes.JUMP, startJump);
            bytecode.set(patchJumpIfNot, _instr_len());

            return -1;
        }

        if(node instanceof Ternary_IfNode ternaryIfNode){
            int resultReg = regIndex++;
            int condReg = compileInner(ternaryIfNode.condition);

            int patchJumpIfNot = _emit(OpCodes.JUMP_IF_NOT, condReg) - 1;
            int bodyReg = compileInner(ternaryIfNode.body);

            _emit(OpCodes.MOV, resultReg, bodyReg);

            int patchJump = _emit(OpCodes.JUMP) - 2;
            bytecode.set(patchJumpIfNot, _instr_len());

            int elseReg = compileInner(ternaryIfNode.elseBody);

            _emit(OpCodes.MOV, resultReg, elseReg);
            bytecode.set(patchJump, _instr_len());

            return resultReg;
        }

        if (node instanceof IfNode ifNode) {
            int condReg = compileInner(ifNode.condition);

            int patchJumpIfNot = _emit(OpCodes.JUMP_IF_NOT, condReg) - 1;
            compileInner(ifNode.body);

            if (ifNode.elseBody != null) {

                int patchJump = _emit(OpCodes.JUMP) - 2;
                bytecode.set(patchJumpIfNot, _instr_len());

                compileInner(ifNode.elseBody);
                bytecode.set(patchJump, _instr_len());
            } else {
                bytecode.set(patchJumpIfNot, _instr_len());
            }

            return -1;
        }

        if(node instanceof DataNode){
            int constIndex = -1;

            if(node instanceof IdentityNode n){
                if (functionMapping.containsKey(n.name)) {
                    constIndex = functionMapping.get(n.name);
                    int reg = regIndex++;
                    _emit(OpCodes.LOAD_CONST, reg, constIndex);
                    return reg;
                }
                return map(n.name);
            }


            if (node instanceof NumberNode n)
                constIndex = constant(n.value);
            if(node instanceof StringNode s)
                constIndex = constant(s.value);
            if(node instanceof BooleanNode b)
                constIndex = constant(b.value);

            int reg = regIndex++;

            if(node instanceof NilNode)
                _emit(OpCodes.LOAD_NIL, reg);
             else
                _emit(OpCodes.LOAD_CONST, reg, constIndex);

            return reg;
        }

        if (node instanceof BinaryNode n) {

            if (n.op == Operator.ASSIGNMENT) {
                int varReg = compileInner(n.left);
                int valueReg = compileInner(n.right);
                _emit(OpCodes.MOV, varReg, valueReg);
                return varReg;
            }

            int result = regIndex++;
            int left  = compileInner(n.left);
            int right = compileInner(n.right);

            byte op = switch (n.op) {
                case PLUS -> OpCodes.LR_ADD;
                case MINUS -> OpCodes.LR_SUB;
                case STAR -> OpCodes.LR_MUL;
                case DIV -> OpCodes.LR_DIV;
                case REMAINDER ->  OpCodes.LR_REM;
                case EQUALS, NOT_EQUALS -> OpCodes.EQ;
                case LESS -> OpCodes.LESS;
                case GREATER -> OpCodes.GREATER;
                default -> throw new RuntimeException("Unknown op: " + n.op);
            };

            _emit(OpCodes.MOV, result, left);
            _emit(op, result, right);

            if(n.op == Operator.NOT_EQUALS)
                _emit(OpCodes.NOT, result);

            return result;
        }

        if(node instanceof UnaryNode u){
            int reg;

            if(node instanceof RUnaryNode rUnaryNode){
                reg = compileInner(rUnaryNode.right);

                int oneConst = constant(1);
                int tempReg = regIndex++;
                int tempRegToReturn = regIndex++;
                _emit(OpCodes.LOAD_CONST, tempReg, oneConst);

                byte op = switch (rUnaryNode.op){
                    case MINUS_MINUS -> OpCodes.LR_SUB;
                    case PLUS_PLUS -> OpCodes.LR_ADD;
                    default -> throw new Bl0j_CompilerException("Unknown op: " + u.op);
                };

                _emit(OpCodes.MOV, tempRegToReturn, reg);
                _emit(op, reg, tempReg);

                return tempRegToReturn;
            }

            if(node instanceof LUnaryNode l) {
                reg = compileInner(l.left);

                byte op = switch (u.op){
                    case MINUS -> OpCodes.NEG;
                    case NOT -> OpCodes.NOT;
                    default -> throw new Bl0j_CompilerException("Unknown op: " + u.op);
                };
                _emit(op, reg);

                return reg;
            }
        }

        throw new Bl0j_CompilerException("Unexpected node type: " + node);
    }

    private void  _emit(int op, int a, int b) {
        bytecode.add((byte) op);
        bytecode.add((byte) a);
        bytecode.add((byte) b);
    }

    private int _emit(int op, int a) {
        bytecode.add((byte) op);
        bytecode.add((byte) a);
        bytecode.add((byte)0x00);
        return bytecode.size();
    }

    private int _emit(int op) {
        bytecode.add((byte) op);
        bytecode.add((byte)0x00);
        bytecode.add((byte)0x00);
        return bytecode.size();
    }

    private byte _instr_len(){
        if(bytecode.size() % 3 != 0)
            throw new Bl0j_CompilerException("Invalid instruction len: " + bytecode.size());
        return (byte) (bytecode.size()/3);
    }

    private int map(String name){
       return identityMapping.computeIfAbsent(name, (ignored) -> regIndex++);
    }

    private int constant(Object value) {
        int index = constants.indexOf(value);
        if(index != -1)
            return index;

        constants.add(value);
        return constants.size() - 1;
    }


    private byte[] build_header_bytecode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        try {
            dos.writeInt(C.MAGIC);
            dos.writeShort(C.VERSION);
            dos.writeShort(constants.size());
            dos.writeShort(regCount);

            for (Object c : constants)
                switch (c) {
                    case Integer i -> {
                        dos.writeByte(Constants.INT);
                        dos.writeInt(i);
                    }
                    case String s -> {
                        dos.writeByte(Constants.STRING);
                        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                        dos.writeShort(bytes.length);
                        dos.write(bytes);
                    }
                    case Boolean b -> {
                        dos.writeByte(Constants.BOOL);
                        dos.writeBoolean(b);
                    }
                    case FunDef f -> {
                        dos.writeByte(Constants.FUN);
                        byte[] bytes = f.name().getBytes(StandardCharsets.UTF_8);
                        dos.writeShort(bytes.length);
                        dos.write(bytes);
                        dos.writeInt(f.address());
                        dos.writeShort(f.arity());
                        dos.writeShort(f.regs());
                    }
                    case Byte b -> {
                        dos.writeByte(Constants.BYTE);
                        dos.writeByte(b);
                    }
                    default -> throw new  Bl0j_CompilerException("unknown constant type - "+c.getClass().getName());
                }

            for (byte b : bytecode)
                dos.writeByte(b);

        } catch (Exception e) {
            throw new Bl0j_CompilerException("compilation error - "+e.getMessage());
        }

        return out.toByteArray();
    }
}
