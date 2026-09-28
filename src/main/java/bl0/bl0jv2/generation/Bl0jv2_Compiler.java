package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.C;
import bl0.bl0jv2.data.ClassDef;
import bl0.bl0jv2.data.Constants;
import bl0.bl0jv2.data.FunDef;
import bl0.bl0jv2.data.OpCodes;
import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.generation.nodes.BinaryNode;
import bl0.bl0jv2.generation.nodes.Node;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
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

public final class Bl0jv2_Compiler {
    private final List<Byte> bytecode = new ArrayList<>();
    private final List<Object> constants = new ArrayList<>();
    private int regIndex = 0;
    private int regCount = 0;

    private final HashMap<String, Integer> identityMapping = new HashMap<>();
    private final List<FunNode> lazy_functions = new ArrayList<>();
    private final HashMap<String, Integer> functionMapping = new HashMap<>();

    // className -> (its constant-pool index, whether it declares 'init')
    private final HashMap<String, ClassInfo> classMapping = new HashMap<>();
    private record ClassInfo(int constIndex, boolean hasInit) {}

    public Bl0jv2_Compiler() {}

    // parsed once per JVM (the prelude source never changes at runtime),
    // then its top-level statements (currently just function definitions)
    // are prepended ahead of every user program
    private static List<Node> preludeNodes;

    private static List<Node> preludeNodes() {
        if (preludeNodes == null) {
            var lexer = new Bl0jv2_Lexer();
            var parser = new Bl0jv2_Parser();
            Node ast = parser.getAST(lexer.getTokens(Bl0jv2_Prelude.SOURCE));
            preludeNodes = ((PROGRAM_N) ast).nodes;
        }
        return preludeNodes;
    }

    public byte[] compile(Node node) {
        bytecode.clear();
        constants.clear();
        identityMapping.clear();
        lazy_functions.clear();
        functionMapping.clear();
        classMapping.clear();
        regIndex = 0;
        regCount = 0;

        if(!(node instanceof PROGRAM_N program))
            throw new Bl0j_CompilerException("node is not a ProgramNode");

        List<Node> withPrelude = new ArrayList<>(preludeNodes());
        withPrelude.addAll(program.nodes);
        PROGRAM_N merged = new PROGRAM_N(withPrelude);

        fetchFunctions(merged);
        compileInner(merged);
        _emit(OpCodes.HALT);
        compileFunctions();

        return build_header_bytecode();
    }

    private void fetchFunctions(PROGRAM_N program) {
        for(var node : program.nodes) {
            if(node instanceof FunNode funNode){
                lazy_functions.add(funNode);
                int constIndex = constant(new FunDef(funNode.name, -1, (short)0, (short)0));
                functionMapping.put(funNode.name, constIndex);
            }

            // a class's methods are registered exactly like top-level
            // functions (they already carry a "ClassName.method" name and
            // an implicit 'this' parameter from the parser) - the class
            // itself is then a ClassDef constant that just points at each
            // method's own FunDef constant by index
            if (node instanceof ClassNode classNode) {
                List<String> methodNames = new ArrayList<>();
                List<Integer> methodConstIndices = new ArrayList<>();
                boolean hasInit = false;

                for (FunNode method : classNode.methods) {
                    lazy_functions.add(method);
                    int constIndex = constant(new FunDef(method.name, -1, (short)0, (short)0));
                    functionMapping.put(method.name, constIndex);

                    String plainName = method.name.substring(classNode.name.length() + 1);
                    methodNames.add(plainName);
                    methodConstIndices.add(constIndex);
                    if (plainName.equals("init")) hasInit = true;
                }

                int classConstIndex = constant(new ClassDef(classNode.name, classNode.fieldNames, methodNames, methodConstIndices));
                classMapping.put(classNode.name, new ClassInfo(classConstIndex, hasInit));

                // static methods are registered as plain functions under
                // their mangled name and resolved entirely at compile time
                // (ClassName.method(...)) - they never go through runtime
                // instance dispatch, so they're deliberately left out of
                // the ClassDef's own method table above
                for (FunNode staticMethod : classNode.staticMethods) {
                    lazy_functions.add(staticMethod);
                    int constIndex = constant(new FunDef(staticMethod.name, -1, (short)0, (short)0));
                    functionMapping.put(staticMethod.name, constIndex);
                }
            }
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

            int bodyStart = adress * 3;
            compileInner(fun.body);
            // an empty body emits nothing, so there is no instruction of
            // *this* function to inspect - peeking at bytecode.size()-3 would
            // read the tail of whatever was emitted before it (e.g. another
            // function's own RETURN) and could wrongly skip this function's
            // implicit return
            boolean bodyEmittedSomething = bytecode.size() > bodyStart;
            if(!bodyEmittedSomething || bytecode.get(bytecode.size()-3) != OpCodes.RETURN)
                _emit(OpCodes.RETURN, 0);

            int constIndex = functionMapping.get(fun.name);
            constants.set(constIndex, new FunDef(fun.name, adress,(short) arity ,(short) regIndex));;
        }

    }

    // true when funCall is a call-syntax built-in: an unshadowed name with
    // the expected arity. User-defined functions always take precedence.
    private boolean isBuiltinCall(FunCall funCall, String name, int arity) {
        return funCall.left instanceof IdentityNode idNode && idNode.name.equals(name)
                && funCall.args.size() == arity && !functionMapping.containsKey(name);
    }

    // handles a call-syntax built-in like len(x) or toArr(x): a single
    // argument, an opcode that mutates it in place. Returns null when
    // funCall doesn't match name, so callers can chain several of these
    // before falling back to a real function call.
    private Integer compileBuiltinUnaryCall(FunCall funCall, String name, byte opcode) {
        if (!isBuiltinCall(funCall, name, 1))
            return null;

        int argReg = compileInner(funCall.args.get(0));
        // the opcode mutates its operand in place, so a bare-variable
        // argument must not reuse its own register directly
        int result = regIndex++;
        _emit(OpCodes.MOV, result, argReg);
        _emit(opcode, result);
        return result;
    }

    // push(arr, x): grows arr in place (mutates the Bl0jArray object, not
    // either register) and evaluates to nil - there's nothing useful to
    // return without risking corrupting arrReg or valueReg
    private Integer compilePush(FunCall funCall) {
        if (!isBuiltinCall(funCall, "push", 2))
            return null;

        int arrReg = compileInner(funCall.args.get(0));
        int valueReg = compileInner(funCall.args.get(1));
        _emit(OpCodes.PUSH, arrReg, valueReg);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    // pop(arr): unlike len/toArr, the popped value must land in a *fresh*
    // register - arrReg has to keep pointing at the (still valid) array
    private Integer compilePop(FunCall funCall) {
        if (!isBuiltinCall(funCall, "pop", 1))
            return null;

        int arrReg = compileInner(funCall.args.get(0));
        int result = regIndex++;
        _emit(OpCodes.POP, result, arrReg);
        return result;
    }

    // read(): no argument at all, so there's nothing to (mis)mutate - a
    // fresh register just receives whatever READ produces
    private Integer compileRead(FunCall funCall) {
        if (!isBuiltinCall(funCall, "read", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.READ, result);
        return result;
    }

    // isInt(x) etc: expands to typeOf(x) == "<expectedType>" rather than
    // needing one opcode per predicate - TYPE_OF is the actual primitive,
    // these are just compile-time sugar over it
    private Integer compileTypeCheck(FunCall funCall, String name, String expectedType) {
        if (!isBuiltinCall(funCall, name, 1))
            return null;

        int argReg = compileInner(funCall.args.get(0));
        int typeReg = regIndex++;
        _emit(OpCodes.MOV, typeReg, argReg);
        _emit(OpCodes.TYPE_OF, typeReg);

        int expectedReg = regIndex++;
        _emit(OpCodes.LOAD_CONST, expectedReg, constant(expectedType));

        int result = regIndex++;
        _emit(OpCodes.MOV, result, typeReg);
        _emit(OpCodes.EQ, result, expectedReg);
        return result;
    }

    // tries every call-syntax built-in in turn; null means funCall is an
    // ordinary user function call
    // obj.method(args): resolves the method against obj's *actual* runtime
    // class (LOOKUP_METHOD), then calls it with 'this' prepended to args -
    // shared by both obj.method(...) call sites and new ClassName(...)'s
    // implicit init(...) call
    private int compileMethodCall(int objReg, String methodName, List<Node> argNodes) {
        int[] valRegs = new int[argNodes.size()];
        for (int i = 0; i < argNodes.size(); i++)
            valRegs[i] = compileInner(argNodes.get(i));

        int methodReg = regIndex++;
        _emit(OpCodes.MOV, methodReg, objReg);
        _emit(OpCodes.LOOKUP_METHOD, methodReg, constant(methodName));

        int startReg = regIndex++;
        _emit(OpCodes.MOV, regIndex, objReg); // 'this'
        regIndex++;
        for (var val : valRegs) {
            _emit(OpCodes.MOV, regIndex, val);
            regIndex++;
        }

        _emit(OpCodes.CALL, methodReg, startReg);
        return startReg;
    }

    private Integer tryCompileBuiltin(FunCall funCall) {
        Integer r;
        if ((r = compileBuiltinUnaryCall(funCall, "len", OpCodes.LENGTH)) != null) return r;
        if ((r = compileBuiltinUnaryCall(funCall, "int", OpCodes.TO_INT)) != null) return r;
        if ((r = compileBuiltinUnaryCall(funCall, "float", OpCodes.TO_FLOAT)) != null) return r;
        if ((r = compileBuiltinUnaryCall(funCall, "str", OpCodes.TO_STRING)) != null) return r;
        if ((r = compileBuiltinUnaryCall(funCall, "typeOf", OpCodes.TYPE_OF)) != null) return r;
        if ((r = compileBuiltinUnaryCall(funCall, "err", OpCodes.MAKE_ERR)) != null) return r;
        if ((r = compilePush(funCall)) != null) return r;
        if ((r = compilePop(funCall)) != null) return r;
        if ((r = compileRead(funCall)) != null) return r;
        if ((r = compileTypeCheck(funCall, "isInt", "int")) != null) return r;
        if ((r = compileTypeCheck(funCall, "isFloat", "float")) != null) return r;
        if ((r = compileTypeCheck(funCall, "isString", "string")) != null) return r;
        if ((r = compileTypeCheck(funCall, "isBool", "bool")) != null) return r;
        if ((r = compileTypeCheck(funCall, "isArray", "array")) != null) return r;
        if ((r = compileTypeCheck(funCall, "isNil", "nil")) != null) return r;
        if ((r = compileTypeCheck(funCall, "isChar", "char")) != null) return r;
        if ((r = compileTypeCheck(funCall, "isTuple", "tuple")) != null) return r;
        if ((r = compileTypeCheck(funCall, "isErr", "err")) != null) return r;
        return null;
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
            Integer builtin = tryCompileBuiltin(funCall);
            if (builtin != null) return builtin;

            if (funCall.left instanceof FieldAccessNode fieldAccess) {
                // ClassName.method(args): resolved entirely at compile
                // time (the "receiver" is a literal class name, not a
                // runtime value), so this is just an ordinary call to the
                // mangled "ClassName.method" function - no LOOKUP_METHOD,
                // no 'this'
                if (fieldAccess.target instanceof IdentityNode idNode && classMapping.containsKey(idNode.name)) {
                    String mangledName = idNode.name + "." + fieldAccess.fieldName;
                    Integer staticConstIndex = functionMapping.get(mangledName);
                    if (staticConstIndex == null)
                        throw new Bl0j_CompilerException(
                                "class " + idNode.name + " has no static method '" + fieldAccess.fieldName + "'");

                    int[] staticValRegs = new int[funCall.args.size()];
                    for (int i = 0; i < funCall.args.size(); i++)
                        staticValRegs[i] = compileInner(funCall.args.get(i));

                    int staticMethodReg = regIndex++;
                    _emit(OpCodes.LOAD_CONST, staticMethodReg, staticConstIndex);

                    int staticStartReg = regIndex++;
                    for (var val : staticValRegs) {
                        _emit(OpCodes.MOV, regIndex, val);
                        regIndex++;
                    }

                    _emit(OpCodes.CALL, staticMethodReg, staticStartReg);
                    return staticStartReg;
                }

                int objReg = compileInner(fieldAccess.target);
                return compileMethodCall(objReg, fieldAccess.fieldName, funCall.args);
            }

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

        if(node instanceof ArrayLiteralNode arrayLiteral){
            int[] valRegs = new int[arrayLiteral.elements.size()];

            for(int i=0;i<arrayLiteral.elements.size();i++)
                valRegs[i] = compileInner(arrayLiteral.elements.get(i));

            // mirrors FunCall: elements are copied into consecutive
            // registers right after the register that will hold the result
            int startReg = regIndex++;

            for(var val : valRegs) {
                _emit(OpCodes.MOV, regIndex, val);
                regIndex++;
            }

            _emit(OpCodes.NEW_ARRAY, startReg, arrayLiteral.elements.size());
            return startReg;
        }

        if(node instanceof TupleNode tupleNode){
            int[] valRegs = new int[tupleNode.values.size()];

            for(int i=0;i<tupleNode.values.size();i++)
                valRegs[i] = compileInner(tupleNode.values.get(i));

            int startReg = regIndex++;

            for(var val : valRegs) {
                _emit(OpCodes.MOV, regIndex, val);
                regIndex++;
            }

            _emit(OpCodes.NEW_TUPLE, startReg, tupleNode.values.size());
            return startReg;
        }

        if(node instanceof DestructuringAssignNode destr){
            int rightReg = compileInner(destr.right);
            int arity = destr.targets.size();

            int base = regIndex++;
            _emit(OpCodes.MOV, base, rightReg);
            regIndex += arity; // reserved for UNPACK's output, filled below

            _emit(OpCodes.UNPACK, base, arity);

            for (int i = 0; i < arity; i++) {
                int targetReg = compileInner(destr.targets.get(i));
                _emit(OpCodes.MOV, targetReg, base + 1 + i);
            }

            return base;
        }

        if(node instanceof IndexNode indexNode){
            int result = regIndex++;
            int arrReg = compileInner(indexNode.left);
            int indexReg = compileInner(indexNode.index);

            _emit(OpCodes.MOV, result, arrReg);
            _emit(OpCodes.INDEX_GET, result, indexReg);

            return result;
        }

        if(node instanceof FieldAccessNode fieldAccess){
            int objReg = compileInner(fieldAccess.target);
            int result = regIndex++;
            _emit(OpCodes.MOV, result, objReg);
            _emit(OpCodes.GET_FIELD, result, constant(fieldAccess.fieldName));
            return result;
        }

        if(node instanceof NewNode newNode){
            ClassInfo info = classMapping.get(newNode.className);
            if (info == null)
                throw new Bl0j_CompilerException("unknown class: " + newNode.className);

            int instanceReg = regIndex++;
            _emit(OpCodes.LOAD_CONST, instanceReg, info.constIndex());
            _emit(OpCodes.NEW_INSTANCE, instanceReg); // class-ref in, instance-ref out

            if (info.hasInit())
                compileMethodCall(instanceReg, "init", newNode.args); // return value discarded

            return instanceReg;
        }

        if(node instanceof PROGRAM_N programNode){
            for(var n : programNode.nodes)
                compileInner(n);
            return -1;
        }

        if(node instanceof FunNode)
            return -1;

        if(node instanceof ClassNode)
            return -1; // its methods were already queued by fetchFunctions


        if(node instanceof WhileNode whileNode){
            int startJump = _instr_len();
            int condReg = compileInner(whileNode.condition);

            int patchJumpIfNot = _emit(OpCodes.JUMP_IF_NOT, condReg) - 1;

            compileInner(whileNode.body); // -1

            _emit(OpCodes.JUMP, startJump);
            bytecode.set(patchJumpIfNot, _instr_len());

            return -1;
        }

        if(node instanceof ForNode forNode){
            // identical to WhileNode, except init runs once up front and
            // update runs at the end of every iteration, right before the
            // jump back to the condition check
            compileInner(forNode.init);

            int startJump = _instr_len();
            int condReg = compileInner(forNode.condition);

            int patchJumpIfNot = _emit(OpCodes.JUMP_IF_NOT, condReg) - 1;

            compileInner(forNode.body);
            compileInner(forNode.update);

            _emit(OpCodes.JUMP, startJump);
            bytecode.set(patchJumpIfNot, _instr_len());

            return -1;
        }

        if(node instanceof TryNode tryNode){
            int errReg = map(tryNode.catchVarName);

            // b (the catch address) is patched once we know where the
            // catch block actually starts, same pattern as if/ternary
            int patchCatchAddr = _emit(OpCodes.TRY_ENTER, errReg) - 1;

            compileInner(tryNode.tryBody);
            _emit(OpCodes.TRY_EXIT);

            int patchJumpOverCatch = _emit(OpCodes.JUMP) - 2;
            bytecode.set(patchCatchAddr, _instr_len());

            compileInner(tryNode.catchBody);
            bytecode.set(patchJumpOverCatch, _instr_len());

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
            if (node instanceof FloatNode f)
                constIndex = constant(f.value);
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
                if (n.left instanceof IndexNode indexNode) {
                    int arrReg = compileInner(indexNode.left);
                    int indexRegRaw = compileInner(indexNode.index);
                    int valueRegRaw = compileInner(n.right);

                    // index and value need to sit in two consecutive
                    // registers (mirrors INDEX_GET's own 2-operand limit)
                    int base = regIndex++;
                    _emit(OpCodes.MOV, base, indexRegRaw);
                    _emit(OpCodes.MOV, regIndex, valueRegRaw);
                    regIndex++;

                    _emit(OpCodes.INDEX_SET, arrReg, base);
                    return base + 1;
                }

                if (n.left instanceof FieldAccessNode fieldAccess) {
                    int objReg = compileInner(fieldAccess.target);
                    int valueRegRaw = compileInner(n.right);

                    // the field name's const index and the value need to
                    // sit in two consecutive registers, same trick as
                    // INDEX_SET above (SET_FIELD only has 2 operand slots
                    // but needs object + name + value)
                    int base = regIndex++;
                    _emit(OpCodes.SET, base, constant(fieldAccess.fieldName));
                    _emit(OpCodes.MOV, regIndex, valueRegRaw);
                    regIndex++;

                    _emit(OpCodes.SET_FIELD, objReg, base);
                    return base + 1;
                }

                int varReg = compileInner(n.left);
                int valueReg = compileInner(n.right);
                _emit(OpCodes.MOV, varReg, valueReg);
                return varReg;
            }

            // short-circuit: unlike every other binary operator here, the
            // right side must not even be evaluated once the left side
            // already decides the result (this is what makes
            // 'i < len(arr) && arr[i] == x' safe to write)
            if (n.op == Operator.AND || n.op == Operator.OR) {
                int result = regIndex++;
                int leftReg = compileInner(n.left);
                _emit(OpCodes.MOV, result, leftReg);

                byte shortCircuitJump = n.op == Operator.AND ? OpCodes.JUMP_IF_NOT : OpCodes.JUMP_IF;
                int patchJump = _emit(shortCircuitJump, result) - 1;

                int rightReg = compileInner(n.right);
                _emit(OpCodes.MOV, result, rightReg);
                bytecode.set(patchJump, _instr_len());

                return result;
            }

            int result = regIndex++;
            int left  = compileInner(n.left);
            int right = compileInner(n.right);

            // a <= b  and  a >= b  reuse GREATER/LESS the same way NOT_EQUALS
            // reuses EQ: compute the opposite comparison, then negate it.
            byte op = switch (n.op) {
                case PLUS -> OpCodes.LR_ADD;
                case MINUS -> OpCodes.LR_SUB;
                case STAR -> OpCodes.LR_MUL;
                case STAR_STAR -> OpCodes.LR_POW;
                case DIV -> OpCodes.LR_DIV;
                case REMAINDER ->  OpCodes.LR_REM;
                case EQUALS, NOT_EQUALS -> OpCodes.EQ;
                case LESS -> OpCodes.LESS;
                case GREATER_EQUALS -> OpCodes.LESS;
                case GREATER -> OpCodes.GREATER;
                case LESS_EQUALS -> OpCodes.GREATER;
                case BIT_AND -> OpCodes.LR_AND;
                case BIT_OR -> OpCodes.LR_OR;
                case BIT_XOR -> OpCodes.LR_XOR;
                case SHIFT_LEFT -> OpCodes.LR_SHL;
                case SHIFT_RIGHT -> OpCodes.LR_SHR;
                default -> throw new Bl0j_CompilerException("Unknown op: " + n.op);
            };

            _emit(OpCodes.MOV, result, left);
            _emit(op, result, right);

            if(n.op == Operator.NOT_EQUALS || n.op == Operator.LESS_EQUALS || n.op == Operator.GREATER_EQUALS)
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
                    case BIT_NOT -> OpCodes.BIT_NOT;
                    default -> throw new Bl0j_CompilerException("Unknown op: " + u.op);
                };

                // unlike ++/-- this must not mutate the operand in place:
                // if l.left is a bare variable, reg IS that variable's own
                // register, and NEG/NOT would otherwise corrupt it (-x
                // would silently also change x)
                int result = regIndex++;
                _emit(OpCodes.MOV, result, reg);
                _emit(op, result);

                return result;
            }
        }

        throw new Bl0j_CompilerException("Unexpected node type: " + node);
    }

    // instructions are fixed-width [opcode, a, b], each a single byte, so
    // every operand (register index, constant index, jump address, native
    // id, ...) must fit in 0..255 - silently truncating a larger value would
    // corrupt registers or jump targets instead of failing loudly
    private static final int MAX_BYTE_OPERAND = 0xFF;

    private void checkOperand(int value, String what) {
        if (value < 0 || value > MAX_BYTE_OPERAND)
            throw new Bl0j_CompilerException(
                    what + " (" + value + ") exceeds the byte-sized bytecode format's limit of " + MAX_BYTE_OPERAND);
    }

    private void  _emit(int op, int a, int b) {
        checkOperand(a, "operand 'a'");
        checkOperand(b, "operand 'b'");
        bytecode.add((byte) op);
        bytecode.add((byte) a);
        bytecode.add((byte) b);
    }

    private int _emit(int op, int a) {
        checkOperand(a, "operand 'a'");
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
        int len = bytecode.size() / 3;
        checkOperand(len, "instruction address");
        return (byte) len;
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
                    case Double d -> {
                        dos.writeByte(Constants.FLOAT);
                        dos.writeDouble(d);
                    }
                    case ClassDef cd -> {
                        dos.writeByte(Constants.CLASS);
                        byte[] nameBytes = cd.name().getBytes(StandardCharsets.UTF_8);
                        dos.writeShort(nameBytes.length);
                        dos.write(nameBytes);

                        dos.writeShort(cd.fieldNames().size());
                        for (String field : cd.fieldNames()) {
                            byte[] fieldBytes = field.getBytes(StandardCharsets.UTF_8);
                            dos.writeShort(fieldBytes.length);
                            dos.write(fieldBytes);
                        }

                        dos.writeShort(cd.methodNames().size());
                        for (int i = 0; i < cd.methodNames().size(); i++) {
                            byte[] methodBytes = cd.methodNames().get(i).getBytes(StandardCharsets.UTF_8);
                            dos.writeShort(methodBytes.length);
                            dos.write(methodBytes);
                            dos.writeShort(cd.methodConstIndices().get(i));
                        }
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
