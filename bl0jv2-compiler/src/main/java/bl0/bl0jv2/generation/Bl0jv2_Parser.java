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
    // statement      =  if | for | try | fun | class | while | break | continue | sysCall | assign ';'
    // break          = 'break' ';'
    // continue       = 'continue' ';'
    // class          = 'def' 'class' IDENT '{' (field | constField | staticField | fun | staticFun)* '}'   (no inheritance)
    // field          = 'field' IDENT ('=' literal)? ';'
    // constField     = 'const' 'field' IDENT ('=' literal)? ';'   (this.field = ... only allowed inside init(); compile-time only, see Bl0jv2_Compiler)
    // literal        = NUMBER | STRING | BOOL | 'nil'          (field initializer only - not full 'data')
    // staticField    = 'static' 'field' IDENT ';'
    // staticFun      = 'static' 'def' IDENT funcBody block
    // new            = 'new' IDENT tuple
    // fun            = 'def' 'fun' IDENT funcBody block
    // lambda         = '(' (IDENT (',' IDENT)*)? ')' '->' (assign | block)
    // argBody        = '(' (IDENT (',' IDENT)*)? ')'
    // if             = 'if' condition block ('else' block)?
    // while          = 'while' condition block
    // for            = 'for' '(' assign ';' assign ';' assign ')' block
    // try            = 'try' block 'catch' '(' IDENT ')' block
    // block          = '{' statement* '}' | statement
    // condition      = assign
    // sysCall        = ('print' | 'println' | 'read' | 'wait') assign ';'
    // assign         = IDENT (',' IDENT)+ '=' assign (',' assign)*           (destructuring)
    //                | (IDENT | index) '=' assign | ternary
    // ternary        = or ('?' ternary ':' ternary)?
    // or             = and ('||' and)*               (short-circuit)
    // and            = bitOr ('&&' bitOr)*            (short-circuit)
    // bitOr          = bitXor ('|' bitXor)*
    // bitXor         = bitAnd ('^' bitAnd)*
    // bitAnd         = equality ('&' equality)*
    // equality       = comparison (('==' | '!=') comparison)*
    // comparison     = shift (('<' | '>' | '<=' | '>=') shift)*
    // shift          = addSub (('<<' | '>>' | '>>>') addSub)*
    // addSub         = multiplyDivide (('+' | '-') multiplyDivide)*
    // multiplyDivide = unary (('*' | '/' | '%' | '**') unary)*
    // unary          = ('-' | '!' | '~') unary | postfix
    // postfix        = data (tuple | index | '.' IDENT | '++' | '--' )*
    // index          = '[' assign ']'
    // tuple          = '(' assign (',' assign)* ')'
    // array          = '[' (assign (',' assign)*)? ']'
    // data           = NUMBER | IDENT | STRING | THIS | tuple | array | new | lambda
    // NUMBER         = decimal digits (with an optional '.' for a float) | '0x' hex digits | '0b' binary digits
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

        if(peek_t() instanceof TryToken) // dont consume!!
            return try_statement(); // self-terminating via block()

        if(consume_if(BreakToken.class)) {
            consume_if(SemicolonToken.class);
            return new BreakNode();
        }

        if(consume_if(ContinueToken.class)) {
            consume_if(SemicolonToken.class);
            return new ContinueNode();
        }

        if(consume_if(ImportToken.class)) {
            String path = consume_or_throw(StringToken.class, "string path expected after 'import'").value;
            consume_or_throw(SemicolonToken.class, "';' token expected after import path");
            return new ImportNode(path);
        }

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
        consume_or_throw(LBraceToken.class, "'{' token expected to start class body");

        List<String> fieldNames = new ArrayList<>();
        List<Node> fieldDefaultNodes = new ArrayList<>();
        List<String> constFieldNames = new ArrayList<>();
        List<FunNode> methods = new ArrayList<>();
        List<FunNode> staticMethods = new ArrayList<>();
        List<String> staticFieldNames = new ArrayList<>();

        while (!consume_if(RBraceToken.class)) {
            if (consume_if(ConstToken.class)) {
                consume_or_throw(FieldToken.class, "'field' token expected after 'const'");
                constFieldNames.add(parseFieldDeclaration(fieldNames, fieldDefaultNodes));
            } else if (consume_if(FieldToken.class)) {
                parseFieldDeclaration(fieldNames, fieldDefaultNodes);
            } else if (consume_if(StaticToken.class)) {
                if (consume_if(FieldToken.class)) {
                    // a static field is shared by the class itself, not by
                    // each instance - always defaults to nil, same as an
                    // instance field, since there's no static-initializer
                    // concept yet; assign it explicitly if you need a value
                    String fieldName = consume_or_throw(IdentityToken.class, "field name expected after 'static field'").name;
                    consume_or_throw(SemicolonToken.class, "';' token expected after field declaration");
                    staticFieldNames.add(fieldName);
                } else {
                    consume_or_throw(DefToken.class, "'def' token expected for static method definition");
                    // a static method is just a regular "ClassName.method"
                    // function with no implicit 'this' - resolved entirely at
                    // compile time via ClassName.method(...), never through
                    // runtime instance dispatch
                    FunNode raw = (FunNode) define_function();
                    staticMethods.add(new FunNode(className + "." + raw.name, raw.args, raw.body));
                }
            } else {
                consume_or_throw(DefToken.class, "'def' token expected for method definition inside a class body");

                // instance methods are just functions with 'this' prepended
                // as an implicit first parameter and a "ClassName.method"
                // name, so the rest of the compiler treats them exactly
                // like any other function - no separate calling machinery
                FunNode raw = (FunNode) define_function();
                List<String> paramsWithThis = new ArrayList<>();
                paramsWithThis.add("this");
                paramsWithThis.addAll(raw.args.args);
                methods.add(new FunNode(className + "." + raw.name, new PARAMS_N(paramsWithThis), raw.body));
            }
        }

        return new ClassNode(className, fieldNames, fieldDefaultNodes, constFieldNames, methods, staticMethods, staticFieldNames);
    }

    // 'field' has already been consumed - parses IDENT ('=' literal)? ';'
    // and appends to the running field lists; returns the field's name so
    // the 'const field' branch can also record it separately
    private String parseFieldDeclaration(List<String> fieldNames, List<Node> fieldDefaultNodes) {
        String fieldName = consume_or_throw(IdentityToken.class, "field name expected after 'field'").name;

        Node defaultNode = null;
        if (peek_t() instanceof OpToken op && op.op == Operator.ASSIGNMENT) {
            pos++;
            defaultNode = field_default_literal();
        }

        consume_or_throw(SemicolonToken.class, "';' token expected after field declaration");
        fieldNames.add(fieldName);
        fieldDefaultNodes.add(defaultNode);
        return fieldName;
    }

    // field x = <literal>;  -  deliberately restricted to a literal leaf,
    // not the full 'data' production (no identifiers, 'new', tuples/
    // arrays, or lambdas): this becomes a compile-time default baked into
    // the class definition, not code that runs at construction time
    private Node field_default_literal(){
        Token t = peek_t();

        if (t instanceof NumberToken numberToken) {
            pos++;
            return parseNumberLiteral(numberToken.value);
        }
        if (t instanceof StringToken stringToken) {
            pos++;
            return new StringNode(stringToken.value);
        }
        if (t instanceof BooleanToken booleanToken) {
            pos++;
            return new BooleanNode(booleanToken.value);
        }
        if (consume_if(NilToken.class))
            return new NilNode();

        gen_exception(t, "field initializer must be a literal (number, string, bool, or nil) - got " + t);
        return null;
    }

    // shared by data() and field_default_literal() - a NumberToken's own
    // text carries an optional '0x'/'0b' prefix (see Bl0jv2_Lexer), parsed
    // with parseUnsignedInt rather than parseInt so a full-width bit
    // pattern like 0xFFFFFFFF is a valid literal even though it's negative
    // as a signed int - the whole reason to write one in hex in the first
    // place (kernel-style code: masks, addresses)
    private Node parseNumberLiteral(String value) {
        if (value.indexOf('.') >= 0)
            return new FloatNode(Double.parseDouble(value));

        if (value.length() > 2 && value.charAt(0) == '0') {
            char prefix = value.charAt(1);
            if (prefix == 'x' || prefix == 'X')
                return new NumberNode(Integer.parseUnsignedInt(value.substring(2), 16));
            if (prefix == 'b' || prefix == 'B')
                return new NumberNode(Integer.parseUnsignedInt(value.substring(2), 2));
        }

        return new NumberNode(Integer.parseInt(value));
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

    private Node try_statement(){
        consume_or_throw(TryToken.class, "'try' token expected at start for try statement");
        Node tryBody = block();

        consume_or_throw(CatchToken.class, "'catch' token expected after try block");
        consume_or_throw(LParenToken.class, "'(' token expected after 'catch'");
        String catchVarName = consume_or_throw(IdentityToken.class,
                "identifier expected for the caught error variable").name;
        consume_or_throw(RParenToken.class, "')' token expected after catch variable");
        Node catchBody = block();

        return new TryNode(tryBody, catchVarName, catchBody);
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
        Node destructuring = try_parse_destructuring_assignment();
        if (destructuring != null)
            return destructuring;

        // a plain IDENT is the common case and doesn't need a full
        // expression parse to know it might be an assignment target, but an
        // indexed target (arr[i] = ...) only reveals itself after parsing
        // the postfix chain, so the left side is always parsed first and
        // checked for '=' afterwards
        Node left = ternary_evaluation();

        if ((left instanceof IdentityNode || left instanceof IndexNode || left instanceof FieldAccessNode)
                && peek_t() instanceof OpToken op && op.op == Operator.ASSIGNMENT) {
            consume_t();
            Node right = assign_evaluation();
            return new BinaryNode(left, op.op, right);
        }

        return left;
    }

    // a, b = <expr> (, <expr>)*   -   e.g. 'x, y = f();' or 'a, b = b, a;'
    // (a swap, no temp variable needed - the whole right side is evaluated
    // before anything on the left is touched). Only a raw lookahead scan
    // over IDENT (',' IDENT)+ '=' decides whether this applies; nothing is
    // consumed unless the pattern actually matches, so a plain 'f(a, b)'
    // call-argument list or '[a, b]' array literal is never misread as one
    // (the token right after the last IDENT there is ')'/']', not '=').
    private Node try_parse_destructuring_assignment(){
        if (!(peek_t() instanceof IdentityToken start))
            return null;

        List<String> names = new ArrayList<>();
        names.add(start.name);

        int lookahead = 1;
        while (peek_safe(lookahead) instanceof SeparatorToken && peek_safe(lookahead + 1) instanceof IdentityToken idTok) {
            names.add(idTok.name);
            lookahead += 2;
        }

        if (names.size() < 2 || !(peek_safe(lookahead) instanceof OpToken op && op.op == Operator.ASSIGNMENT))
            return null;

        for (int i = 0; i < names.size(); i++) {
            consume_t(); // IDENT
            if (i < names.size() - 1)
                consume_t(); // ','
        }
        consume_t(); // '='

        List<Node> rightValues = new ArrayList<>();
        rightValues.add(assign_evaluation());
        while (consume_if(SeparatorToken.class))
            rightValues.add(assign_evaluation());

        Node right = rightValues.size() == 1 ? rightValues.get(0) : new TupleNode(rightValues);

        List<Node> targets = new ArrayList<>();
        for (String name : names)
            targets.add(new IdentityNode(name));

        return new DestructuringAssignNode(targets, right);
    }

    // (params) -> expr | (params) -> { block }. Only a raw lookahead scan
    // over '(' (IDENT (',' IDENT)*)? ')' '->' decides this is a lambda,
    // same speculative-and-non-consuming approach as destructuring
    // assignment - a plain parenthesized expression or tuple like
    // '(a, b)' is never misread as one, since the token right after ')'
    // there isn't '->'.
    private Node try_parse_lambda(){
        int i = 1; // tokens[pos] is the '(' itself, not yet consumed
        List<String> params = new ArrayList<>();

        if (!(peek_safe(i) instanceof RParenToken)) {
            if (!(peek_safe(i) instanceof IdentityToken first))
                return null;
            params.add(first.name);
            i++;

            while (peek_safe(i) instanceof SeparatorToken && peek_safe(i + 1) instanceof IdentityToken idTok) {
                params.add(idTok.name);
                i += 2;
            }
        }

        if (!(peek_safe(i) instanceof RParenToken))
            return null;
        i++;

        if (!(peek_safe(i) instanceof ArrowToken))
            return null;

        pos += i + 1; // consume everything through '->'

        Node body = peek_t() instanceof LBraceToken ? block() : new ReturnNode(assign_evaluation());
        return new LambdaNode(new PARAMS_N(params), body);
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
            if (t instanceof OpToken op && (op.op == Operator.SHIFT_LEFT || op.op == Operator.SHIFT_RIGHT || op.op == Operator.SHIFT_RIGHT_UNSIGNED)) {
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
            else if (peek_t() instanceof DotToken) {
                consume_t();
                String fieldName = consume_or_throw(IdentityToken.class, "field/method name expected after '.'").name;
                left = new FieldAccessNode(left, fieldName);
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

        if(consume_if(ThisToken.class))
            return new IdentityNode("this");

        if(consume_if(NewToken.class)) {
            String className = consume_or_throw(IdentityToken.class, "class name expected after 'new'").name;
            return new NewNode(className, tuple_args());
        }

        if (t instanceof NumberToken numberToken) {
            pos++;
            return parseNumberLiteral(numberToken.value);
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
            Node lambda = try_parse_lambda();
            if (lambda != null)
                return lambda;

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

    // unlike peek_t(extra), never throws on out-of-bounds - needed for
    // speculative lookahead (e.g. destructuring-assignment detection) that
    // must be able to fail quietly and fall back to normal parsing
    private Token peek_safe(int extra) {
        int index = pos + extra;
        return index < tokens.size() ? tokens.get(index) : null;
    }
}
