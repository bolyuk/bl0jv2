package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.exceptions.Bl0j_ParserException;
import bl0.bl0jv2.data.generation.nodes.BinaryNode;
import bl0.bl0jv2.data.generation.nodes.Node;
import bl0.bl0jv2.data.generation.nodes.ProgramNode;
import bl0.bl0jv2.data.generation.nodes.data.*;
import bl0.bl0jv2.data.generation.nodes.statements.IfNode;
import bl0.bl0jv2.data.generation.nodes.statements.Ternary_IfNode;
import bl0.bl0jv2.data.generation.nodes.statements.WhileNode;
import bl0.bl0jv2.data.generation.nodes.unary.LUnaryNode;
import bl0.bl0jv2.data.generation.nodes.unary.UnaryNode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Bl0jv2_Compiler {
    private final List<Byte> bytecode = new ArrayList<>();
    private final List<Object> constants = new ArrayList<>();
    private int regIndex = 0;

    private final HashMap<String, Integer> identityMapping = new HashMap<>();
    public Bl0jv2_Compiler() {}

    public byte[] compile(Node node) {
        bytecode.clear();
        constants.clear();
        identityMapping.clear();
        regIndex = 0;

        if(!(node instanceof ProgramNode program))
            throw new IllegalArgumentException("node is not a ProgramNode");

        compileInner(program);

        emit(0xFF, 0, 0); // HALT

        return buildBytecode();
    }

    private byte[] buildBytecode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        try {
                dos.writeInt(0x426C306A);
                dos.writeShort(1);
                dos.writeShort(constants.size());
                dos.writeShort(regIndex);

                for (Object c : constants)
                    switch (c) {
                        case Integer i -> {
                            dos.writeByte(0x01);
                            dos.writeInt(i);
                        }
                        case String s -> {
                            dos.writeByte(0x02);
                            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                            dos.writeShort(bytes.length);
                            dos.write(bytes);
                        }
                        case Boolean b -> {
                            dos.writeByte(0x03);
                            dos.writeBoolean(b);
                        }
                        default -> {
                        }
                    }

                for (byte b : bytecode)
                    dos.writeByte(b);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return out.toByteArray();
    }

    private int compileInner(Node node) {

        if(node instanceof ProgramNode programNode){
            for(var n : programNode.nodes)
                compileInner(n);
            return -1;
        }

        if(node instanceof WhileNode whileNode){
            int startJump = bytecode.size() / 3;
            int condReg = compileInner(whileNode.condition);

            emit(0x08, condReg, 0);
            int patchJumpIfNot = bytecode.size() - 1;
            compileInner(whileNode.body);
            emit(0x06, startJump, 0);
            bytecode.set(patchJumpIfNot,(byte)(bytecode.size() / 3));
            return -1;
        }

        if(node instanceof Ternary_IfNode ternaryIfNode){
            int resultReg = regIndex++;

            int condReg = compileInner(ternaryIfNode.condition);
            emit(0x08, condReg, 0);
            int patchJumpIfNot = bytecode.size() - 1;

            int bodyReg = compileInner(ternaryIfNode.body);
            emit(0x0C, resultReg, bodyReg);  // MOV resultReg = bodyReg

            emit(0x06, 0, 0);
            int patchJump = bytecode.size() - 2;
            bytecode.set(patchJumpIfNot, (byte)(bytecode.size() / 3));

            int elseReg = compileInner(ternaryIfNode.elseBody);
            emit(0x0C, resultReg, elseReg);  // MOV resultReg = elseReg

            bytecode.set(patchJump, (byte)(bytecode.size() / 3));

            return resultReg;
        }

        if (node instanceof IfNode ifNode) {
            int condReg = compileInner(ifNode.condition);
            emit(0x08, condReg, 0);  // JUMP_IF_NOT
            int patchJumpIfNot = bytecode.size() - 1;

            compileInner(ifNode.body);

            if (ifNode.elseBody != null) {
                emit(0x06, 0, 0); // JUMP
                int patchJump = bytecode.size() - 2;
                bytecode.set(patchJumpIfNot, (byte)(bytecode.size() / 3));
                compileInner(ifNode.elseBody);
                bytecode.set(patchJump, (byte)(bytecode.size() / 3));
            } else {
                bytecode.set(patchJumpIfNot, (byte)(bytecode.size() / 3));
            }

            return -1;
        }

        if(node instanceof DataNode){
            int constIndex = -1;

            if(node instanceof IdentityNode n)
                return map(n.name);

            if (node instanceof NumberNode n)
                constIndex = constant(n.value);
            if(node instanceof StringNode s)
                constIndex = constant(s.value);
            if(node instanceof BooleanNode b)
                constIndex = constant(b.value);

            int reg = regIndex++;

            if(node instanceof NilNode){
                emit(0x00, reg, 0);
            } else {
                emit(0x01, reg, constIndex);
            }
            return reg;
        }

        if (node instanceof BinaryNode n) {

            if (n.op == Op.ASSIGNMENT) {
                int varReg = compileInner(n.left);
                int valueReg = compileInner(n.right);
                emit(0x0C, varReg, valueReg);
                return varReg;
            }

            int result = regIndex++;
            int left  = compileInner(n.left);
            int right = compileInner(n.right);

            byte op = switch (n.op) {
                case PLUS -> 0x02;
                case MINUS -> 0x03;
                case STAR -> 0x04;
                case DIV -> 0x05;
                case EQUALS, NOT_EQUALS -> 0x09;
                case LESS -> 0x0A;
                case GREATER -> 0x0B;
                default -> throw new RuntimeException("Unknown op: " + n.op);
            };

            emit(0x0c, result, left);
            emit(op, result, right);

            if(n.op == Op.NOT_EQUALS)
                emit(0x0F, result, 0);

            return result;
        }

        if(node instanceof UnaryNode u){
            int reg;

            byte op = (byte) switch (u.op){
                case MINUS -> 0x0E;
                case NOT -> 0x0F;
                case PRINT -> 0xFE;
                default -> throw new RuntimeException("Unknown op: " + u.op);
            };

            if(node instanceof LUnaryNode l) {
                reg = compileInner(l.left);
                emit(op, reg , 0x00);
                return reg;
            }
        }

        throw new RuntimeException("Unexpected node type: " + node);
    }

    private void emit(int op, int a, int b) {
        bytecode.add((byte) op);
        bytecode.add((byte) a);
        bytecode.add((byte) b);
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
}
