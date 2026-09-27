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
import bl0.bl0jv2.generation.tokens.blocks.LBracketToken;
import bl0.bl0jv2.generation.tokens.blocks.RBracketToken;
import bl0.bl0jv2.generation.tokens.data.*;
import bl0.bl0jv2.generation.tokens.statements.*;

import java.util.ArrayList;
import java.util.List;

public final class Bl0jv2_Parser {
    private List<Token> tokens;
    private int pos;

    private String sourceCode;

    // program        = statement*
    // statement      =  if | for | fun | class | while | sysCall | assign ';' TODO (class)
    // class          = 'def' 'class' IDENT '{' fun* '}'                       TODO
    // new            = 'new' IDENT tuple                                      TODO
    // fun            = 'def' 'fun' IDENT funcBody block
    // lambda         = funcBody  '->' (assign | block)                        TODO (later)
    // argBody        = '(' (IDENT (',' IDENT)*)? ')'
    // if             = 'if' condition block ('else' block)?
    // while          = 'while' condition block
    // for            = 'for' '(' assign ';' assign ';' assign ')' block
    // block          = '{' statement* '}' | statement
    // condition      = assign
    // sysCall        = ('print' | 'println' | 'read' | 'wait') assign ';'
    // assign         = (IDENT | index) '=' assign | ternary
    // ternary        = or ('?' ternary ':' ternary)?
    // or             = and ('||' and)*               (short-circuit)
    // and            = bitOr ('&&' bitOr)*            (short-circuit)
    // bitOr          = bitXor ('|' bitXor)*
    // bitXor         = bitAnd ('^' bitAnd)*
    // bitAnd         = equality ('&' equality)*
    // equality       = comparison (('==' | '!=') comparison)*
    // comparison     = shift (('<' | '>' | '<=' | '>=') shift)*
    // shift          = addSub (('<<' | '>>') addSub)*
    // addSub         = multiplyDivide (('+' | '-') multiplyDivide)*
    // multiplyDivide = unary (('*' | '/' | '%' | '**') unary)*
    // unary          = ('-' | '!' | '~') unary | postfix
    // postfix        = data (tuple | index | '++' | '--' | '.' IDENT )*       TODO ('.' IDENT)
    // index          = '[' assign ']'
    // tuple          = '(' assign (',' assign)* ')'
    // array          = '[' (assign (',' assign)*)? ']'
    // data           = NUMBER | IDENT | STRING | tuple | array | lambda | new TODO (lambda, new)
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

        // stray/empty statement terminators must be skipped before checking
        // what kind of statement follows, otherwise this check would see a
        // stale ';' instead of the real next token
        while (consume_if(SemicolonToken.class));

        if(peek_t() instanceof NativeCallToken)
           return native_call_statement(); // self-consumes its trailing ';'

        if(consume_if(ReturnToken.class)) {
            Node returnNode = new ReturnNode(assign_evaluation());
            consume_if(SemicolonToken.class);
            return returnNode;
        }

        if(peek_t() instanceof IfToken) // dont consume!!
            return if_statement(); // self-terminating via block()

        if(peek_t() instanceof WhileToken) // dont consume!!
            return while_statement(); // self-terminating via block()

        if(peek_t() instanceof ForToken) // dont consume!!
            return for_statement(); // self-terminating via block()

        Node node = assign_evaluation();
        consume_if(SemicolonToken.class);
        return node;
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

    // for (init; condition; update) block - unlike while's condition, the
    // parens are mandatory here since three clauses need clear separators
    private Node for_statement(){
        consume_or_throw(ForToken.class, "'for' token expected at start for for statement");
        consume_or_throw(LParenToken.class, "'(' token expected after 'for'");

        Node init = assign_evaluation();
        consume_or_throw(SemicolonToken.class, "';' token expected after for-loop initializer");

        Node condition = assign_evaluation();
        consume_or_throw(SemicolonToken.class, "';' token expected after for-loop condition");

        Node update = assign_evaluation();
        consume_or_throw(RParenToken.class, "')' token expected after for-loop update");

        return new ForNode(init, condition, update, block());
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
        // a plain IDENT is the common case and doesn't need a full
        // expression parse to know it might be an assignment target, but an
        // indexed target (arr[i] = ...) only reveals itself after parsing
        // the postfix chain, so the left side is always parsed first and
        // checked for '=' afterwards
        Node left = ternary_evaluation();

        if ((left instanceof IdentityNode || left instanceof IndexNode)
                && peek_t() instanceof OpToken op && op.op == Operator.ASSIGNMENT) {
            consume_t();
            Node right = assign_evaluation();
            return new BinaryNode(left, op.op, right);
        }

        return left;
    }

    private Node ternary_evaluation(){
        Node left = or_evaluation();

        if(consume_if(Ternary_IfToken.class)){
            var body = ternary_evaluation();

            consume_or_throw(Ternary_ElseToken.class, "':' token expected for ternary");

            var elseBody = ternary_evaluation();

            return new Ternary_IfNode(left, body, elseBody);
        }

        return left;
    }

    // || and && are short-circuiting (see the compiler), unlike every other
    // binary operator here, but that's purely a codegen concern - parsing
    // them is a plain left-associative binary chain like the rest
    private Node or_evaluation(){
        Node left = and_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op && op.op == Operator.OR) {
                consume_t();
                left = new BinaryNode(left, op.op, and_evaluation());
            } else break;
        }

        return left;
    }

    private Node and_evaluation(){
        Node left = bit_or_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op && op.op == Operator.AND) {
                consume_t();
                left = new BinaryNode(left, op.op, bit_or_evaluation());
            } else break;
        }

        return left;
    }

    private Node bit_or_evaluation(){
        Node left = bit_xor_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op && op.op == Operator.BIT_OR) {
                consume_t();
                left = new BinaryNode(left, op.op, bit_xor_evaluation());
            } else break;
        }

        return left;
    }

    private Node bit_xor_evaluation(){
        Node left = bit_and_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op && op.op == Operator.BIT_XOR) {
                consume_t();
                left = new BinaryNode(left, op.op, bit_and_evaluation());
            } else break;
        }

        return left;
    }

    private Node bit_and_evaluation(){
        Node left = equality_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op && op.op == Operator.BIT_AND) {
                consume_t();
                left = new BinaryNode(left, op.op, equality_evaluation());
            } else break;
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
        Node left = shift_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op &&
                    (op.op == Operator.LESS || op.op == Operator.LESS_EQUALS || op.op == Operator.GREATER || op.op == Operator.GREATER_EQUALS)) {
                consume_t();
                left = new BinaryNode(left, op.op, shift_evaluation());
            } else break;
        }

        return left;
    }

    private Node shift_evaluation(){
        Node left = add_sub_evaluation();

        while (pos < tokens.size()) {
            Token t = peek_t();
            if (t instanceof OpToken op && (op.op == Operator.SHIFT_LEFT || op.op == Operator.SHIFT_RIGHT)) {
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
                    (op.op == Operator.STAR || op.op == Operator.DIV || op.op == Operator.REMAINDER || op.op == Operator.STAR_STAR)) {
                consume_t();
                left = new BinaryNode(left, op.op, unary_evaluation());
            } else break;
        }

        return left;
    }

    private Node unary_evaluation() {
        Token t = peek_t();
        if (t instanceof OpToken op &&
                (op.op == Operator.MINUS || op.op == Operator.NOT || op.op == Operator.BIT_NOT)) {
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
            else if (peek_t() instanceof LBracketToken) {
                consume_t();
                Node index = assign_evaluation();
                consume_or_throw(RBracketToken.class, "']' token expected at end of index expression");
                left = new IndexNode(left, index);
            }
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

    private Node array_literal(){
        consume_or_throw(LBracketToken.class, "'[' token expected at start of array literal");
        List<Node> elements = new ArrayList<>();

        if (consume_if(RBracketToken.class))
            return new ArrayLiteralNode(elements);

        elements.add(assign_evaluation());

        while (consume_if(SeparatorToken.class))
            elements.add(assign_evaluation());

        consume_or_throw(RBracketToken.class, "']' token expected at end of array literal");

        return new ArrayLiteralNode(elements);
    }

    private Node data() {
        Token t = peek_t();

        if(consume_if(NilToken.class))
            return new NilNode();

        if (t instanceof NumberToken numberToken) {
            pos++;
            if (numberToken.value.indexOf('.') >= 0)
                return new FloatNode(Double.parseDouble(numberToken.value));
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

        if (t instanceof LBracketToken)
            return array_literal();

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
