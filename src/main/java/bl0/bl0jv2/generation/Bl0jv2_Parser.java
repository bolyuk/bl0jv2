package bl0.bl0jv2.generation;

import bl0.bl0jv2.generation.nodes.BinaryNode;
import bl0.bl0jv2.generation.nodes.Node;
import bl0.bl0jv2.exceptions.Bl0j_ParserException;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.generation.nodes.data.*;
import bl0.bl0jv2.generation.nodes.statements.*;
import bl0.bl0jv2.generation.nodes.unary.LUnaryNode;
import bl0.bl0jv2.generation.nodes.unary.RUnaryNode;
import bl0.bl0jv2.generation.tokens.*;
import bl0.bl0jv2.generation.tokens.blocks.LBraceToken;
import bl0.bl0jv2.generation.tokens.blocks.RBraceToken;
import bl0.bl0jv2.generation.tokens.blocks.LParenToken;
import bl0.bl0jv2.generation.tokens.blocks.RParenToken;
import bl0.bl0jv2.generation.tokens.data.*;
import bl0.bl0jv2.generation.tokens.statements.*;

import java.util.ArrayList;
import java.util.List;

public final class Bl0jv2_Parser {
    private List<Token> tokens;
    private int pos;

    private String sourceCode;

    // program        = statement*
    // statement      =  if | fun | class | while | sysCall | assign ';'       TODO
    // class          = 'def' 'class' IDENT '{' fun* '}'                       TODO
    // new            = 'new' IDENT tuple                                      TODO
    // fun            = 'def' 'fun' IDENT funcBody block
    // lambda         = funcBody  '->' (assign | block)                        TODO (later)
    // argBody        = '(' (IDENT (',' IDENT)*)? ')'
    // if             = 'if' condition block ('else' block)?
    // while          = 'while' condition block
    // block          = '{' statement* '}' | statement
    // condition      = assign
    // sysCall        = ('print' | 'println' | 'read' | 'wait') assign ';'
    // assign         = IDENT '=' assign | ternary | postfix '=' assign        TODO
    // ternary        = equality ('?' ternary ':' ternary)?
    // equality       = comparison (('==' | '!=') comparison)*
    // comparison     = addSub (('<' | '>' | '<=' | '>=') addSub)*
    // addSub         = multiplyDivide (('+' | '-') multiplyDivide)*
    // multiplyDivide = unary (('*' | '/' | '%') unary)*
    // unary          = ('-' | '!') unary | postfix
    // postfix        = data (tuple | '++' | '--' | '.' IDENT )*               TODO
    // tuple          = '(' assign (',' assign)* ')'
    // data           = NUMBER | IDENT | STRING | tuple | lambda | new         TODO
    public Node getAST(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
        return program();
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    // --- UPPER NODES ---

    private PROGRAM_N program() {
        List<Node> stmts = new ArrayList<>();

        while (!(peek_t() instanceof EOFToken))
            stmts.add(classStatement());

        return new PROGRAM_N(stmts);
    }

    private Node classStatement() {
        if(peek_t() instanceof DefToken) // dont consume!!
            return define_function_or_class();

        return statement();
    }

    private Node statement(){

        if(peek_t() instanceof NativeCallToken)
           return native_call_statement();

        if(consume_if(ReturnToken.class))
            return new ReturnNode(assign_evaluation());

        if(peek_t() instanceof IfToken) // dont consume!!
            return if_statement();

        if(peek_t() instanceof WhileToken) // dont consume!!
            return while_statement();

        // TODO probably not needed
        consume_if(SemicolonToken.class); // TODO check if it possible to kill semicolons in another place

        return assign_evaluation();
    }

    // --- DEFINITIONS ---

    private Node define_function_or_class(){
        consume_or_throw(DefToken.class, "'define' token expected for Class or Function definition");

        Token t = peek_t();

        if(t instanceof IdentityToken)
            return define_function();

        if(t instanceof ClassToken)
            return define_class();

        throw new Bl0j_ParserException(t.line, t.line_index, "unexpected 'def' token");
    }

    private Node define_function(){
        String function_name = consume_or_throw(IdentityToken.class, "'IDENTITY' token expected for Function definition").name;

        return new FunNode(function_name, define_function_params_body(), block());
    }

    private PARAMS_N define_function_params_body(){
        // mandatory
        consume_or_throw(LParenToken.class, "'(' token expected for parameter definition");

        Token t;

        boolean findIdentity = true;
        List<String> params = new ArrayList<>();

        while (pos < tokens.size()) {
            t = peek_t();

            if (t instanceof RParenToken) break; // end of params input, consuming later

            if(findIdentity)
                params.add(consume_or_throw(IdentityToken.class, "'IDENTITY' token expected for parameter definition").name);
            else
                consume_or_throw(SeparatorToken.class,"',' token expected for parameter definition");

            findIdentity = !findIdentity;
        }

        // mandatory
        consume_or_throw(RParenToken.class, "')' token expected at end of parameter definition");

        return new PARAMS_N(params);
    }

    private Node define_class(){
        consume_or_throw(ClassToken.class, "'class' token expected for Class definition");
        String className = consume_or_throw(IdentityToken.class, "'IDENTITY' token expected for Class definition").name;

        List<Node> stmts = new ArrayList<>();

        while (!(peek_t() instanceof EOFToken))
            stmts.add(classStatement());


        return null; //TODO just for now so
    }

    // --- STATEMENTS ---

    private Node native_call_statement(){
        byte id = consume_or_throw(NativeCallToken.class, "'if' token expected at start for if statement").id;

        Node operand = assign_evaluation();

        consume_if(SemicolonToken.class); // TODO check if it possible to kill semicolons in another place

        return new NativeCallNode(id, operand);
    }

    private Node if_statement(){
        consume_or_throw(IfToken.class, "'if' token expected at start for if statement");

        Node condition = condition_evaluation();
        Node body = block();
        Node elseBody = null;

        if(consume_if(ElseToken.class))
            elseBody = block();

        return new IfNode(condition, body, elseBody);
    }

    private Node while_statement(){
        consume_or_throw(WhileToken.class, "'while' token expected at start for while statement");
        return new WhileNode(condition_evaluation(), block());
    }

    private Node block(){
        if(consume_if(LBraceToken.class)) {
            List<Node> stmts = new ArrayList<>();

            while (!consume_if(RBraceToken.class))
                stmts.add(statement());

            return new PROGRAM_N(stmts);
        } else {
            Node left = statement();

            consume_if(SemicolonToken.class); // TODO check if it possible to kill semicolons in another place

            return left;
        }
    }

    // --- EVALUATIONS ---

    private Node condition_evaluation(){
        if(consume_if(LParenToken.class)) {
            var node = assign_evaluation();

            consume_or_throw(RParenToken.class, "')' token expected at end for condition evaluation");

            return node;
        } else
            return assign_evaluation();
    }

    private Node assign_evaluation(){
        if(peek_t() instanceof IdentityToken && peek_t(1) instanceof OpToken op && op.op == Operator.ASSIGNMENT){
            Node left = data();
            consume_t();
            Node right = assign_evaluation();
            return new BinaryNode(left, op.op, right);
        }
        return ternary_evaluation();
    }

    private Node ternary_evaluation(){
        Node left = equality_evaluation();

        if(consume_if(Ternary_IfToken.class)){
            var body = ternary_evaluation();

            consume_or_throw(Ternary_ElseToken.class, "':' token expected for ternary");

            var elseBody = ternary_evaluation();

            return new Ternary_IfNode(left, body, elseBody);
        }

        return left;
    }

    private Node equality_evaluation(){
        Node left = comparison_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op && (op.op == Operator.EQUALS || op.op == Operator.NOT_EQUALS)) {
                consume_t();
                left = new BinaryNode(left, op.op, comparison_evaluation());
            } else break;
        }

        return left;
    }

    private Node comparison_evaluation(){
        Node left = add_sub_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op &&
                    (op.op == Operator.LESS || op.op == Operator.LESS_EQUALS || op.op == Operator.GREATER || op.op == Operator.GREATER_EQUALS)) {
                consume_t();
                left = new BinaryNode(left, op.op, add_sub_evaluation());
            } else break;
        }

        return left;
    }

    private Node add_sub_evaluation() {
        Node left = mul_div_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op &&
                    (op.op == Operator.PLUS || op.op == Operator.MINUS)) {
                consume_t();
                left = new BinaryNode(left, op.op, mul_div_evaluation());
            } else break;
        }

        return left;
    }

    private Node mul_div_evaluation() {
        Node left = unary_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op &&
                    (op.op == Operator.STAR || op.op == Operator.DIV || op.op == Operator.REMAINDER)) {
                consume_t();
                left = new BinaryNode(left, op.op, unary_evaluation());
            } else break;
        }

        return left;
    }

    private Node unary_evaluation() {
        Token t = peek_t();
        if (t instanceof OpToken op &&
                (op.op == Operator.MINUS || op.op == Operator.NOT)) {
            consume_t();
            return new LUnaryNode(op.op, unary_evaluation());
        }
        return postfix_evaluation();
    }

    private Node postfix_evaluation(){
        Node left = data();

        while (true) {
            if (peek_t() instanceof LParenToken)
                left = new FunCall(left, tuple_args());
            else if (peek_t() instanceof OpToken op && (op.op == Operator.PLUS_PLUS || op.op == Operator.MINUS_MINUS)) {
                consume_t();
                left = new RUnaryNode(op.op, left);
            } else break;
        }

        return left;
    }

    private List<Node> tuple_args(){
        consume_or_throw(LParenToken.class, "'(' token expected at start for tuple arguments");
        List<Node> args = new ArrayList<>();

        if (consume_if(RParenToken.class))
            return args;

        args.add(assign_evaluation());

        while (consume_if(SeparatorToken.class))
            args.add(assign_evaluation());

        consume_or_throw(RParenToken.class, "')' token expected at end for tuple arguments");

        return args;
    }

    private Node data() {
        Token t = peek_t();

        if(consume_if(NilToken.class))
            return new NilNode();

        if (t instanceof NumberToken numberToken) {
            pos++;
            return new NumberNode(Integer.parseInt(numberToken.value));
        }

        if(t instanceof StringToken stringToken) {
            pos++;
            return new StringNode(stringToken.value);
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
            Node first = assign_evaluation();

            if (peek_t() instanceof SeparatorToken) {
                List<Node> values = new ArrayList<>();
                values.add(first);
                while (peek_t() instanceof SeparatorToken) {
                    pos++; // consume ','
                    values.add(assign_evaluation());
                }
                if (!(peek_t() instanceof RParenToken))
                    throw new Bl0j_ParserException(-1, -1, "expected ')'");
                pos++;
                return new TupleNode(values);
            } else {
                if (!(peek_t() instanceof RParenToken))
                    throw new Bl0j_ParserException(-1, -1, "expected ')'");
                pos++;
                return first;
            }
        }

        gen_exception(t,"unexpected token - "+t);
        return null;
    }

    private void gen_exception(Token t, String reason){
        String context = "";
        if(sourceCode != null && t != null)
        {
            var lines = sourceCode.split("\n", -1);
            if(t.line >= 0 && t.line < lines.length){
                String srcLine = lines[t.line];
                String caret = " ".repeat(Math.max(0, t.line_index)) + "^";
                context = srcLine + "\n" + caret + "\n";
            } else
                context = "wrong line indices...\n";
        }

        throw new Bl0j_ParserException(t != null ? t.line : -1,t != null ? t.line_index : 1, context+reason);
    }

    private <T extends Token> boolean consume_if(Class<T> tokenClass){
        Token t = peek_t();

        boolean qualified = tokenClass.isInstance(t);

        if(qualified)
            consume_t();

        return qualified;
    }

    private <T extends Token> T consume_or_throw(Class<T> tokenClass, String errorMsg){
        Token t = peek_t();

        if(!consume_if(tokenClass))
            gen_exception(t, errorMsg+", but got - "+t);

        return (T)t;
    }

    private void consume_t(){
        pos++;
    }

    private Token peek_t() {
        if (pos >= tokens.size())
            gen_exception(lastToken(), "unexpected end of input");
        return tokens.get(pos);
    }

    private Token peek_t(int extra) {
        if (pos+extra >= tokens.size())
            gen_exception(lastToken(), "unexpected end of input");
        return tokens.get(pos+extra);
    }

    private Token lastToken(){
        return tokens.isEmpty() ? null : tokens.get(tokens.size()-1);
    }
}
