package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.generation.nodes.BinaryNode;
import bl0.bl0jv2.data.generation.nodes.Node;
import bl0.bl0jv2.data.exceptions.Bl0j_ParserException;
import bl0.bl0jv2.data.generation.nodes.ProgramNode;
import bl0.bl0jv2.data.generation.nodes.data.*;
import bl0.bl0jv2.data.generation.nodes.statements.IfNode;
import bl0.bl0jv2.data.generation.nodes.statements.Ternary_IfNode;
import bl0.bl0jv2.data.generation.nodes.unary.LUnaryNode;
import bl0.bl0jv2.data.generation.tokens.*;
import bl0.bl0jv2.data.generation.tokens.blocks.LBraceToken;
import bl0.bl0jv2.data.generation.tokens.blocks.RBraceToken;
import bl0.bl0jv2.data.generation.tokens.data.*;
import bl0.bl0jv2.data.generation.tokens.blocks.LParenToken;
import bl0.bl0jv2.data.generation.tokens.blocks.RParenToken;
import bl0.bl0jv2.data.generation.tokens.statements.*;

import java.util.ArrayList;
import java.util.List;

public class Bl0jv2_Parser {
    private List<Token> tokens;
    private int pos;

    // program    = statement*
    // statement  = if | while | return | sysCall | assign ';' TODO
    // sysCall    = ('print' | 'read' | 'wait') assign ';'     TODO
    // return     = 'return' assign? ';'                       TODO
    // if         = 'if' '('? assign ')'? block ('else' block)?
    // block      = '{' statement* '}' | statement
    // assign     = IDENT '=' assign | ternary
    // ternary    = equality ('?' ternary ':' ternary)?
    // equality   = comparison (('==' | '!=') comparison)*
    // comparison = addSub (('<' | '>' | '<=' | '>=') addSub)*
    // addSub     = multiplyDivide (('+' | '-') multiplyDivide)*
    // multiplyDivide = unary (('*' | '/') unary)*
    // unary      = ('-' | '!') unary | data
    // data       = NUMBER | IDENT | '(' assign ')'
    public Node getAST(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
        return program();
    }

    private Node program() {
        List<Node> stmts = new ArrayList<>();

        while (!(peek() instanceof EOFToken)) {
            stmts.add(statement());
        }

        return new ProgramNode(stmts);
    }

    private Node statement(){

        if(peek() instanceof OpToken op && op.op == Op.PRINT){
            pos++; // consume
            Node operand = assign();

            if (peek() instanceof SemicolonToken)
                pos++; // consume ;

            return new LUnaryNode(op.op, operand);
        }

        if(peek() instanceof IfToken){
            pos++; // consume
            return If();
        }

        Node expr = assign();

        if (peek() instanceof SemicolonToken) {
            pos++;
        }

        return expr;
    }

    private Node If(){
        Token t = peek();

        Node condition;
        Node body;
        Node elseBody = null;

        if(t instanceof LParenToken){
            pos++;
            var node = assign();
            Token closing = peek();
            if (!(closing instanceof RParenToken))
                throw new Bl0j_ParserException(closing.line, closing.line_index, "expected ')'");
            pos++;
            condition = node;
        } else
            condition = statement();

        body = block();

        if(peek() instanceof ElseToken) {
            pos++;
            elseBody = block();
        }

        return new IfNode(condition, body, elseBody);
    }

    private Node block(){
        if(peek() instanceof LBraceToken) {
            pos++; // consume
            List<Node> stmts = new ArrayList<>();

            while (!(peek() instanceof RBraceToken)) {
                stmts.add(statement());
            }
            pos++;
            return new ProgramNode(stmts);
        } else {
            Node left = statement();
            if (peek() instanceof SemicolonToken)
                pos++; // consume ;
            return left;
        }
    }

    private Node assign(){
        if(peek() instanceof IdentityToken &&
           peek(1) instanceof OpToken op &&
        op.op == Op.ASSIGNMENT){
            Node left = data();
            pos++; // "=" consumed
            Node right = assign();
            return new BinaryNode(left, op.op, right);
        }
        return ternary();
    }

    private Node ternary(){
        Node left = equality();
        if(peek() instanceof Ternary_IfToken){
            pos++; // consume
            var body = ternary();
            if(!(peek() instanceof Ternary_ElseToken))
                throw new Bl0j_ParserException(peek().line, peek().line_index, "expected ':' for ternary if");
            pos++; // consume :
            var elseBody = ternary();
            return new Ternary_IfNode(left, body, elseBody);
        }
        return left;
    }

    private Node equality(){
        Node left = comparison();

        while (pos < tokens.size()) {
            Token t = peek();
            if (t instanceof OpToken op && (op.op == Op.EQUALS || op.op == Op.NOT_EQUALS)) {
                pos++; // consume
                Node right = comparison();
                left = new BinaryNode(left, op.op, right);
            } else break;
        }

        return left;
    }

    private Node comparison(){
        Node left = addSub();

        while (pos < tokens.size()) {
            Token t = peek();
            if (t instanceof OpToken op && (op.op == Op.LESS || op.op == Op.LESS_EQUALS ||
                    op.op == Op.GREATER || op.op == Op.GREATER_EQUALS)) {
                pos++; // consume
                Node right = addSub();
                left = new BinaryNode(left, op.op, right);
            } else break;
        }

        return left;
    }

    private Node addSub() {
        Node left = multiplyDivide();

        while (pos < tokens.size()) {
            Token t = peek();
            if (t instanceof OpToken op && (op.op == Op.PLUS || op.op == Op.MINUS)) {
                pos++; // consume
                Node right = multiplyDivide();
                left = new BinaryNode(left, op.op, right);
            } else break;
        }

        return left;
    }

    private Node multiplyDivide() {
        Node left = unary();

        while (pos < tokens.size()) {
            Token t = peek();
            if (t instanceof OpToken op && (op.op == Op.STAR || op.op == Op.DIV)) {
                pos++; // consume
                Node right = unary();
                left = new BinaryNode(left, op.op, right);
            } else break;
        }

        return left;
    }

    private Node unary() {
        Token t = peek();
        if (t instanceof OpToken op &&
                (op.op == Op.MINUS || op.op == Op.NOT)) {
            pos++; // consume
            Node operand = unary();
            return new LUnaryNode(op.op, operand);
        }
        return data();
    }

    private Node data() {
        Token t = peek();
        if (t instanceof NumberToken numberToken) {
            pos++;
            return new NumberNode(Integer.parseInt(numberToken.value));
        }

        if(t instanceof StringToken stringToken) {
            pos++;
            return new StringNode(stringToken.value);
        }

        if(t instanceof NilToken){
            pos++;
            return new NilNode();
        }

        if(t instanceof BooleanToken identityToken){
            pos++;
            return new BooleanNode(identityToken.value);
        }

        if(t instanceof IdentityToken identityToken){
            pos++;
            return new IdentityNode(identityToken.name);
        }

        if(t instanceof LParenToken){
            pos++;
            var node = assign();
            Token closing = peek();
            if (!(closing instanceof RParenToken))
                throw new Bl0j_ParserException(closing.line, closing.line_index, "expected ')'");
            pos++;
            return node;
        }

        throw new Bl0j_ParserException(t.line, t.line_index, "unexpected token - "+t);
    }

    private Token peek() {
        if (pos >= tokens.size())
            throw new Bl0j_ParserException(-1, -1, "unexpected end of input");
        return tokens.get(pos);
    }

    private Token peek(int extra) {
        if (pos+extra >= tokens.size())
            throw new Bl0j_ParserException(-1, -1, "unexpected end of input");
        return tokens.get(pos+extra);
    }
}
