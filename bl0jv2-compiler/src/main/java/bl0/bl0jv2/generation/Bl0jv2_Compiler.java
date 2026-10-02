package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.C;
import bl0.bl0jv2.data.ClassDef;
import bl0.bl0jv2.data.ExternDef;
import bl0.bl0jv2.data.Constants;
import bl0.bl0jv2.data.FunDef;
import bl0.bl0jv2.data.NativeMethods;
import bl0.bl0jv2.data.OpCodes;
import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.generation.nodes.BinaryNode;
import bl0.bl0jv2.generation.nodes.Node;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.generation.nodes.RegValueNode;
import bl0.bl0jv2.generation.nodes.data.*;
import bl0.bl0jv2.generation.nodes.statements.*;
import bl0.bl0jv2.generation.nodes.unary.LUnaryNode;
import bl0.bl0jv2.generation.nodes.unary.RUnaryNode;
import bl0.bl0jv2.generation.nodes.unary.UnaryNode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Bl0jv2_Compiler {
    private final List<Byte> bytecode = new ArrayList<>();
    private final List<Object> constants = new ArrayList<>();
    private int regIndex = 0;
    private int regCount = 0;

    // a function/lambda/method queued for its body to be compiled once all
    // of its own leading capture names (if any) are already known.
    // captureCount of the first N declared params are captured cells, not
    // ordinary caller-supplied arguments; enclosingChain is the lexical
    // scope chain that was live when a lambda literal was discovered
    // (null for a regular function/method, which has no enclosing scope
    // to close over - matches this language's existing "no nested def"
    // rule).
    private record PendingFunction(FunNode fun, int captureCount, List<FunctionScope> enclosingChain) {
        PendingFunction(FunNode fun) {
            this(fun, 0, null);
        }
    }

    private final List<PendingFunction> lazy_functions = new ArrayList<>();
    private final HashMap<String, Integer> functionMapping = new HashMap<>();

    // one lexical scope per function/lambda currently reachable while
    // compiling - only ever grown/shrunk at the top of each
    // compileFunctions() iteration (see there), never nested within a
    // single recursive compileInner call, since lambda bodies are always
    // compiled later, as their own separate iteration
    private static final class FunctionScope {
        final Map<String, Integer> identityMapping = new HashMap<>();
        // names captured by some nested lambda, or (for a lambda's own
        // scope) names that ARE its own captured leading parameters
        final Set<String> cellNames = new HashSet<>();
        // exempted from 'boxed's blanket cell treatment - currently just
        // a try/catch error variable, whose value the VM writes directly
        // into its register on a caught error, bypassing any cell
        final Set<String> plainNames = new HashSet<>();
        // true when this function/lambda's own body contains a lambda
        // anywhere (at any depth) - decided once, up front, so that a
        // local later found to be captured never needs its earlier
        // (already-compiled) accesses retroactively rewritten
        final boolean boxed;
        // names read while they had no binding yet - each is an error unless
        // something later in the same function assigns it (a loop may
        // legitimately read a variable above the line that first sets it).
        // Checked once the whole body has been compiled.
        final Set<String> suspectReads = new LinkedHashSet<>();
        // literals loaded once before the loop being compiled (value -> register): inside the
        // loop they cost nothing per iteration. Registers are written once and never again.
        final Map<Object, Integer> hoisted = new HashMap<>();

        FunctionScope(boolean boxed) {
            this.boxed = boxed;
        }

        boolean isCell(String name) {
            if (plainNames.contains(name)) return false;
            return boxed || cellNames.contains(name);
        }
    }

    private final List<FunctionScope> scopeChain = new ArrayList<>();

    private FunctionScope currentScope() {
        return scopeChain.get(scopeChain.size() - 1);
    }

    private record VarRef(int reg, boolean isCell) {}

    private int lambdaCounter = 0;

    // className -> (its constant-pool index, whether it declares 'init',
    // and its static field names in declaration order - resolved to a
    // compile-time index the same way a local variable resolves to a
    // register, since 'ClassName.field' is always a literal class name).
    // constFieldNames never leaves the compiler - const-ness is a
    // compile-time-only check (see compileAssign), not part of the
    // compiled ClassDef.
    private final HashMap<String, ClassInfo> classMapping = new HashMap<>();
    // fieldNames/methodArity describe the INSTANCE side (arity excludes the
    // implicit 'this'); staticMethodArity the 'static def' side. initArity
    // is -1 when the class declares no init(). All of it exists purely so
    // the compiler can reject a misspelled member or a wrong argument count
    // instead of leaving that to a runtime error.
    private record ClassInfo(String name, int constIndex, boolean hasInit, List<String> staticFieldNames, List<String> constFieldNames,
                             Set<String> fieldNames, Map<String, Integer> methodArity, Map<String, Integer> staticMethodArity, int initArity) {
        int staticFieldIndex(String fieldName) {
            return staticFieldNames.indexOf(fieldName);
        }
    }

    // which class (if any) owns the method currently being compiled in
    // compileFunctions(), and whether it's literally that class's own
    // init() - derived from the mangled "ClassName.methodName" FunNode
    // name, the same way fetchFunctions() already strips the class-name
    // prefix. Used only to enforce 'const field' (see compileAssign).
    private String currentClassName;
    private boolean currentMethodIsInit;

    // a FieldAccessNode's target is a "ClassName.thing" static access (as
    // opposed to an instance access) exactly when it's a bare identifier
    // naming a known class - shared by static method calls and static
    // field read/write, which all resolve entirely at compile time on that
    // basis
    private ClassInfo staticTargetOf(Node target) {
        if (!(target instanceof IdentityNode idNode))
            return null;
        return classMapping.get(idNode.name);
    }

    // parameter count of every top-level function, by name
    private final Map<String, Integer> functionArity = new HashMap<>();
    // every instance field name declared by ANY class, and for every
    // instance method name the set of arities some class declares it with -
    // what an access through a receiver of unknown class is checked against
    private final Set<String> allFieldNames = new HashSet<>();
    private final Map<String, Set<Integer>> allMethodArities = new HashMap<>();
    // the function whose body is being compiled, null at the top level
    private String currentFunctionName;

    private Bl0j_CompilerException err(String message) {
        String where;
        if (currentFunctionName == null) where = "at top level";
        else if (currentFunctionName.startsWith("<lambda")) where = "in a lambda";
        else where = "in function " + currentFunctionName;
        return new Bl0j_CompilerException(where + ": " + message);
    }

    private static String argumentCount(int n) {
        return n + " argument" + (n == 1 ? "" : "s");
    }

    // number of TRY_ENTERs currently open (incremented/decremented only
    // around a try body, not its catch body - see TryNode below)
    private int tryDepth = 0;

    // innermost loop on top; break/continue patch into whichever loop is
    // currently being compiled rather than jumping to a fixed address,
    // since that address isn't known until the loop finishes compiling.
    // tryDepthAtStart lets break/continue know how many TRY_ENTERs were
    // opened *inside* this loop (as opposed to enclosing it) and so need a
    // matching TRY_EXIT before jumping out, the same way RETURN unwinds
    // handlers registered inside the frame it's leaving.
    private final ArrayDeque<LoopContext> loopStack = new ArrayDeque<>();
    private record LoopContext(List<Integer> breakPatches, List<Integer> continuePatches, int tryDepthAtStart) {
        LoopContext(int tryDepthAtStart) {
            this(new ArrayList<>(), new ArrayList<>(), tryDepthAtStart);
        }
    }

    public Bl0jv2_Compiler() {}

    public byte[] compile(Node node) {
        bytecode.clear();
        constants.clear();
        lazy_functions.clear();
        functionMapping.clear();
        classMapping.clear();
        loopStack.clear();
        tryDepth = 0;
        scopeChain.clear();
        lambdaCounter = 0;
        currentClassName = null;
        currentMethodIsInit = false;
        currentFunctionName = null;
        functionArity.clear();
        allFieldNames.clear();
        allMethodArities.clear();
        regIndex = 0;
        regCount = 0;

        if(!(node instanceof PROGRAM_N program))
            throw new Bl0j_CompilerException("node is not a ProgramNode");

        // the top-level program is itself a scope, exactly like any
        // function's body - so a lambda written directly at the top level
        // has an enclosing scope to close over, same as one inside a
        // named function
        scopeChain.add(new FunctionScope(containsLambda(program)));

        fetchFunctions(program);
        FunctionScope topScope = currentScope();
        compileInner(program);
        checkNoUndefinedReads(topScope);
        _emit(OpCodes.HALT);
        compileFunctions();

        return build_header_bytecode();
    }

    // -1 = no default (the field starts nil) - both when no initializer
    // was written at all, and when it was explicitly '= nil': there's no
    // NIL constant-pool entry type to point at (LOAD_NIL is its own
    // opcode, not a constant), so the two cases collapse into one sentinel
    private int fieldDefaultConstIndex(Node defaultNode) {
        if (defaultNode == null || defaultNode instanceof NilNode)
            return -1;
        if (defaultNode instanceof NumberNode n) return constant(n.value);
        if (defaultNode instanceof FloatNode f) return constant(f.value);
        if (defaultNode instanceof StringNode s) return constant(s.value);
        if (defaultNode instanceof BooleanNode b) return constant(b.value);
        throw new Bl0j_CompilerException("unexpected field default node: " + defaultNode);
    }

    // names a shared library declares (see Bl0jv2_Linker): defining one of them here too would
    // make two different things with one name
    private final Set<String> externalNames = new HashSet<>();
    private final Set<String> localNames = new HashSet<>();

    // the constant-pool index of a function; a shared library's function gets its EXTERN
    // constant when first referred to, so a program carries only the names it actually uses
    private Integer functionConst(String name) {
        Integer index = functionMapping.get(name);
        if (index != null && index == -1) {
            index = constant(new ExternDef(name));
            functionMapping.put(name, index);
        }
        return index;
    }

    private final Map<String, Integer> externClassConsts = new HashMap<>();

    private int classConst(ClassInfo info) {
        if (info.constIndex() != -1) return info.constIndex();
        return externClassConsts.computeIfAbsent(info.name(), n -> constant(new ExternDef(n)));
    }

    private void checkNotDefinedTwice(String name, boolean external) {
        if (external) {
            if (localNames.contains(name))
                throw new Bl0j_CompilerException("'" + name + "' is defined here and by a shared library");
            externalNames.add(name);
        } else {
            if (externalNames.contains(name))
                throw new Bl0j_CompilerException("'" + name + "' is defined here and by a shared library");
            localNames.add(name);
        }
    }

    private void fetchFunctions(PROGRAM_N program) {
        for(var node : program.nodes) {
            if(node instanceof FunNode funNode){
                checkNotDefinedTwice(funNode.name, funNode.external);
                int constIndex;
                if (funNode.external) {
                    // a shared library's function: nothing to compile, the loader links it by name
                    constIndex = -1;   // the constant is made when something refers to it (see functionConst)
                } else {
                    lazy_functions.add(new PendingFunction(funNode));
                    constIndex = constant(new FunDef(funNode.name, -1, (short)0, (short)0));
                }
                functionMapping.put(funNode.name, constIndex);
                functionArity.put(funNode.name, funNode.args.args.size());
            }

            // a class's methods are registered exactly like top-level
            // functions (they already carry a "ClassName.method" name and
            // an implicit 'this' parameter from the parser) - the class
            // itself is then a ClassDef constant that just points at each
            // method's own FunDef constant by index
            if (node instanceof ClassNode classNode) {
                checkNotDefinedTwice(classNode.name, classNode.external);
                List<String> methodNames = new ArrayList<>();
                List<Integer> methodConstIndices = new ArrayList<>();
                boolean hasInit = false;
                Map<String, Integer> methodArity = new HashMap<>();
                int initArity = -1;

                for (FunNode method : classNode.methods) {
                    int constIndex = -1;
                    if (!classNode.external) {
                        lazy_functions.add(new PendingFunction(method));
                        constIndex = constant(new FunDef(method.name, -1, (short)0, (short)0));
                        functionMapping.put(method.name, constIndex);
                    }

                    String plainName = method.name.substring(classNode.name.length() + 1);
                    methodNames.add(plainName);
                    methodConstIndices.add(constIndex);
                    if (plainName.equals("init")) hasInit = true;

                    int arity = method.args.args.size() - 1; // minus the implicit 'this'
                    methodArity.put(plainName, arity);
                    allMethodArities.computeIfAbsent(plainName, k -> new HashSet<>()).add(arity);
                    if (plainName.equals("init")) initArity = arity;
                }
                allFieldNames.addAll(classNode.fieldNames);

                int classConstIndex;
                if (classNode.external) {
                    classConstIndex = -1;   // made on first use (see classConst)
                } else {
                    List<Integer> fieldDefaultConstIndices = new ArrayList<>();
                    for (Node defaultNode : classNode.fieldDefaultNodes)
                        fieldDefaultConstIndices.add(fieldDefaultConstIndex(defaultNode));
                    classConstIndex = constant(new ClassDef(classNode.name, classNode.fieldNames, fieldDefaultConstIndices, methodNames, methodConstIndices, classNode.staticFieldNames.size()));
                }
                Map<String, Integer> staticMethodArity = new HashMap<>();
                for (FunNode staticMethod : classNode.staticMethods)
                    staticMethodArity.put(staticMethod.name.substring(classNode.name.length() + 1), staticMethod.args.args.size());

                classMapping.put(classNode.name, new ClassInfo(classNode.name, classConstIndex, hasInit, classNode.staticFieldNames, classNode.constFieldNames,
                        new HashSet<>(classNode.fieldNames), methodArity, staticMethodArity, initArity));

                // static methods are registered as plain functions under
                // their mangled name and resolved entirely at compile time
                // (ClassName.method(...)) - they never go through runtime
                // instance dispatch, so they're deliberately left out of
                // the ClassDef's own method table above
                for (FunNode staticMethod : classNode.staticMethods) {
                    int constIndex;
                    if (classNode.external) {
                        constIndex = -1;
                    } else {
                        lazy_functions.add(new PendingFunction(staticMethod));
                        constIndex = constant(new FunDef(staticMethod.name, -1, (short)0, (short)0));
                    }
                    functionMapping.put(staticMethod.name, constIndex);
                }
            }
        }
    }

    // index-based (not for-each): compiling one function's body can itself
    // discover a lambda literal and append a new PendingFunction to
    // lazy_functions, which this loop must still pick up
    private void compileFunctions(){
        regCount = regIndex;
        int adress;
        int arity;

        for (int idx = 0; idx < lazy_functions.size(); idx++) {
            PendingFunction pending = lazy_functions.get(idx);
            FunNode fun = pending.fun();
            currentFunctionName = fun.name;

            // a regular function/method has no enclosing scope (this
            // language has no nested 'def'); a lambda's own chain was
            // snapshotted at the point it was discovered, inside whatever
            // was compiling it at the time
            scopeChain.clear();
            if (pending.enclosingChain() != null)
                scopeChain.addAll(pending.enclosingChain());
            FunctionScope scope = new FunctionScope(containsLambda(fun.body));
            scopeChain.add(scope);

            // a mangled "ClassName.methodName" name (only ever true for
            // instance/static methods, never a plain function or a
            // "<lambda:N>" name - neither contains a class-name prefix
            // that's actually in classMapping) - used only to enforce
            // 'const field' (this.field = ... allowed only inside init())
            int dot = fun.name.indexOf('.');
            if (dot >= 0 && classMapping.containsKey(fun.name.substring(0, dot))) {
                currentClassName = fun.name.substring(0, dot);
                currentMethodIsInit = fun.name.substring(dot + 1).equals("init");
            } else {
                currentClassName = null;
                currentMethodIsInit = false;
            }

            regIndex = 1; // first reg for return value

            adress = _instr_len();
            arity = fun.args.args.size();

            for (int i = 0; i < fun.args.args.size(); i++) {
                String argName = fun.args.args.get(i);
                boolean isCaptured = i < pending.captureCount();

                // mark BEFORE resolve() allocates it, so isCell() already
                // reports the truth about this incoming value
                if (isCaptured) scope.cellNames.add(argName);
                VarRef ref = resolve(argName);

                // a regular param of a scope that boxes everything: the
                // incoming value is raw (CALL just copies it in), so wrap
                // it in a fresh cell right away. A captured param's
                // incoming value is already a cell reference - MAKE_CLOSURE
                // packed the real cell in, nothing more to do here.
                if (!isCaptured && ref.isCell()) {
                    int temp = regIndex++;
                    _emit(OpCodes.MOV, temp, ref.reg());
                    _emit(OpCodes.MAKE_CELL, ref.reg());
                    _emit(OpCodes.CELL_SET, ref.reg(), temp);
                }
            }

            int bodyStart = adress * C.INSTR_WIDTH;
            compileInner(fun.body);
            checkNoUndefinedReads(scope);
            // an empty body emits nothing, so there is no instruction of
            // *this* function to inspect - peeking at the last instruction's
            // opcode byte would read the tail of whatever was emitted
            // before it (e.g. another function's own RETURN) and could
            // wrongly skip this function's implicit return
            boolean bodyEmittedSomething = bytecode.size() > bodyStart;
            if(!bodyEmittedSomething || bytecode.get(bytecode.size() - C.INSTR_WIDTH) != OpCodes.RETURN)
                _emit(OpCodes.RETURN, 0);

            int constIndex = functionMapping.get(fun.name);
            // an instance method's first parameter is 'this' (see the parser's
            // method desugaring) - only a mangled "Class.method" name can
            // have one, so a lambda or plain function that merely names a
            // parameter 'this' is not mistaken for a method
            boolean receiver = dot >= 0 && classMapping.containsKey(fun.name.substring(0, dot))
                    && fun.args.args.size() > 0 && fun.args.args.get(0).equals("this");
            constants.set(constIndex, new FunDef(fun.name, adress, (short) arity, (short) regIndex, receiver));
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
        if (opcode == OpCodes.FREE) {
            _emit(OpCodes.MOV, result, argReg);
            _emit(opcode, result);
        } else {
            _emit(opcode, result, argReg);          // result = f(argReg): the argument is left alone
        }
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

    // read(): a real native method like print/println/wait, just callable
    // in expression position - CALL_NATIVE writes its result back into its
    // operand register, which here is a throwaway nil since read() itself
    // takes no argument
    private Integer compileRead(FunCall funCall) {
        if (!isBuiltinCall(funCall, "read", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.READ, result);
        return result;
    }

    // ticks(): a real value-producing native like read() - milliseconds
    // elapsed since the program started, see Bl0jv2_jVM's own TICKS
    // registration for why this is wall-clock, not instruction-count, based
    private Integer compileTicks(FunCall funCall) {
        if (!isBuiltinCall(funCall, "ticks", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.TICKS, result);
        return result;
    }

    // coreCount()/currentCore(): same zero-arg, value-producing shape as
    // ticks()/read()
    private Integer compileCoreCount(FunCall funCall) {
        if (!isBuiltinCall(funCall, "coreCount", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.CORE_COUNT, result);
        return result;
    }

    private Integer compileCurrentCore(FunCall funCall) {
        if (!isBuiltinCall(funCall, "currentCore", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.CURRENT_CORE, result);
        return result;
    }

    // newMutex(): zero-arg, value-producing, same shape as ticks()/read()
    private Integer compileNewMutex(FunCall funCall) {
        if (!isBuiltinCall(funCall, "newMutex", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.NEW_MUTEX, result);
        return result;
    }

    // lock(m)/unlock(m): 1-arg side-effecting native calls, exact same
    // shape (and the same clobber-avoidance MOV) as compileRaiseInterrupt -
    // without it, lock(myMutex) where myMutex is a plain variable would
    // silently clobber it to nil after the call
    private Integer compileLockMutex(FunCall funCall) {
        if (!isBuiltinCall(funCall, "lock", 1))
            return null;

        int mRegRaw = compileInner(funCall.args.get(0));
        int mReg = regIndex++;
        _emit(OpCodes.MOV, mReg, mRegRaw);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.LOCK_MUTEX, mReg);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    private Integer compileUnlockMutex(FunCall funCall) {
        if (!isBuiltinCall(funCall, "unlock", 1))
            return null;

        int mRegRaw = compileInner(funCall.args.get(0));
        int mReg = regIndex++;
        _emit(OpCodes.MOV, mReg, mRegRaw);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.UNLOCK_MUTEX, mReg);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    // newEvent(): zero-arg, value-producing - same shape as newMutex()
    private Integer compileNewEvent(FunCall funCall) {
        if (!isBuiltinCall(funCall, "newEvent", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.NEW_EVENT, result);
        return result;
    }

    // signalEvent(e): 1-arg, same clobber-avoidance MOV as lock(m)
    private Integer compileSignalEvent(FunCall funCall) {
        if (!isBuiltinCall(funCall, "signalEvent", 1))
            return null;

        int eRegRaw = compileInner(funCall.args.get(0));
        int eReg = regIndex++;
        _emit(OpCodes.MOV, eReg, eRegRaw);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.SIGNAL_EVENT, eReg);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    // raiseInterruptOn(core, vector): packs (core, vector) into a 2-element
    // array for the one-operand native, the same way waitEvent packs its
    // three. Returns nil.
    private Integer compileRaiseInterruptOn(FunCall funCall) {
        if (!isBuiltinCall(funCall, "raiseInterruptOn", 2))
            return null;

        int coreReg = compileInner(funCall.args.get(0));
        int vectorReg = compileInner(funCall.args.get(1));

        int startReg = regIndex++;
        _emit(OpCodes.MOV, regIndex, coreReg);
        regIndex++;
        _emit(OpCodes.MOV, regIndex, vectorReg);
        regIndex++;
        _emit(OpCodes.NEW_ARRAY, startReg, 2);

        int arrRef = regIndex++; // see compileWaitEvent on why the array is freed
        _emit(OpCodes.MOV, arrRef, startReg);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.RAISE_INTERRUPT_ON, startReg);
        _emit(OpCodes.FREE, arrRef);

        _emit(OpCodes.LOAD_NIL, startReg);
        return startReg;
    }

    // a builtin that is a native call taking 'argc' arguments and returning
    // its result: one operand goes in directly, several are packed into a
    // temporary array (a native has exactly one operand register), which is
    // freed again right after the call
    private Integer compileValueNative(FunCall funCall, String name, int argc, byte nativeId) {
        if (!isBuiltinCall(funCall, name, argc))
            return null;

        if (argc == 1) {
            int raw = compileInner(funCall.args.get(0));
            int reg = regIndex++;
            _emit(OpCodes.MOV, reg, raw);
            _emit(OpCodes.CALL_NATIVE, nativeId, reg);
            return reg;
        }

        int[] argRegs = new int[argc];
        for (int i = 0; i < argc; i++)
            argRegs[i] = compileInner(funCall.args.get(i));

        int startReg = regIndex++;
        for (int argReg : argRegs) {
            _emit(OpCodes.MOV, regIndex, argReg);
            regIndex++;
        }
        _emit(OpCodes.NEW_ARRAY, startReg, argc);

        int arrRef = regIndex++; // see compileWaitEvent on why the array is freed
        _emit(OpCodes.MOV, arrRef, startReg);
        _emit(OpCodes.CALL_NATIVE, nativeId, startReg);
        _emit(OpCodes.FREE, arrRef);
        return startReg;
    }

    // setTimer(ms, vector) / setInterval(ms, vector): value-producing (the
    // timer id). Both pack [ms, vector, periodic] into one array for the
    // one-operand native; the periodic flag is a compile-time constant.
    private Integer compileSetTimer(FunCall funCall, String name, int periodic) {
        if (!isBuiltinCall(funCall, name, 2))
            return null;

        int msReg = compileInner(funCall.args.get(0));
        int vectorReg = compileInner(funCall.args.get(1));
        int periodicReg = compileInner(new NumberNode(periodic));

        int startReg = regIndex++;
        for (int argReg : new int[]{msReg, vectorReg, periodicReg}) {
            _emit(OpCodes.MOV, regIndex, argReg);
            regIndex++;
        }
        _emit(OpCodes.NEW_ARRAY, startReg, 3);

        int arrRef = regIndex++; // see compileWaitEvent on why the array is freed
        _emit(OpCodes.MOV, arrRef, startReg);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.SET_TIMER, startReg);
        _emit(OpCodes.FREE, arrRef);
        return startReg;
    }

    // cancelTimer(id): 1-arg, value-producing (true if it was still pending)
    private Integer compileCancelTimer(FunCall funCall) {
        if (!isBuiltinCall(funCall, "cancelTimer", 1))
            return null;

        int idRegRaw = compileInner(funCall.args.get(0));
        int idReg = regIndex++;
        _emit(OpCodes.MOV, idReg, idRegRaw);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.CANCEL_TIMER, idReg);
        return idReg;
    }

    // eventGen(e): 1-arg, value-producing (the event's current generation) -
    // same shape as syscall's value-producing natives
    private Integer compileEventGen(FunCall funCall) {
        if (!isBuiltinCall(funCall, "eventGen", 1))
            return null;

        int eRegRaw = compileInner(funCall.args.get(0));
        int eReg = regIndex++;
        _emit(OpCodes.MOV, eReg, eRegRaw);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.EVENT_GEN, eReg);
        return eReg;
    }

    // waitEvent(e, gen, timeoutMs): a native call carries exactly one
    // operand register, so the three arguments are packed into a fresh
    // 3-element array first (laid out exactly like an ArrayLiteralNode's own
    // registers) and unpacked again inside the native. Result: true if the
    // event was signalled since 'gen' was read, false on timeout or a
    // pending interrupt.
    private Integer compileWaitEvent(FunCall funCall) {
        if (!isBuiltinCall(funCall, "waitEvent", 3))
            return null;

        int[] argRegs = new int[3];
        for (int i = 0; i < 3; i++)
            argRegs[i] = compileInner(funCall.args.get(i));

        int startReg = regIndex++;
        for (int argReg : argRegs) {
            _emit(OpCodes.MOV, regIndex, argReg);
            regIndex++;
        }
        _emit(OpCodes.NEW_ARRAY, startReg, 3);

        // the heap has no GC (only explicit free), so the packing array is
        // freed right after the call instead of leaking one slot per wait -
        // CALL_NATIVE overwrites startReg with the result, so the array's
        // reference is parked in its own register first
        int arrRef = regIndex++;
        _emit(OpCodes.MOV, arrRef, startReg);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.WAIT_EVENT, startReg);
        _emit(OpCodes.FREE, arrRef);
        return startReg;
    }

    // peek8(addr)/peek16(addr)/peek32(addr): the width is known at compile
    // time (which builtin name matched), so it's baked in as PEEK's b
    // operand directly - an immediate, not a register
    private Integer compilePeek(FunCall funCall, String name, int widthBits) {
        if (!isBuiltinCall(funCall, name, 1))
            return null;

        int addrReg = compileInner(funCall.args.get(0));
        int result = regIndex++;
        _emit(OpCodes.MOV, result, addrReg);
        _emit(OpCodes.PEEK, result, widthBits);
        return result;
    }

    // poke8(addr, value)/poke16(addr, value)/poke32(addr, value): width and
    // value are packed into two consecutive registers, same trick as
    // SET_FIELD (POKE only has 2 operand slots but needs address + width +
    // value)
    private Integer compilePoke(FunCall funCall, String name, int widthBits) {
        if (!isBuiltinCall(funCall, name, 2))
            return null;

        int addrReg = compileInner(funCall.args.get(0));
        int valueRegRaw = compileInner(funCall.args.get(1));

        int base = regIndex++;
        _emit(OpCodes.SET, base, widthBits);
        _emit(OpCodes.MOV, regIndex, valueRegRaw);
        regIndex++;

        _emit(OpCodes.POKE, addrReg, base);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    // in8(port)/in16(port)/in32(port): exact same shape as compilePeek -
    // a second, port-addressed bus, kept separate from PEEK's raw-memory
    // arena (see PortIO's own doc)
    private Integer compilePortIn(FunCall funCall, String name, int widthBits) {
        if (!isBuiltinCall(funCall, name, 1))
            return null;

        int portReg = compileInner(funCall.args.get(0));
        int result = regIndex++;
        _emit(OpCodes.MOV, result, portReg);
        _emit(OpCodes.PORT_IN, result, widthBits);
        return result;
    }

    // out8(port, value)/out16(port, value)/out32(port, value): exact same
    // shape as compilePoke
    private Integer compilePortOut(FunCall funCall, String name, int widthBits) {
        if (!isBuiltinCall(funCall, name, 2))
            return null;

        int portReg = compileInner(funCall.args.get(0));
        int valueRegRaw = compileInner(funCall.args.get(1));

        int base = regIndex++;
        _emit(OpCodes.SET, base, widthBits);
        _emit(OpCodes.MOV, regIndex, valueRegRaw);
        regIndex++;

        _emit(OpCodes.PORT_OUT, portReg, base);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    // disableInterrupts()/enableInterrupts(): zero-arg native calls, same
    // shape as read() - a throwaway nil input/output, called purely for
    // side effect on InterruptController's nesting-safe disable counter
    private Integer compileDisableInterrupts(FunCall funCall) {
        if (!isBuiltinCall(funCall, "disableInterrupts", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.DISABLE_INTERRUPTS, result);
        return result;
    }

    private Integer compileEnableInterrupts(FunCall funCall) {
        if (!isBuiltinCall(funCall, "enableInterrupts", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.ENABLE_INTERRUPTS, result);
        return result;
    }

    // dropToUserMode(): zero-arg, side-effecting, same shape as
    // disableInterrupts()/enableInterrupts()
    private Integer compileDropToUserMode(FunCall funCall) {
        if (!isBuiltinCall(funCall, "dropToUserMode", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.DROP_TO_USER_MODE, result);
        return result;
    }

    // isPrivileged(): zero-arg, value-producing, same shape as coreCount()
    private Integer compileIsPrivileged(FunCall funCall) {
        if (!isBuiltinCall(funCall, "isPrivileged", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.IS_PRIVILEGED, result);
        return result;
    }

    // haltCore(): zero-arg, side-effecting, same shape as disableInterrupts()
    private Integer compileHaltCore(FunCall funCall) {
        if (!isBuiltinCall(funCall, "haltCore", 0))
            return null;

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.HALT_CORE, result);
        return result;
    }

    // syscall(vector, arg): SYSCALL takes its two values directly in a/b,
    // no packing needed (same shape as reserve's addr/size) - but unlike
    // reserve, SYSCALL mutates a's own slot with the handler's return
    // value, so vector must first be copied into a fresh register (same
    // clobber-avoidance reasoning as compilePeek's MOV-before-PEEK)
    private Integer compileSyscall(FunCall funCall) {
        if (!isBuiltinCall(funCall, "syscall", 2))
            return null;

        int vectorRegRaw = compileInner(funCall.args.get(0));
        int argReg = compileInner(funCall.args.get(1));

        int result = regIndex++;
        _emit(OpCodes.MOV, result, vectorRegRaw);
        _emit(OpCodes.SYSCALL, result, argReg);
        return result;
    }

    // atomicAdd(addr, delta): ATOMIC_ADD takes its two values directly in
    // a/b like reserve, and mutates a's own slot to the pre-add value like
    // compileSyscall above
    private Integer compileAtomicAdd(FunCall funCall) {
        if (!isBuiltinCall(funCall, "atomicAdd", 2))
            return null;

        int addrRegRaw = compileInner(funCall.args.get(0));
        int deltaReg = compileInner(funCall.args.get(1));

        int result = regIndex++;
        _emit(OpCodes.MOV, result, addrRegRaw);
        _emit(OpCodes.ATOMIC_ADD, result, deltaReg);
        return result;
    }

    // atomicCas(addr, expected, newValue): [expected, newValue] packed into
    // two consecutive registers, same trick as poke's [width, value];
    // mutates a's own slot to the pre-swap value like compileAtomicAdd above
    private Integer compileAtomicCas(FunCall funCall) {
        if (!isBuiltinCall(funCall, "atomicCas", 3))
            return null;

        int addrRegRaw = compileInner(funCall.args.get(0));
        int expectedRegRaw = compileInner(funCall.args.get(1));
        int newValueRegRaw = compileInner(funCall.args.get(2));

        int result = regIndex++;
        _emit(OpCodes.MOV, result, addrRegRaw);

        int base = regIndex++;
        _emit(OpCodes.MOV, base, expectedRegRaw);
        _emit(OpCodes.MOV, regIndex, newValueRegRaw);
        regIndex++;

        _emit(OpCodes.ATOMIC_CAS, result, base);
        return result;
    }

    // panic(msg): unlike err(msg) (which produces an ordinary catchable
    // Bl0jError value), this throws Bl0j_VM_Panic - see its own javadoc for
    // why that's never caught by try/catch. Compiles like raiseInterrupt:
    // a 1-arg native call, evaluates to nil (though nothing after it ever
    // actually runs).
    private Integer compilePanic(FunCall funCall) {
        if (!isBuiltinCall(funCall, "panic", 1))
            return null;

        int msgRegRaw = compileInner(funCall.args.get(0));
        // CALL_NATIVE overwrites its own b operand with the native call's
        // return value, so a bare-variable argument must be copied into a
        // fresh register first - same reasoning as compileBuiltinUnaryCall.
        // Harmless here in practice (panic() never returns control to code
        // that could observe the clobbered original), but wrong to skip.
        int msgReg = regIndex++;
        _emit(OpCodes.MOV, msgReg, msgRegRaw);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.PANIC, msgReg);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    // reserve(addr, size): unlike poke's [width, value], RESERVE takes its
    // two values directly in a/b - no packing needed, there just aren't any
    // more values to fit in
    private Integer compileReserve(FunCall funCall) {
        if (!isBuiltinCall(funCall, "reserve", 2))
            return null;

        int addrReg = compileInner(funCall.args.get(0));
        int sizeReg = compileInner(funCall.args.get(1));
        _emit(OpCodes.RESERVE, addrReg, sizeReg);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    // raiseInterrupt(vector): a real native method like wait, just called
    // for its side effect - the interrupt doesn't fire synchronously, it's
    // only queued (see InterruptController); result is a throwaway nil
    private Integer compileRaiseInterrupt(FunCall funCall) {
        if (!isBuiltinCall(funCall, "raiseInterrupt", 1))
            return null;

        int vectorRegRaw = compileInner(funCall.args.get(0));
        // CALL_NATIVE overwrites its own b operand with the native call's
        // return value (here: nil, since raiseInterrupt returns null) - a
        // bare-variable argument must be copied into a fresh register
        // first, or the call silently clobbers that variable to nil. Same
        // reasoning as compileBuiltinUnaryCall's own comment.
        int vectorReg = regIndex++;
        _emit(OpCodes.MOV, vectorReg, vectorRegRaw);
        _emit(OpCodes.CALL_NATIVE, NativeMethods.RAISE_INTERRUPT, vectorReg);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    // registerHandler(fn, vector, priority): fn sits directly in
    // REGISTER_HANDLER's a operand (never mutated, so no MOV-to-fresh-
    // register needed, unlike a builtin that writes its result back into
    // a); vector and priority are packed into two consecutive fresh
    // registers, same trick as poke's [width, value]
    private Integer compileRegisterHandler(FunCall funCall) {
        if (!isBuiltinCall(funCall, "registerHandler", 3))
            return null;

        int fnReg = compileInner(funCall.args.get(0));
        int vectorRegRaw = compileInner(funCall.args.get(1));
        int priorityRegRaw = compileInner(funCall.args.get(2));

        int base = regIndex++;
        _emit(OpCodes.MOV, base, vectorRegRaw);
        _emit(OpCodes.MOV, regIndex, priorityRegRaw);
        regIndex++;

        _emit(OpCodes.REGISTER_HANDLER, fnReg, base);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
        return result;
    }

    // dispatch(fn, core, arg): hands fn off to run on a specific worker
    // core, fire-and-forget. Exact same shape as registerHandler - fn
    // directly in DISPATCH's a operand (never mutated), core and arg
    // packed into two consecutive fresh registers
    private Integer compileDispatch(FunCall funCall) {
        if (!isBuiltinCall(funCall, "dispatch", 3))
            return null;

        int fnReg = compileInner(funCall.args.get(0));
        int coreRegRaw = compileInner(funCall.args.get(1));
        int argRegRaw = compileInner(funCall.args.get(2));

        int base = regIndex++;
        _emit(OpCodes.MOV, base, coreRegRaw);
        _emit(OpCodes.MOV, regIndex, argRegRaw);
        regIndex++;

        _emit(OpCodes.DISPATCH, fnReg, base);

        int result = regIndex++;
        _emit(OpCodes.LOAD_NIL, result);
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
        _emit(OpCodes.TYPE_OF, typeReg, argReg);

        int expectedReg = regIndex++;
        _emit(OpCodes.LOAD_CONST, expectedReg, constant(expectedType));

        int result = regIndex++;
        _emit(OpCodes.EQ, typeReg, expectedReg, result);
        return result;
    }

    // tries every call-syntax built-in in turn; null means funCall is an
    // ordinary user function call
    // obj.method(args): resolves the method against obj's *actual* runtime
    // class (LOOKUP_METHOD), then calls it with 'this' prepended to args -
    // shared by both obj.method(...) call sites and new ClassName(...)'s
    // implicit init(...) call
    // 'known' is the receiver's class when the compiler can tell (this, or
    // a just-constructed instance), null otherwise. With a known class the
    // method and its argument count are checked exactly; with an unknown
    // receiver the check is whether ANY class declares such a method with
    // this many arguments - closed classes make a miss a certain typo.
    private void checkMethodCall(ClassInfo known, String methodName, int argc) {
        if (known != null) {
            Integer expected = known.methodArity().get(methodName);
            if (expected == null)
                throw err("class " + known.name() + " has no method '" + methodName + "'");
            if (expected != argc)
                throw err("method " + known.name() + "." + methodName + " expects " + argumentCount(expected) + ", got " + argc);
            return;
        }

        Set<Integer> declared = allMethodArities.get(methodName);
        if (declared == null)
            throw err("no class declares a method '" + methodName + "'");
        if (!declared.contains(argc))
            throw err("no method '" + methodName + "' takes " + argumentCount(argc) + " (declared with "
                    + declared.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")) + ")");
    }

    private void checkFieldAccess(Node receiver, String field) {
        ClassInfo known = knownReceiverClass(receiver);
        if (known != null) {
            if (!known.fieldNames().contains(field))
                throw err("class " + known.name() + " has no field '" + field + "'");
        } else if (!allFieldNames.contains(field)) {
            throw err("no class declares a field '" + field + "'");
        }
    }

    // the class a receiver expression is certainly an instance of, or null
    private ClassInfo knownReceiverClass(Node receiver) {
        if (receiver instanceof IdentityNode id && id.name.equals("this") && currentClassName != null)
            return classMapping.get(currentClassName);
        return null;
    }

    private int compileMethodCall(int objReg, String methodName, List<Node> argNodes, ClassInfo known) {
        checkMethodCall(known, methodName, argNodes.size());
        int startReg = regIndex;
        regIndex += 2 + argNodes.size();                       // the result, 'this', the arguments
        for (int i = 0; i < argNodes.size(); i++)
            moveInto(startReg + 2 + i, compileInner(argNodes.get(i)));

        int methodReg = regIndex++;
        _emit(OpCodes.LOOKUP_METHOD, objReg, constant(methodName), methodReg);
        _emit(OpCodes.MOV, startReg + 1, objReg); // 'this'

        _emit(OpCodes.CALL, methodReg, startReg, argNodes.size() + 1); // + 'this'
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
        if ((r = compileBuiltinUnaryCall(funCall, "free", OpCodes.FREE)) != null) return r;
        if ((r = compileReserve(funCall)) != null) return r;
        if ((r = compilePeek(funCall, "peek8", 8)) != null) return r;
        if ((r = compilePeek(funCall, "peek16", 16)) != null) return r;
        if ((r = compilePeek(funCall, "peek32", 32)) != null) return r;
        if ((r = compilePoke(funCall, "poke8", 8)) != null) return r;
        if ((r = compilePoke(funCall, "poke16", 16)) != null) return r;
        if ((r = compilePoke(funCall, "poke32", 32)) != null) return r;
        if ((r = compilePortIn(funCall, "in8", 8)) != null) return r;
        if ((r = compilePortIn(funCall, "in16", 16)) != null) return r;
        if ((r = compilePortIn(funCall, "in32", 32)) != null) return r;
        if ((r = compilePortOut(funCall, "out8", 8)) != null) return r;
        if ((r = compilePortOut(funCall, "out16", 16)) != null) return r;
        if ((r = compilePortOut(funCall, "out32", 32)) != null) return r;
        if ((r = compilePush(funCall)) != null) return r;
        if ((r = compilePop(funCall)) != null) return r;
        if ((r = compileRead(funCall)) != null) return r;
        if ((r = compileTicks(funCall)) != null) return r;
        if ((r = compileCoreCount(funCall)) != null) return r;
        if ((r = compileCurrentCore(funCall)) != null) return r;
        if ((r = compileNewMutex(funCall)) != null) return r;
        if ((r = compileLockMutex(funCall)) != null) return r;
        if ((r = compileUnlockMutex(funCall)) != null) return r;
        if ((r = compileNewEvent(funCall)) != null) return r;
        if ((r = compileSignalEvent(funCall)) != null) return r;
        if ((r = compileRaiseInterruptOn(funCall)) != null) return r;
        if ((r = compileValueNative(funCall, "throw", 1, NativeMethods.THROW)) != null) return r;
        if ((r = compileValueNative(funCall, "strSub", 3, NativeMethods.STR_SUB)) != null) return r;
        if ((r = compileValueNative(funCall, "strFind", 3, NativeMethods.STR_FIND)) != null) return r;
        if ((r = compileValueNative(funCall, "execMem", 3, NativeMethods.EXEC_MEM)) != null) return r;
        if ((r = compileValueNative(funCall, "memory", 3, NativeMethods.MEMORY)) != null) return r;
        if ((r = compileValueNative(funCall, "strChar", 1, NativeMethods.STR_CHAR)) != null) return r;
        if ((r = compileValueNative(funCall, "strUpper", 1, NativeMethods.STR_UPPER)) != null) return r;
        if ((r = compileValueNative(funCall, "strLower", 1, NativeMethods.STR_LOWER)) != null) return r;
        if ((r = compileValueNative(funCall, "strJoin", 2, NativeMethods.STR_JOIN)) != null) return r;
        if ((r = compileSetTimer(funCall, "setTimer", 0)) != null) return r;
        if ((r = compileSetTimer(funCall, "setInterval", 1)) != null) return r;
        if ((r = compileCancelTimer(funCall)) != null) return r;
        if ((r = compileEventGen(funCall)) != null) return r;
        if ((r = compileWaitEvent(funCall)) != null) return r;
        if ((r = compileRaiseInterrupt(funCall)) != null) return r;
        if ((r = compileRegisterHandler(funCall)) != null) return r;
        if ((r = compileDispatch(funCall)) != null) return r;
        if ((r = compileDisableInterrupts(funCall)) != null) return r;
        if ((r = compileEnableInterrupts(funCall)) != null) return r;
        if ((r = compileDropToUserMode(funCall)) != null) return r;
        if ((r = compileIsPrivileged(funCall)) != null) return r;
        if ((r = compileHaltCore(funCall)) != null) return r;
        if ((r = compileSyscall(funCall)) != null) return r;
        if ((r = compileAtomicAdd(funCall)) != null) return r;
        if ((r = compileAtomicCas(funCall)) != null) return r;
        if ((r = compilePanic(funCall)) != null) return r;
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

        if(node instanceof RegValueNode regValue)
            return regValue.reg;

        if(node instanceof NativeCallNode nativeCallNode){
            int valRegRaw = compileInner(nativeCallNode.right);

            // CALL_NATIVE overwrites its own operand register with the
            // native call's return value (print/println return a status
            // code, wait() too) - a bare-variable operand like 'print x;'
            // must be copied into a fresh register first, or the call
            // silently clobbers that variable (e.g. 'print x; print x;'
            // would print the value, then print's own status code instead
            // of the value again). Same reasoning as
            // compileBuiltinUnaryCall's own comment.
            int valReg = regIndex++;
            _emit(OpCodes.MOV, valReg, valRegRaw);
            _emit(OpCodes.CALL_NATIVE, nativeCallNode.id, valReg);

            return valReg;
        }

        if(node instanceof FunCall funCall){
            Integer builtin = tryCompileBuiltin(funCall);
            if (builtin != null) return builtin;

            if (funCall.left instanceof IdentityNode calleeName && functionArity.containsKey(calleeName.name)
                    && !currentScope().identityMapping.containsKey(calleeName.name)) {
                int expected = functionArity.get(calleeName.name);
                if (expected != funCall.args.size())
                    throw err("function " + calleeName.name + " expects " + argumentCount(expected) + ", got " + funCall.args.size());
            }

            if (funCall.left instanceof FieldAccessNode fieldAccess) {
                // ClassName.method(args): resolved entirely at compile
                // time (the "receiver" is a literal class name, not a
                // runtime value), so this is just an ordinary call to the
                // mangled "ClassName.method" function - no LOOKUP_METHOD,
                // no 'this'
                ClassInfo staticTarget = staticTargetOf(fieldAccess.target);
                if (staticTarget != null) {
                    Integer staticExpected = staticTarget.staticMethodArity().get(fieldAccess.fieldName);
                    if (staticExpected == null) {
                        if (staticTarget.methodArity().containsKey(fieldAccess.fieldName))
                            throw err(staticTarget.name() + "." + fieldAccess.fieldName + " is an instance method - call it on an instance, not on the class");
                        throw err("class " + staticTarget.name() + " has no static method '" + fieldAccess.fieldName + "'");
                    }
                    if (staticExpected != funCall.args.size())
                        throw err("function " + staticTarget.name() + "." + fieldAccess.fieldName + " expects " + argumentCount(staticExpected) + ", got " + funCall.args.size());

                    String mangledName = staticTarget.name() + "." + fieldAccess.fieldName;
                    Integer staticConstIndex = functionConst(mangledName);
                    if (staticConstIndex == null)
                        throw new Bl0j_CompilerException(
                                "class " + staticTarget.name() + " has no static method '" + fieldAccess.fieldName + "'");

                    // the call's registers: the result (also where the arguments start) and one per
                    // argument, reserved first so each argument can be computed straight into its slot
                    int staticStartReg = regIndex;
                    regIndex += 1 + funCall.args.size();
                    for (int i = 0; i < funCall.args.size(); i++)
                        moveInto(staticStartReg + 1 + i, compileInner(funCall.args.get(i)));

                    int staticMethodReg = regIndex++;
                    _emit(OpCodes.LOAD_CONST, staticMethodReg, staticConstIndex);

                    _emit(OpCodes.CALL, staticMethodReg, staticStartReg, funCall.args.size());
                    return staticStartReg;
                }

                int objReg = compileInner(fieldAccess.target);
                return compileMethodCall(objReg, fieldAccess.fieldName, funCall.args, knownReceiverClass(fieldAccess.target));
            }

            int startReg = regIndex;
            regIndex += 1 + funCall.args.size();
            for(int i=0;i<funCall.args.size();i++) {
                moveInto(startReg + 1 + i, compileInner(funCall.args.get(i)));
            }

            int method = compileInner(funCall.left);

            _emit(OpCodes.CALL, method, startReg, funCall.args.size());
            return startReg;
        }

        if(node instanceof LambdaNode lambdaNode){
            // which of the body's free names actually resolve to
            // something in an enclosing scope decides the real capture
            // list; anything else just becomes a fresh local once the
            // body is compiled for real, exactly as it would outside a
            // lambda
            FreeVarScan scan = new FreeVarScan();
            scan.bound.addAll(lambdaNode.params.args);
            scanFree(lambdaNode.body, scan);

            List<String> captures = new ArrayList<>();
            for (String name : scan.free)
                if (existsInScopeChain(name))
                    captures.add(name);

            // captures become the lambda's own leading (implicit)
            // parameters, exactly like 'this' for an instance method -
            // this reuses the ordinary param-mapping prologue in
            // compileFunctions() with no special casing there
            String lambdaName = "<lambda:" + (lambdaCounter++) + ">";
            List<String> allParams = new ArrayList<>(captures);
            allParams.addAll(lambdaNode.params.args);
            FunNode lambdaFun = new FunNode(lambdaName, new PARAMS_N(allParams), lambdaNode.body);

            int constIndex = constant(new FunDef(lambdaName, -1, (short) 0, (short) 0));
            functionMapping.put(lambdaName, constIndex);
            lazy_functions.add(new PendingFunction(lambdaFun, captures.size(), new ArrayList<>(scopeChain)));

            // MAKE_CLOSURE: FunDef ref, then each captured name's current
            // (guaranteed cell) register from THIS scope's perspective
            int closureReg = regIndex++;
            _emit(OpCodes.LOAD_CONST, closureReg, constIndex);

            for (String captured : captures) {
                VarRef ref = resolve(captured);
                _emit(OpCodes.MOV, regIndex, ref.reg());
                regIndex++;
            }

            _emit(OpCodes.MAKE_CLOSURE, closureReg, captures.size());
            return closureReg;
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

            // targets are always plain identifiers (see
            // DestructuringAssignNode's own doc comment)
            for (int i = 0; i < arity; i++) {
                String name = ((IdentityNode) destr.targets.get(i)).name;
                writeToIdentity(name, base + 1 + i);
            }

            return base;
        }

        if(node instanceof IndexNode indexNode){
            int result = regIndex++;
            int arrReg = compileInner(indexNode.left);
            int indexReg = compileInner(indexNode.index);

            _emit(OpCodes.INDEX_GET, arrReg, indexReg, result);

            return result;
        }

        if(node instanceof FieldAccessNode fieldAccess){
            // ClassName.field: like a static method call, the receiver is a
            // literal class name, so the field's index is resolved right
            // here instead of going through runtime name lookup
            ClassInfo staticTarget = staticTargetOf(fieldAccess.target);
            if (staticTarget != null) {
                int fieldIndex = staticTarget.staticFieldIndex(fieldAccess.fieldName);
                if (fieldIndex < 0) {
                    // not a static field - a static method used as a value
                    // (Class.method without a call): its function constant
                    // is the callable, same as a bare function name
                    Integer methodConst = functionConst(staticTarget.name() + "." + fieldAccess.fieldName);
                    if (methodConst != null) {
                        int methodReg = regIndex++;
                        _emit(OpCodes.LOAD_CONST, methodReg, methodConst);
                        return methodReg;
                    }
                    throw new Bl0j_CompilerException("class " + staticTarget.name() + " has no static field or method '" + fieldAccess.fieldName + "'");
                }

                int classReg = regIndex++;
                _emit(OpCodes.LOAD_CONST, classReg, classConst(staticTarget));
                _emit(OpCodes.GET_STATIC_FIELD, classReg, fieldIndex);
                return classReg;
            }

            checkFieldAccess(fieldAccess.target, fieldAccess.fieldName);
            int objReg = compileInner(fieldAccess.target);
            int result = regIndex++;
            _emit(OpCodes.GET_FIELD, objReg, constant(fieldAccess.fieldName), result);
            return result;
        }

        if(node instanceof NewNode newNode){
            ClassInfo info = classMapping.get(newNode.className);
            if (info == null)
                throw new Bl0j_CompilerException("unknown class: " + newNode.className);

            int instanceReg = regIndex++;
            _emit(OpCodes.LOAD_CONST, instanceReg, classConst(info));
            _emit(OpCodes.NEW_INSTANCE, instanceReg); // class-ref in, instance-ref out

            if (info.hasInit())
                compileMethodCall(instanceReg, "init", newNode.args, info); // return value discarded
            else if (!newNode.args.isEmpty())
                throw err("class " + info.name() + " has no init(), so new " + info.name() + "(...) takes no arguments, got " + newNode.args.size());

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
            List<Object> hoisted = hoistLiterals(whileNode.condition, whileNode.body);
            int startJump = _instr_len();
            int condReg = compileInner(whileNode.condition);

            int patchJumpIfNot = emitJumpIfNot(condReg);

            LoopContext loop = new LoopContext(tryDepth);
            loopStack.push(loop);
            compileInner(whileNode.body); // -1
            loopStack.pop();

            // continue lands here and falls straight into the backward
            // jump below - same effect as looping normally
            int continueTarget = _instr_len();
            for (int patch : loop.continuePatches())
                patchAddr(patch, continueTarget);

            _emit(OpCodes.JUMP, startJump);
            int loopEnd = _instr_len();
            patchAddr(patchJumpIfNot, loopEnd);
            for (int patch : loop.breakPatches())
                patchAddr(patch, loopEnd);

            unhoist(hoisted);
            return -1;
        }

        if(node instanceof ForNode forNode){
            // identical to WhileNode, except init runs once up front and
            // update runs at the end of every iteration, right before the
            // jump back to the condition check
            compileInner(forNode.init);

            List<Object> hoisted = hoistLiterals(forNode.condition, forNode.body, forNode.update);
            int startJump = _instr_len();
            int condReg = compileInner(forNode.condition);

            int patchJumpIfNot = emitJumpIfNot(condReg);

            LoopContext loop = new LoopContext(tryDepth);
            loopStack.push(loop);
            compileInner(forNode.body);
            loopStack.pop();

            // continue must still run the update before re-checking the
            // condition, so its target is the update's own start address -
            // only known now, right before compiling it
            int continueTarget = _instr_len();
            for (int patch : loop.continuePatches())
                patchAddr(patch, continueTarget);

            compileInner(forNode.update);

            _emit(OpCodes.JUMP, startJump);
            int loopEnd = _instr_len();
            patchAddr(patchJumpIfNot, loopEnd);
            for (int patch : loop.breakPatches())
                patchAddr(patch, loopEnd);

            unhoist(hoisted);
            return -1;
        }

        if(node instanceof BreakNode){
            if (loopStack.isEmpty())
                throw new Bl0j_CompilerException("'break' outside of a loop");
            LoopContext loop = loopStack.peek();
            // unwind any handler(s) opened by a try inside this loop that
            // the break is jumping past - otherwise they'd stay on the
            // VM's handlerStack long after this loop is gone, exactly the
            // stale-handler bug RETURN already has to guard against
            for (int i = 0; i < tryDepth - loop.tryDepthAtStart(); i++)
                _emit(OpCodes.TRY_EXIT);
            int patch = _emit(OpCodes.JUMP) + A_OFFSET; // patched once the loop end is known
            loop.breakPatches().add(patch);
            return -1;
        }

        if(node instanceof ContinueNode){
            if (loopStack.isEmpty())
                throw new Bl0j_CompilerException("'continue' outside of a loop");
            LoopContext loop = loopStack.peek();
            for (int i = 0; i < tryDepth - loop.tryDepthAtStart(); i++)
                _emit(OpCodes.TRY_EXIT);
            int patch = _emit(OpCodes.JUMP) + A_OFFSET;
            loop.continuePatches().add(patch);
            return -1;
        }

        if(node instanceof TryNode tryNode){
            // exempted from cell treatment: the VM writes the caught error
            // straight into this register on a catch (see TRY_ENTER's
            // handling), bypassing any cell, so this name must stay a
            // plain register even in an otherwise-boxed scope. A nested
            // lambda inside the catch body therefore can't capture it -
            // narrow, deliberate limitation rather than a silent bug.
            currentScope().plainNames.add(tryNode.catchVarName);
            int errReg = resolve(tryNode.catchVarName).reg();

            // b (the catch address) is patched once we know where the
            // catch block actually starts, same pattern as if/ternary
            int patchCatchAddr = _emit(OpCodes.TRY_ENTER, errReg) + B_OFFSET;

            // only the try body itself runs with this handler active - a
            // break/continue compiled inside it needs to know to emit a
            // matching TRY_EXIT before jumping out (see LoopContext)
            tryDepth++;
            compileInner(tryNode.tryBody);
            tryDepth--;
            _emit(OpCodes.TRY_EXIT);

            int patchJumpOverCatch = _emit(OpCodes.JUMP) + A_OFFSET;
            patchAddr(patchCatchAddr, _instr_len());

            compileInner(tryNode.catchBody);
            patchAddr(patchJumpOverCatch, _instr_len());

            return -1;
        }

        if(node instanceof Ternary_IfNode ternaryIfNode){
            int resultReg = regIndex++;
            int condReg = compileInner(ternaryIfNode.condition);

            int patchJumpIfNot = emitJumpIfNot(condReg);
            int bodyReg = compileInner(ternaryIfNode.body);

            _emit(OpCodes.MOV, resultReg, bodyReg);

            int patchJump = _emit(OpCodes.JUMP) + A_OFFSET;
            patchAddr(patchJumpIfNot, _instr_len());

            int elseReg = compileInner(ternaryIfNode.elseBody);

            _emit(OpCodes.MOV, resultReg, elseReg);
            patchAddr(patchJump, _instr_len());

            return resultReg;
        }

        if (node instanceof IfNode ifNode) {
            int condReg = compileInner(ifNode.condition);

            int patchJumpIfNot = emitJumpIfNot(condReg);
            compileInner(ifNode.body);

            if (ifNode.elseBody != null) {

                int patchJump = _emit(OpCodes.JUMP) + A_OFFSET;
                patchAddr(patchJumpIfNot, _instr_len());

                compileInner(ifNode.elseBody);
                patchAddr(patchJump, _instr_len());
            } else {
                patchAddr(patchJumpIfNot, _instr_len());
            }

            return -1;
        }

        if(node instanceof DataNode){
            int constIndex = -1;

            if(node instanceof IdentityNode n){
                // a parameter (or captured variable) shadows a function of the
                // same name inside its own function - lexical scoping. Plain
                // assignment to a function's name is rejected, so a local
                // that exists here is always a parameter.
                if (functionMapping.containsKey(n.name) && !currentScope().identityMapping.containsKey(n.name)) {
                    constIndex = functionConst(n.name);
                    int reg = regIndex++;
                    _emit(OpCodes.LOAD_CONST, reg, constIndex);
                    return reg;
                }

                VarRef ref = resolveForRead(n.name);
                if (ref.isCell()) {
                    int result = regIndex++;
                    _emit(OpCodes.MOV, result, ref.reg());
                    _emit(OpCodes.CELL_GET, result);
                    return result;
                }
                return ref.reg();
            }


            if (node instanceof NumberNode n)
                constIndex = constant(n.value);
            if (node instanceof FloatNode f)
                constIndex = constant(f.value);
            if(node instanceof StringNode s)
                constIndex = constant(s.value);
            if(node instanceof BooleanNode b)
                constIndex = constant(b.value);

            Object literal = literalValue(node);
            if (literal != null) {
                Integer loaded = currentScope().hoisted.get(literal);
                if (loaded != null)
                    return loaded;
            }

            int reg = regIndex++;

            if(node instanceof NilNode)
                _emit(OpCodes.LOAD_NIL, reg);
             else
                _emit(OpCodes.LOAD_CONST, reg, constIndex);

            return reg;
        }

        if (node instanceof BinaryNode n) {

            if (n.op == Operator.ASSIGNMENT)
                return compileAssign(n.left, n.right);

            // short-circuit: unlike every other binary operator here, the
            // right side must not even be evaluated once the left side
            // already decides the result (this is what makes
            // 'i < len(arr) && arr[i] == x' safe to write)
            if (n.op == Operator.AND || n.op == Operator.OR) {
                int result = regIndex++;
                int leftReg = compileInner(n.left);
                _emit(OpCodes.MOV, result, leftReg);

                byte shortCircuitJump = n.op == Operator.AND ? OpCodes.JUMP_IF_NOT : OpCodes.JUMP_IF;
                int patchJump = _emit(shortCircuitJump, result) + B_OFFSET;

                int rightReg = compileInner(n.right);
                _emit(OpCodes.MOV, result, rightReg);
                patchAddr(patchJump, _instr_len());

                return result;
            }

            int result = regIndex++;
            int left  = compileInner(n.left);
            int right = compileInner(n.right);

            // != reuses EQ and negates it; <= and >= have their own opcodes
            // (negating the opposite comparison gets NaN wrong).
            byte op = switch (n.op) {
                case PLUS -> OpCodes.LR_ADD;
                case MINUS -> OpCodes.LR_SUB;
                case STAR -> OpCodes.LR_MUL;
                case STAR_STAR -> OpCodes.LR_POW;
                case DIV -> OpCodes.LR_DIV;
                case REMAINDER ->  OpCodes.LR_REM;
                case EQUALS, NOT_EQUALS -> OpCodes.EQ;
                case LESS -> OpCodes.LESS;
                case GREATER_EQUALS -> OpCodes.GREATER_EQ;
                case GREATER -> OpCodes.GREATER;
                case LESS_EQUALS -> OpCodes.LESS_EQ;
                case BIT_AND -> OpCodes.LR_AND;
                case BIT_OR -> OpCodes.LR_OR;
                case BIT_XOR -> OpCodes.LR_XOR;
                case SHIFT_LEFT -> OpCodes.LR_SHL;
                case SHIFT_RIGHT -> OpCodes.LR_SHR;
                case SHIFT_RIGHT_UNSIGNED -> OpCodes.LR_USHR;
                default -> throw new Bl0j_CompilerException("Unknown op: " + n.op);
            };

            // three-operand form: result = left op right, neither operand touched
            _emit(op, left, right, result);

            if(n.op == Operator.NOT_EQUALS)
                _emit(OpCodes.NOT, result, result);

            return result;
        }

        if(node instanceof UnaryNode u){
            int reg;

            if(node instanceof RUnaryNode rUnaryNode){
                // x++ / x-- as 'x = x + 1' written back to wherever x lives, and
                // yielding x's OLD value. It used to add to a register that holds
                // a COPY of x for anything but a plain local - a captured variable
                // (a cell), a field or an array element - so those silently never
                // changed.
                Node target = rUnaryNode.right;
                if (!(target instanceof IdentityNode || target instanceof FieldAccessNode || target instanceof IndexNode))
                    throw err("the operand of '" + (rUnaryNode.op == Operator.PLUS_PLUS ? "++" : "--") + "' must be a variable, a field or an array element");
                if (!isRepeatableTarget(target))
                    throw err("the operand of '++'/'--' is read and written, so it must not contain calls or assignments");

                byte op = switch (rUnaryNode.op){
                    case MINUS_MINUS -> OpCodes.LR_SUB;
                    case PLUS_PLUS -> OpCodes.LR_ADD;
                    default -> throw new Bl0j_CompilerException("Unknown op: " + u.op);
                };

                int current = compileInner(target);
                int oldValue = regIndex++;
                _emit(OpCodes.MOV, oldValue, current); // 'current' may BE the variable's register, which the store below changes
                int one = regIndex++;
                _emit(OpCodes.LOAD_CONST, one, constant(1));
                int updated = regIndex++;
                _emit(op, oldValue, one, updated);

                compileAssign(target, new RegValueNode(updated), true);
                return oldValue;
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
                _emit(op, result, reg);

                return result;
            }
        }

        throw new Bl0j_CompilerException("Unexpected node type: " + node);
    }

    // instructions are fixed-width [opcode:1][a:2][b:2] (C.INSTR_WIDTH
    // bytes), so every operand (register index, constant index, jump
    // address, native id, ...) must fit in 0..65535 - silently truncating a
    // larger value would corrupt registers or jump targets instead of
    // failing loudly
    private void checkOperand(int value, String what) {
        if (value < 0 || value > C.MAX_OPERAND)
            throw new Bl0j_CompilerException(
                    what + " (" + value + ") exceeds the bytecode format's per-operand limit of " + C.MAX_OPERAND);
    }

    // offsets of each operand's first byte, relative to an instruction's
    // own start index - used by callers that patch a jump target in after
    // emitting it (see patchAddr)
    private static final int A_OFFSET = 1;
    private static final int B_OFFSET = 3;
    private static final int C_OFFSET = 5;

    // returns the index of the instruction's own first (opcode) byte, not
    // the index right after it - callers that need to patch an operand
    // later add A_OFFSET/B_OFFSET to find it, which is far less error-prone
    // than counting backward from where bytecode.size() happened to land
    private int _emit(int op, int a, int b, int c) {
        checkOperand(a, "operand 'a'");
        checkOperand(b, "operand 'b'");
        checkOperand(c, "operand 'c'");
        int start = bytecode.size();
        bytecode.add((byte) op);
        bytecode.add((byte) (a >> 8));
        bytecode.add((byte) a);
        bytecode.add((byte) (b >> 8));
        bytecode.add((byte) b);
        bytecode.add((byte) (c >> 8));
        bytecode.add((byte) c);
        return start;
    }

    private int _emit(int op, int a, int b) {
        return _emit(op, a, b, 0);
    }

    private int _emit(int op, int a) {
        return _emit(op, a, 0);
    }

    private int _emit(int op) {
        return _emit(op, 0, 0);
    }

    // writes a 2-byte big-endian address into an already-emitted
    // instruction's operand slot - used once a forward jump's real target
    // is known (see WhileNode/ForNode/IfNode/TryNode/break/continue)
    private void patchAddr(int index, int addr) {
        checkOperand(addr, "patched address");
        bytecode.set(index, (byte) (addr >> 8));
        bytecode.set(index + 1, (byte) addr);
    }

    // the byte offset the most recent jump target / address was taken at: an instruction emitted
    // there may be jumped to, so the one before it cannot be merged with it (see retargetLast)
    private int labelAt = -1;

    private int _instr_len(){
        labelAt = bytecode.size();
        if(bytecode.size() % C.INSTR_WIDTH != 0)
            throw new Bl0j_CompilerException("Invalid instruction len: " + bytecode.size());
        int len = bytecode.size() / C.INSTR_WIDTH;
        checkOperand(len, "instruction address");
        return len;
    }

    // resolves a name against the CURRENT (innermost) scope only - never
    // searches outward. A lambda body's free variables are already
    // guaranteed to be among its own declared params (captures prepended
    // by the LambdaNode handling below, via scanFree's up-front analysis),
    // so this never needs to reach into an enclosing, already-compiled
    // scope; a name genuinely not found here is a brand new local.
    private VarRef resolve(String name) {
        FunctionScope scope = currentScope();
        Integer reg = scope.identityMapping.get(name);
        if (reg != null) {
            scope.suspectReads.remove(name); // something besides a read touched it: it IS assigned
            return new VarRef(reg, scope.isCell(name));
        }

        int newReg = regIndex++;
        scope.identityMapping.put(name, newReg);
        return new VarRef(newReg, scope.isCell(name));
    }

    // resolve() for a variable being READ: a name with no binding yet still
    // gets a register (the read may sit above the assignment in a loop), but
    // is remembered so that, if no assignment ever follows in this function,
    // the compile fails with "undefined variable" instead of the program
    // quietly reading nil forever
    private VarRef resolveForRead(String name) {
        FunctionScope scope = currentScope();
        boolean known = scope.identityMapping.containsKey(name);
        Integer reg = scope.identityMapping.get(name);
        if (known)
            return new VarRef(reg, scope.isCell(name));
        VarRef ref = resolve(name);
        scope.suspectReads.add(name);
        return ref;
    }

    private void checkNoUndefinedReads(FunctionScope scope) {
        if (!scope.suspectReads.isEmpty())
            throw err("undefined variable '" + scope.suspectReads.iterator().next() + "' (read, but never assigned)");
    }

    // the value of a number/float/string/boolean literal, else null
    private static Object literalValue(Node node) {
        return switch (node) {
            case NumberNode n -> n.value;
            case FloatNode f -> f.value;
            case StringNode s -> s.value;
            case BooleanNode b -> b.value;
            default -> null;
        };
    }

    // the literals a loop's condition, body and update use (not the bodies of lambdas: those are
    // compiled as functions of their own)
    private void collectLiterals(Node node, Set<Object> out) {
        if (node == null) return;
        Object literal = literalValue(node);
        if (literal != null) { out.add(literal); return; }
        switch (node) {
            case ReturnNode n -> collectLiterals(n.right, out);
            case NativeCallNode n -> collectLiterals(n.right, out);
            case FunCall n -> { collectLiterals(n.left, out); for (var arg : n.args) collectLiterals(arg, out); }
            case ArrayLiteralNode n -> { for (var e : n.elements) collectLiterals(e, out); }
            case TupleNode n -> { for (var v : n.values) collectLiterals(v, out); }
            case IndexNode n -> { collectLiterals(n.left, out); collectLiterals(n.index, out); }
            case FieldAccessNode n -> collectLiterals(n.target, out);
            case NewNode n -> { for (var a : n.args) collectLiterals(a, out); }
            case DestructuringAssignNode n -> collectLiterals(n.right, out);
            case WhileNode n -> { collectLiterals(n.condition, out); collectLiterals(n.body, out); }
            case ForNode n -> { collectLiterals(n.init, out); collectLiterals(n.condition, out); collectLiterals(n.body, out); collectLiterals(n.update, out); }
            case TryNode n -> { collectLiterals(n.tryBody, out); collectLiterals(n.catchBody, out); }
            case Ternary_IfNode n -> { collectLiterals(n.condition, out); collectLiterals(n.body, out); collectLiterals(n.elseBody, out); }
            case IfNode n -> { collectLiterals(n.condition, out); collectLiterals(n.body, out); collectLiterals(n.elseBody, out); }
            case BinaryNode n -> { collectLiterals(n.left, out); collectLiterals(n.right, out); }
            case LUnaryNode n -> collectLiterals(n.left, out);
            case RUnaryNode n -> collectLiterals(n.right, out);
            case PROGRAM_N n -> { for (var st : n.nodes) collectLiterals(st, out); }
            default -> { }
        }
    }

    private static final int MAX_HOISTED_PER_LOOP = 24;

    // loads, before a loop, the literals it uses that are not loaded already; returns what was
    // added so the caller can take it back out when the loop ends
    private List<Object> hoistLiterals(Node... parts) {
        Set<Object> wanted = new LinkedHashSet<>();
        for (Node part : parts) collectLiterals(part, wanted);
        List<Object> added = new ArrayList<>();
        for (Object value : wanted) {
            if (added.size() >= MAX_HOISTED_PER_LOOP) break;
            if (currentScope().hoisted.containsKey(value)) continue;
            int reg = regIndex++;
            _emit(OpCodes.LOAD_CONST, reg, constant(value));
            currentScope().hoisted.put(value, reg);
            added.add(value);
        }
        return added;
    }

    private void unhoist(List<Object> added) {
        for (Object value : added) currentScope().hoisted.remove(value);
    }

    // The conditional jump that skips a body when a condition is false; returns the index to patch
    // with the target. When the condition was just computed by a comparison into a temporary that
    // only this jump reads, the two become one instruction (a, b operands, c target) - for '!=' the
    // EQ and the NOT after it become a jump on equality. Not when something jumps to the place the
    // jump would sit.
    private int emitJumpIfNot(int condReg) {
        int size = bytecode.size();
        boolean temp = !currentScope().identityMapping.containsValue(condReg) && !currentScope().hoisted.containsValue(condReg);
        if (temp && size >= C.INSTR_WIDTH && labelAt != size) {
            int p = size - C.INSTR_WIDTH;
            int op = bytecode.get(p) & 0xFF;
            int fused = switch (op) {
                case OpCodes.LESS -> OpCodes.JUMP_IF_NOT_LESS;
                case OpCodes.GREATER -> OpCodes.JUMP_IF_NOT_GREATER;
                case OpCodes.LESS_EQ -> OpCodes.JUMP_IF_NOT_LESS_EQ;
                case OpCodes.GREATER_EQ -> OpCodes.JUMP_IF_NOT_GREATER_EQ;
                case OpCodes.EQ -> OpCodes.JUMP_IF_NOT_EQ;
                default -> -1;
            };
            if (fused >= 0 && operandAt(p + C_OFFSET) == condReg) {
                bytecode.set(p, (byte) fused);
                patchAddr(p + C_OFFSET, 0);
                return p + C_OFFSET;
            }
            // 'a != b' is EQ followed by NOT on its result
            if (op == OpCodes.NOT && p >= C.INSTR_WIDTH && labelAt != p && operandAt(p + A_OFFSET) == condReg) {
                int q = p - C.INSTR_WIDTH;
                if ((bytecode.get(q) & 0xFF) == OpCodes.EQ && operandAt(q + C_OFFSET) == condReg) {
                    for (int i = 0; i < C.INSTR_WIDTH; i++)
                        bytecode.remove(bytecode.size() - 1);
                    bytecode.set(q, (byte) OpCodes.JUMP_IF_EQ);
                    patchAddr(q + C_OFFSET, 0);
                    return q + C_OFFSET;
                }
            }
        }
        return _emit(OpCodes.JUMP_IF_NOT, condReg) + B_OFFSET;
    }

    private int operandAt(int index) {
        return ((bytecode.get(index) & 0xFF) << 8) | (bytecode.get(index + 1) & 0xFF);
    }

    // 'x = a + b' computes into a temporary and then copies it into x; when the instruction that
    // just computed the temporary can write x directly, do that and drop the copy. Only for
    // instructions with a single destination register that is not also needed afterwards: the
    // arithmetic and comparison operators (destination c) and constant loads (destination a),
    // and only when valueReg is a temporary - not a variable's own register - and nothing jumps
    // to the place the copy would have gone (a conditional's result is written on two paths).
    private boolean retargetLast(int valueReg, int destReg) {
        int size = bytecode.size();
        if (size < C.INSTR_WIDTH || labelAt == size)
            return false;
        if (currentScope().identityMapping.containsValue(valueReg) || currentScope().hoisted.containsValue(valueReg))
            return false;
        int p = size - C.INSTR_WIDTH;
        int op = bytecode.get(p) & 0xFF;
        int operand;
        switch (op) {
            case OpCodes.LR_ADD, OpCodes.LR_SUB, OpCodes.LR_MUL, OpCodes.LR_DIV, OpCodes.LR_REM, OpCodes.LR_POW,
                 OpCodes.LR_AND, OpCodes.LR_OR, OpCodes.LR_XOR, OpCodes.LR_SHL, OpCodes.LR_SHR, OpCodes.LR_USHR,
                 OpCodes.EQ, OpCodes.LESS, OpCodes.GREATER, OpCodes.LESS_EQ, OpCodes.GREATER_EQ, OpCodes.INDEX_GET -> operand = p + C_OFFSET;
            case OpCodes.LOAD_CONST, OpCodes.LOAD_NIL,
                 OpCodes.LENGTH, OpCodes.TO_INT, OpCodes.TO_FLOAT, OpCodes.TO_STRING, OpCodes.TYPE_OF,
                 OpCodes.NEG, OpCodes.NOT, OpCodes.BIT_NOT, OpCodes.MAKE_ERR -> operand = p + A_OFFSET;
            default -> { return false; }
        }
        int current = ((bytecode.get(operand) & 0xFF) << 8) | (bytecode.get(operand + 1) & 0xFF);
        if (current != valueReg)
            return false;
        checkOperand(destReg, "operand");
        bytecode.set(operand, (byte) (destReg >> 8));
        bytecode.set(operand + 1, (byte) destReg);
        return true;
    }

    // puts the value in valueReg into the call-argument register 'slot': the instruction that just
    // computed it writes there directly when it can (see retargetLast), else a copy
    private void moveInto(int slot, int valueReg) {
        if (!retargetLast(valueReg, slot))
            _emit(OpCodes.MOV, slot, valueReg);
    }

    // writes valueReg into 'name', handling first-establishment of a cell
    // (MAKE_CELL) vs. an already-live one (just CELL_SET) - shared by plain
    // assignment and destructuring targets, which are always bare names
    private void writeToIdentity(String name, int valueReg) {
        FunctionScope scope = currentScope();
        boolean firstBinding = !scope.identityMapping.containsKey(name);
        VarRef ref = resolve(name);

        if (ref.isCell()) {
            if (firstBinding) _emit(OpCodes.MAKE_CELL, ref.reg());
            _emit(OpCodes.CELL_SET, ref.reg(), valueReg);
            return;
        }
        _emit(OpCodes.MOV, ref.reg(), valueReg);
    }

    // compiles an assignment target=valueNode, preserving each target
    // kind's original left-to-right evaluation order (target parts before
    // the value, matching INDEX_SET/SET_FIELD's existing operand packing).
    // Returns a register holding the assigned value.
    private int compileAssign(Node target, Node valueNode) {
        return compileAssign(target, valueNode, false);
    }

    private int compileAssign(Node target, Node valueNode, boolean freshTemp) {
        if (target instanceof IndexNode indexNode) {
            int arrReg = compileInner(indexNode.left);
            int indexRegRaw = compileInner(indexNode.index);
            int valueRegRaw = compileInner(valueNode);

            int base = regIndex++;
            _emit(OpCodes.MOV, base, indexRegRaw);
            _emit(OpCodes.MOV, regIndex, valueRegRaw);
            regIndex++;

            _emit(OpCodes.INDEX_SET, arrReg, base);
            return valueRegRaw;
        }

        if (target instanceof FieldAccessNode fieldAccess) {
            ClassInfo staticTarget = staticTargetOf(fieldAccess.target);
            if (staticTarget != null) {
                int fieldIndex = staticTarget.staticFieldIndex(fieldAccess.fieldName);
                if (fieldIndex < 0) {
                    // not a static field - a static method used as a value
                    // (Class.method without a call): its function constant
                    // is the callable, same as a bare function name
                    Integer methodConst = functionConst(staticTarget.name() + "." + fieldAccess.fieldName);
                    if (methodConst != null) {
                        int methodReg = regIndex++;
                        _emit(OpCodes.LOAD_CONST, methodReg, methodConst);
                        return methodReg;
                    }
                    throw new Bl0j_CompilerException("class " + staticTarget.name() + " has no static field or method '" + fieldAccess.fieldName + "'");
                }

                int classReg = regIndex++;
                _emit(OpCodes.LOAD_CONST, classReg, classConst(staticTarget));
                int valueRegRaw = compileInner(valueNode);

                _emit(OpCodes.SET_STATIC_FIELD, classReg, valueRegRaw, fieldIndex);
                return valueRegRaw;
            }

            // const field: writable only via 'this.field = ...' inside the
            // declaring class's own init() - compile-time-only check, so
            // 'obj.field = ...' from outside (or from any other method)
            // is deliberately not caught here; this language has no field
            // privacy at all, so const is a same-class self-discipline
            // check, not access control
            if (fieldAccess.target instanceof IdentityNode idNode && idNode.name.equals("this") && currentClassName != null) {
                ClassInfo owner = classMapping.get(currentClassName);
                if (owner != null && owner.constFieldNames().contains(fieldAccess.fieldName) && !currentMethodIsInit)
                    throw new Bl0j_CompilerException("cannot assign to const field '" + fieldAccess.fieldName + "' outside " + currentClassName + ".init()");
            }

            checkFieldAccess(fieldAccess.target, fieldAccess.fieldName);
            int objReg = compileInner(fieldAccess.target);
            int valueRegRaw = compileInner(valueNode);

            _emit(OpCodes.SET_FIELD, objReg, valueRegRaw, constant(fieldAccess.fieldName));
            return valueRegRaw;
        }

        if (target instanceof IdentityNode idNode) {
            // 'f = (n) -> ... f(n - 1) ...': the lambda's own body names the
            // variable it is about to be assigned to. Left alone, 'f' would
            // not exist in any enclosing scope yet while the lambda is
            // compiled (the assignment only creates it AFTER the value), so
            // it would not be captured and the call inside would hit a
            // fresh nil local. Declaring the (cell-backed) variable first,
            // as nil, makes the lambda capture the very cell the assignment
            // below then fills in - the same cell, so by the time the
            // lambda actually runs it sees itself.
            // assigning to the name of a global function makes a LOCAL variable of
            // that name, hiding the function from here on in this function - the
            // same as a parameter does. (It must not be an error: a library
            // function's local called 'handler' would then break the moment a
            // program defines its own top-level 'handler'.)
            if (classMapping.containsKey(idNode.name))
                throw err("cannot assign to '" + idNode.name + "': it is the name of a class");

            if (valueNode instanceof LambdaNode lambda && lambdaMentionsFreely(lambda, idNode.name)) {
                FunctionScope scope = currentScope();
                if (!scope.identityMapping.containsKey(idNode.name) && scope.isCell(idNode.name)) {
                    VarRef ref = resolve(idNode.name);
                    int nilReg = regIndex++;
                    _emit(OpCodes.LOAD_NIL, nilReg);
                    _emit(OpCodes.MAKE_CELL, ref.reg());
                    _emit(OpCodes.CELL_SET, ref.reg(), nilReg);
                }
            }

            int valueRegRaw = compileInner(valueNode);
            // a RegValueNode is a register somebody else made: only the ++/-- path's own fresh one
            // (freshTemp) may be taken over
            boolean mayRetarget = !(valueNode instanceof RegValueNode) || freshTemp;
            if (mayRetarget && !currentScope().isCell(idNode.name)) {
                boolean known = currentScope().identityMapping.containsKey(idNode.name);
                VarRef ref = resolve(idNode.name);
                if (retargetLast(valueRegRaw, ref.reg()))
                    return ref.reg();
                // the name was just made by resolve(); writeToIdentity must not think it is new
                // (it only matters for cells, which are excluded above)
            }
            writeToIdentity(idNode.name, valueRegRaw);
            return valueRegRaw;
        }

        throw new Bl0j_CompilerException("cannot assign to " + target);
    }

    // a target that can be compiled twice (read, then written) without running
    // anything twice: names, literals, and field/index chains over them
    private static boolean isRepeatableTarget(Node node) {
        return switch (node) {
            case IdentityNode n -> true;
            case NumberNode n -> true;
            case StringNode n -> true;
            case FieldAccessNode n -> isRepeatableTarget(n.target);
            case IndexNode n -> isRepeatableTarget(n.left) && isRepeatableTarget(n.index);
            default -> false;
        };
    }

    // does the lambda's body refer to 'name' without it being one of the
    // lambda's own parameters (i.e. as a free variable)?
    private boolean lambdaMentionsFreely(LambdaNode lambda, String name) {
        FreeVarScan scan = new FreeVarScan();
        scan.bound.addAll(lambda.params.args);
        scanFree(lambda.body, scan);
        return scan.free.contains(name);
    }

    // is 'name' bound anywhere in the enclosing scope chain (excluding
    // names explicitly exempted from capture, like a try/catch error
    // variable)? Used to decide, for a lambda literal's free variables,
    // which ones are real captures versus brand new locals of its own.
    private boolean existsInScopeChain(String name) {
        for (int i = scopeChain.size() - 1; i >= 0; i--) {
            FunctionScope s = scopeChain.get(i);
            if (s.identityMapping.containsKey(name) && !s.plainNames.contains(name))
                return true;
        }
        return false;
    }

    // does this subtree contain a LambdaNode anywhere (at any depth,
    // including inside a nested lambda's own body)? Decides, once and up
    // front, whether a function/lambda's own locals default to being
    // cell-backed - cheap presence check, no bound/free bookkeeping needed.
    private boolean containsLambda(Node node) {
        if (node == null) return false;
        return switch (node) {
            case LambdaNode n -> true;
            case ReturnNode n -> containsLambda(n.right);
            case NativeCallNode n -> containsLambda(n.right);
            case FunCall n -> containsLambda(n.left) || n.args.stream().anyMatch(this::containsLambda);
            case ArrayLiteralNode n -> n.elements.stream().anyMatch(this::containsLambda);
            case TupleNode n -> n.values.stream().anyMatch(this::containsLambda);
            case IndexNode n -> containsLambda(n.left) || containsLambda(n.index);
            case FieldAccessNode n -> containsLambda(n.target);
            case NewNode n -> n.args.stream().anyMatch(this::containsLambda);
            case DestructuringAssignNode n -> containsLambda(n.right) || n.targets.stream().anyMatch(this::containsLambda);
            case WhileNode n -> containsLambda(n.condition) || containsLambda(n.body);
            case ForNode n -> containsLambda(n.init) || containsLambda(n.condition) || containsLambda(n.body) || containsLambda(n.update);
            case TryNode n -> containsLambda(n.tryBody) || containsLambda(n.catchBody);
            case Ternary_IfNode n -> containsLambda(n.condition) || containsLambda(n.body) || containsLambda(n.elseBody);
            case IfNode n -> containsLambda(n.condition) || containsLambda(n.body) || (n.elseBody != null && containsLambda(n.elseBody));
            case BinaryNode n -> containsLambda(n.left) || containsLambda(n.right);
            case LUnaryNode n -> containsLambda(n.left);
            case RUnaryNode n -> containsLambda(n.right);
            case PROGRAM_N n -> n.nodes.stream().anyMatch(this::containsLambda);
            default -> false; // DataNode leaves, BreakNode, ContinueNode, ...
        };
    }

    // tracks, while walking a lambda body, which names are bound (params,
    // or first touched as an assignment target) versus free (referenced
    // before/without ever being locally bound) - first-use order matters,
    // so 'free' is a LinkedHashSet.
    private static final class FreeVarScan {
        final Set<String> bound = new HashSet<>();
        final LinkedHashSet<String> free = new LinkedHashSet<>();
    }

    // collects a lambda body's free names: identifiers read or assigned
    // that aren't its own params and aren't themselves assigned earlier in
    // the SAME body first. Over-approximates only in the direction of
    // "maybe free" - a name that turns out not to exist in any enclosing
    // scope just becomes a fresh local once the body is actually compiled,
    // exactly as it would outside a lambda.
    private void scanFree(Node node, FreeVarScan s) {
        if (node == null) return;
        switch (node) {
            case IdentityNode n -> { if (!s.bound.contains(n.name)) s.free.add(n.name); }
            case ReturnNode n -> scanFree(n.right, s);
            case NativeCallNode n -> scanFree(n.right, s);
            case FunCall n -> { scanFree(n.left, s); for (var arg : n.args) scanFree(arg, s); }
            case ArrayLiteralNode n -> { for (var e : n.elements) scanFree(e, s); }
            case TupleNode n -> { for (var v : n.values) scanFree(v, s); }
            case IndexNode n -> { scanFree(n.left, s); scanFree(n.index, s); }
            case FieldAccessNode n -> scanFree(n.target, s); // fieldName isn't an identifier reference
            case NewNode n -> { for (var a : n.args) scanFree(a, s); }
            case DestructuringAssignNode n -> {
                scanFree(n.right, s);
                for (var t : n.targets) bindAssignTarget(t, s);
            }
            case WhileNode n -> { scanFree(n.condition, s); scanFree(n.body, s); }
            case ForNode n -> { scanFree(n.init, s); scanFree(n.condition, s); scanFree(n.body, s); scanFree(n.update, s); }
            case TryNode n -> {
                scanFree(n.tryBody, s);
                s.bound.add(n.catchVarName);
                scanFree(n.catchBody, s);
            }
            case Ternary_IfNode n -> { scanFree(n.condition, s); scanFree(n.body, s); scanFree(n.elseBody, s); }
            case IfNode n -> { scanFree(n.condition, s); scanFree(n.body, s); scanFree(n.elseBody, s); }
            case BinaryNode n -> {
                if (n.op == Operator.ASSIGNMENT) {
                    scanFree(n.right, s);
                    bindAssignTarget(n.left, s);
                } else {
                    scanFree(n.left, s);
                    scanFree(n.right, s);
                }
            }
            case LUnaryNode n -> scanFree(n.left, s);
            case RUnaryNode n -> scanFree(n.right, s);
            case PROGRAM_N n -> { for (var st : n.nodes) scanFree(st, s); }
            case LambdaNode n -> {
                // a name a NESTED lambda needs (and doesn't declare itself)
                // is potentially free relative to THIS lambda too, so it
                // must keep recursing rather than stop at the boundary -
                // this is what lets a multi-level nested lambda's captures
                // thread all the way through each enclosing lambda's own
                // parameter list
                Set<String> savedBound = new HashSet<>(s.bound);
                s.bound.addAll(n.params.args);
                scanFree(n.body, s);
                s.bound.clear();
                s.bound.addAll(savedBound);
            }
            default -> {} // NumberNode/FloatNode/StringNode/BooleanNode/NilNode, BreakNode, ContinueNode, ...
        }
    }

    private void bindAssignTarget(Node target, FreeVarScan s) {
        if (target instanceof IdentityNode idNode) {
            s.bound.add(idNode.name);
        } else if (target instanceof IndexNode idxNode) {
            scanFree(idxNode.left, s);
            scanFree(idxNode.index, s);
        } else if (target instanceof FieldAccessNode faNode) {
            scanFree(faNode.target, s);
        }
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
                        dos.writeBoolean(f.receiver());
                    }
                    case Byte b -> {
                        dos.writeByte(Constants.BYTE);
                        dos.writeByte(b);
                    }
                    case Double d -> {
                        dos.writeByte(Constants.FLOAT);
                        dos.writeDouble(d);
                    }
                    case ExternDef e -> {
                        dos.writeByte(Constants.EXTERN);
                        byte[] bytes = e.name().getBytes(StandardCharsets.UTF_8);
                        dos.writeShort(bytes.length);
                        dos.write(bytes);
                    }
                    case ClassDef cd -> {
                        dos.writeByte(Constants.CLASS);
                        byte[] nameBytes = cd.name().getBytes(StandardCharsets.UTF_8);
                        dos.writeShort(nameBytes.length);
                        dos.write(nameBytes);

                        dos.writeShort(cd.fieldNames().size());
                        for (int i = 0; i < cd.fieldNames().size(); i++) {
                            byte[] fieldBytes = cd.fieldNames().get(i).getBytes(StandardCharsets.UTF_8);
                            dos.writeShort(fieldBytes.length);
                            dos.write(fieldBytes);

                            int defaultIdx = cd.fieldDefaultConstIndices().get(i);
                            dos.writeBoolean(defaultIdx >= 0);
                            if (defaultIdx >= 0) dos.writeShort(defaultIdx);
                        }

                        dos.writeShort(cd.methodNames().size());
                        for (int i = 0; i < cd.methodNames().size(); i++) {
                            byte[] methodBytes = cd.methodNames().get(i).getBytes(StandardCharsets.UTF_8);
                            dos.writeShort(methodBytes.length);
                            dos.write(methodBytes);
                            dos.writeShort(cd.methodConstIndices().get(i));
                        }

                        dos.writeShort(cd.staticFieldCount());
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
