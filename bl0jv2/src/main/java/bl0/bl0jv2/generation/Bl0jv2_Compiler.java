package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.generation.nodes.BinaryNode;
import bl0.bl0jv2.data.generation.nodes.Node;
import bl0.bl0jv2.data.generation.nodes.NumberNode;
import bl0.bl0jv2.data.generation.nodes.unary.LUnaryNode;
import bl0.bl0jv2.data.generation.nodes.unary.RUnaryNode;
import bl0.bl0jv2.data.generation.nodes.unary.UnaryNode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Bl0jv2_Compiler {
    private List<Byte> bytecode = new ArrayList<>();
    private List<Object> constants = new ArrayList<>();
    private int regIndex = 0;

    public byte[] compile(Node node) {
        bytecode.clear();
        constants.clear();
        regIndex = 0;

        compileInner(node);

        emit(0xFE, 0, 0); // PRINT R0
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

            for (Object c : constants) {
                if (c instanceof Integer i) {
                    dos.writeByte(0x01);
                    dos.writeInt(i);
                } else if (c instanceof String s) {
                    dos.writeByte(0x02);
                    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                    dos.writeShort(bytes.length);
                    dos.write(bytes);
                }
            }

            for (byte b : bytecode) {
                dos.writeByte(b);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return out.toByteArray();
    }

    private int compileInner(Node node) {

        if (node instanceof NumberNode n) {
            int constIndex = constant(n.value);
            int reg = regIndex++;

            emit(0x01, reg, constIndex);
            return reg;
        }

        if (node instanceof BinaryNode n) {
            int left  = compileInner(n.left);
            int right = compileInner(n.right);

            byte op = switch (n.op) {
                case PLUS -> 0x02;
                case MINUS -> 0x03;
                case STAR -> 0x04;
                case DIV -> 0x05;
                default -> throw new RuntimeException("Unknown op: " + n.op);
            };

            emit(op, left, right);
            return left;
        }

        if(node instanceof UnaryNode u){
            int reg;

            byte op = switch (u.op){
                case MINUS -> 0x0E;
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

    private int constant(Object value) {
        constants.add(value);
        return constants.size() - 1;
    }
}
