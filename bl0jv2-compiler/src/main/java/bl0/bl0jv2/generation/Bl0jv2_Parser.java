package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.Op;
import bl0.bl0jv2.data.generation.nodes.BinaryNode;
import bl0.bl0jv2.data.generation.nodes.Node;
import bl0.bl0jv2.data.exceptions.Bl0j_ParserException;
import bl0.bl0jv2.data.generation.nodes.ProgramNode;
import bl0.bl0jv2.data.generation.nodes.data.*;
import bl0.bl0jv2.data.generation.nodes.statements.*;
import bl0.bl0jv2.data.generation.nodes.unary.LUnaryNode;
import bl0.bl0jv2.data.generation.nodes.unary.RUnaryNode;
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

    // program        = statement*
    // statement      = if | fun | while | sysCall | assign ';'
    // fun            = 'fun' IDENT funcBody block
    // lambda         = funcBody  '->' (assign | block)                        TODO (later)
    // argBody        = '(' (IDENT (',' IDENT)*)? ')'
    // if             = 'if' condition block ('else' block)?
    // while          = 'while' condition block
    // block          = '{' statement* '}' | statement
    // condition      = assign
    // sysCall        = ('print' | 'read' | 'wait') assign ';'                 TODO (later)
    // assign         = IDENT '=' assign | ternary
    // ternary        = equality ('?' ternary ':' ternary)?
    // equality       = comparison (('==' | '!=') comparison)*
    // comparison     = addSub (('<' | '>' | '<=' | '>=') addSub)*
    // addSub         = multiplyDivide (('+' | '-') multiplyDivide)*
    // multiplyDivide = unary (('*' | '/') unary)*
    // unary          = ('-' | '!') unary | postfix
    // postfix        = data ('++' | '--')?
    // tuple          = '(' assign (',' assign)* ')'
    // data           = NUMBER | IDENT | STRING | tuple | lambda
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

        if(peek() instanceof DefToken){
            pos++; // consume
            return fun();
        }

        if(peek() instanceof IfToken){
            pos++; // consume
            return If();
        }

        if(peek() instanceof WhileToken){
            pos++; // consume
            return While();
        }

        Node expr = assign();

        if (peek() instanceof SemicolonToken) {
            pos++;
        }

        return expr;
    }

    private Node fun(){
        Token t = peek();
        if(!(t instanceof IdentityToken identityToken))
            throw new Bl0j_ParserException(t.line, t.line_index, "expected 'IDENTITY'");
        pos++;

        return new FunNode(identityToken.name, argBody(), block());
    }

    private ArgumentNode argBody(){
        Token t = peek();
        if (!(t instanceof LParenToken))
            throw new Bl0j_ParserException(t.line, t.line_index, "expected '('");
        pos++;

        boolean findIdentity = true;
        List<String> args = new ArrayList<>();
        while (pos < tokens.size()) {
            t = peek();

            if (t instanceof RParenToken) break;

            if(findIdentity){
                if (t instanceof IdentityToken identityToken) {
                    pos++; // consume
                    args.add(identityToken.name);
                } else throw new Bl0j_ParserException(t.line, t.line_index, "expected 'IDENTITY'");
            } else {
                if (t instanceof SeparatorToken) {
                    pos++; // consume
                } else throw new Bl0j_ParserException(t.line, t.line_index, "expected 'SEPARATOR'");
            }
            findIdentity = !findIdentity;
        }

        t = peek();
        if (!(t instanceof RParenToken))
            throw new Bl0j_ParserException(t.line, t.line_index, "expected ')'");
        pos++;
        return new ArgumentNode(args);
    }

    private Node If(){
        Node condition = condition();
        Node body = block();
        Node elseBody = null;

        if(peek() instanceof ElseToken) {
            pos++;
            elseBody = block();
        }

        return new IfNode(condition, body, elseBody);
    }

    private Node While(){
        return new WhileNode(condition(), block());
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

    private Node condition(){
        if(peek() instanceof LParenToken){
            pos++;
            var node = assign();
            Token closing = peek();
            if (!(closing instanceof RParenToken))
                throw new Bl0j_ParserException(closing.line, closing.line_index, "expected ')'");
            pos++;
            return node;
        } else
            return assign();
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
        return postfix();
    }

    private Node postfix(){
        Node left = data();
        if(peek() instanceof OpToken op &&
                (op.op == Op.PLUS_PLUS || op.op == Op.MINUS_MINUS)){
            pos++; // consume
            return new RUnaryNode(op.op, left);
        }

        return left;
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

        if (t instanceof LParenToken) {
            pos++;
            Node first = assign();

            if (peek() instanceof SeparatorToken) {
                List<Node> values = new ArrayList<>();
                values.add(first);
                while (peek() instanceof SeparatorToken) {
                    pos++; // consume ','
                    values.add(assign());
                }
                if (!(peek() instanceof RParenToken))
                    throw new Bl0j_ParserException(-1, -1, "expected ')'");
                pos++;
                return new TupleNode(values);
            } else {
                if (!(peek() instanceof RParenToken))
                    throw new Bl0j_ParserException(-1, -1, "expected ')'");
                pos++;
                return first;
            }
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
