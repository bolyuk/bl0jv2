package bl0.bl0jv2.runtime;

import bl0.bl0jv2.data.*;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import bl0.bl0jv2.exceptions.Bl0j_VM_Panic;
import bl0.bl0jv2.runtime.arithmetic.ArithmeticOperators;
import bl0.bl0jv2.runtime.interrupt.InterruptController;
import bl0.bl0jv2.runtime.memory.RawMemory;
import bl0.bl0jv2.runtime.values.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

public final class Bl0jv2_jVM {

    // the Java object nil unboxes to; only ever reached through NanBox.NIL,
    // so its identity is never observed outside this class
    private static final Object NIL_OBJECT = new Object() {
        @Override
        public String toString() { return "nil"; }
    };

    // marks a freed heap slot - distinct from NIL_OBJECT so a freed value
    // is never confused with a field that legitimately holds nil
    private static final Object FREED = new Object() {
        @Override
        public String toString() { return "<freed>"; }
    };

    private static final long[] EMPTY_CELLS = new long[0];

    private Writer out;
    private BufferedReader stdin;
    // two cores print/read()-ing concurrently would otherwise interleave or
    // corrupt output/input with zero warning - the one piece of shared
    // state that's easy to miss since it's plain Writer/BufferedReader
    // fields, not a collection
    private final Object ioLock = new Object();

    private final ArithmeticOperators ops = new ArithmeticOperators(NIL_OBJECT);
    private final RawMemory rawMemory = new RawMemory();
    private final InterruptController interrupts = new InterruptController();

    // one call stack / try-catch handler stack per core (Java thread) - see
    // CoreContext. Bound lazily as core 0 the first time an unregistered
    // thread touches currentContext(), so every existing single-threaded
    // caller (every test, the CLI) keeps working with zero changes; a real
    // worker thread (added in a later step) binds its own id explicitly
    // before doing anything else.
    private final ThreadLocal<CoreContext> coreContext = new ThreadLocal<>();

    private CoreContext currentContext() {
        CoreContext ctx = coreContext.get();
        if (ctx == null) {
            ctx = new CoreContext(0);
            coreContext.set(ctx);
        }
        return ctx;
    }

    private Map<Byte, Function<Object, Object>> nativeMethods = new HashMap<>();

    // reference-typed values (strings, FunDefs, ...) that registers/consts
    // hold as a NanBox REF index rather than inline. Shared across cores,
    // so boxRef()/FREE's mutations take heapLock's write lock and unbox()'s
    // read takes its read lock - boxRef() is the least-hot heap path (one
    // call per allocation) so a coarser lock than RawMemory's disjoint-state
    // split is fine here; unbox() is the hottest path in the whole
    // interpreter, which is exactly why read/write (not one coarse lock)
    // matters for this specific structure.
    private final List<Object> heap = new ArrayList<>();
    // indices freed via free() - boxRef() reuses these before growing heap,
    // so alloc/free cycles don't exhaust maxHeapEntries even with zero
    // actual leaks
    private final ArrayDeque<Integer> freeHeapSlots = new ArrayDeque<>();
    // 0 = unlimited, matching this VM's historical (unbounded) behavior
    private long maxHeapEntries = 0;
    private final ReentrantReadWriteLock heapLock = new ReentrantReadWriteLock();

    private long[] consts;
    private byte[] instructions;

    // set fresh in feed_compiled_file(); ticks() reads elapsed time against
    // this, not against JVM startup, so it's monotonic per *program*, not
    // per VM instance
    private long programStartNanos;

    // 1 = today's exact single-threaded behavior (only core 0, the calling
    // thread, ever exists). set_core_count() only records the desired
    // count; actual worker threads are spawned separately (see
    // startWorkerCoresIfNeeded()), once real dispatch exists to give them
    // something to do.
    private int coreCount = 1;
    // one inbox per worker core (1..coreCount-1) - core 0 has none, it's
    // the calling thread and is never itself a dispatch target in this
    // increment. Populated once by startWorkerCoresIfNeeded(); a
    // ConcurrentHashMap since dispatch() (any core) reads it concurrently
    // with worker spawning (which only ever happens once, from
    // run_instructions()).
    private final Map<Integer, BlockingQueue<DispatchedWork>> coreInboxes = new ConcurrentHashMap<>();
    private volatile boolean workersStarted = false;
    // set by whichever core's Bl0j_VM_Panic fires first. On a single core,
    // a panic halting that one call stack WAS halting the whole machine;
    // with N cores it isn't, unless every core's execute() loop also
    // checks this and aborts - otherwise a panic on one core would leave
    // others running (or worse, hung forever in lock() on a mutex the
    // panicking core held and never released).
    private volatile boolean panicked = false;

    private record DispatchedWork(Object callee, long argRaw) {}

    public Bl0jv2_jVM() {
        nativeMethods.put(NativeMethods.PRINT, (d) -> {
            synchronized (ioLock) {
                if (out != null) {
                    try {
                        out.append(d.toString());
                    } catch (IOException e) {
                        return -1;
                    }
                }
                else System.out.print(d);
            }
            return 0;
        });
        nativeMethods.put(NativeMethods.PRINT_LN, (d) -> {
            synchronized (ioLock) {
                if (out != null) {
                    try {
                        out.append("\n").append(d.toString());
                    } catch (IOException e) {
                        return -1;
                    }
                }
                else System.out.println(d);
            }
            return 0;
        });
        nativeMethods.put(NativeMethods.WAIT, (d) -> {
            try {
                Thread.sleep((int) d);
            } catch (InterruptedException e) {
                return -1;
            }
            return 0;
        });
        // ignores its (dummy nil) input; CALL_NATIVE below writes whatever
        // this returns back into a register, which is what actually makes
        // read() usable as an expression
        nativeMethods.put(NativeMethods.READ, (ignored) -> readLine());
        // fires a host-observable interrupt from bl0jv2 code itself; the
        // actual firing is deferred to execute()'s cooperative poll, not
        // immediate - see InterruptController
        nativeMethods.put(NativeMethods.RAISE_INTERRUPT, (v) -> {
            interrupts.raiseInterrupt((int) v);
            return null;
        });
        // masking is per-core state (see CoreContext.disableDepth's own
        // comment) - a counter, not a flag, so nested disable/enable pairs
        // nest safely; an unbalanced enableInterrupts() is a permissive
        // no-op rather than an error, matching this VM's general "don't
        // throw over misuse" stance elsewhere (e.g. raising an unregistered
        // vector)
        nativeMethods.put(NativeMethods.DISABLE_INTERRUPTS, (ignored) -> {
            currentContext().disableDepth++;
            return null;
        });
        nativeMethods.put(NativeMethods.ENABLE_INTERRUPTS, (ignored) -> {
            CoreContext ctx = currentContext();
            if (ctx.disableDepth > 0)
                ctx.disableDepth--;
            return null;
        });
        // unrecoverable by design - see Bl0j_VM_Panic's javadoc and the
        // special case for it in execute()'s exception-catch block below
        nativeMethods.put(NativeMethods.PANIC, (msg) -> {
            throw new Bl0j_VM_Panic(String.valueOf(msg));
        });
        // milliseconds elapsed since feed_compiled_file() - a real
        // (wall-clock) monotonic source, matching wait()'s own use of real
        // time. Cast to int like every other bl0jv2 number: wraps after
        // about 24.8 days, same as a real 32-bit millisecond timer would -
        // at that exact wrap instant this could coincidentally read -1,
        // which CALL_NATIVE's generic error sentinel would misread as a
        // failure; accepted as effectively unreachable for this VM
        nativeMethods.put(NativeMethods.TICKS, (ignored) ->
                (int) ((System.nanoTime() - programStartNanos) / 1_000_000));
        nativeMethods.put(NativeMethods.CORE_COUNT, (ignored) -> coreCount);
        nativeMethods.put(NativeMethods.CURRENT_CORE, (ignored) -> currentContext().coreId);
        nativeMethods.put(NativeMethods.NEW_MUTEX, (ignored) -> new Bl0jMutex());
        nativeMethods.put(NativeMethods.LOCK_MUTEX, (m) -> {
            ((Bl0jMutex) m).lock();
            return null;
        });
        nativeMethods.put(NativeMethods.UNLOCK_MUTEX, (m) -> {
            ((Bl0jMutex) m).unlock();
            return null;
        });
    }

    private static double toDouble(Object numeric) {
        return ArithmeticOperators.toDouble(numeric);
    }

    private static boolean isNumeric(Object value) {
        return value instanceof Integer || value instanceof Double;
    }

    // numeric equality crosses int/double (5 == 5.0 is true), matching the
    // implicit promotion already used by +, -, *, /, %, ** ; a class that
    // declares its own 'equals' method gets to decide for its own
    // instances; everything else falls back to plain value equality.
    // Public (not just package-private) so Bl0jTuple, now in
    // runtime.values, can reuse it for its own (recursive) content equality.
    public static boolean valuesEqual(Object left, Object right) {
        if (isNumeric(left) && isNumeric(right))
            return toDouble(left) == toDouble(right);
        if (left instanceof Bl0jInstance li && li.cls.hasMethod("equals")) {
            Object result = li.owner.invoke(li.cls.method("equals"), li.owner.box(li), li.owner.box(right));
            return result instanceof Boolean b && b;
        }
        return Objects.equals(left, right);
    }

    private static Object negate(Object value) {
        if (value instanceof Integer i) return -i;
        if (value instanceof Double d) return -d;
        throw new Bl0j_VM_Exception("cannot negate " + value.getClass().getSimpleName());
    }

    private static int length(Object value) {
        if (value instanceof Bl0jArray arr) return arr.length();
        if (value instanceof Bl0jTuple t) return t.length();
        if (value instanceof String s) return s.length();
        throw new Bl0j_VM_Exception("cannot take length of " + value.getClass().getSimpleName());
    }

    private static Bl0jArray requireMutableArray(Object target) {
        if (target instanceof Bl0jTuple)
            throw new Bl0j_VM_Exception("cannot mutate a tuple");
        if (target instanceof Bl0jArray arr)
            return arr;
        throw new Bl0j_VM_Exception("expected an array, got " + target.getClass().getSimpleName());
    }

    private static int bitNot(Object value) {
        if (value instanceof Integer i) return ~i;
        throw new Bl0j_VM_Exception("cannot apply '~' to " + value.getClass().getSimpleName());
    }

    // shares arr[-1]-style negative indexing with Bl0jArray.getRaw
    private static char charAt(String s, int index) {
        int i = Bl0jArray.normalizeIndex(index, s.length());
        if (i < 0 || i >= s.length())
            throw new Bl0j_VM_Exception("string index out of bounds: " + index + " (length " + s.length() + ")");
        return s.charAt(i);
    }

    private static Object toInt(Object value) {
        if (value instanceof Integer) return value;
        if (value instanceof Double d) return d.intValue();
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Character c) return (int) c;
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new Bl0j_VM_Exception("cannot convert '" + s + "' to int");
            }
        }
        throw new Bl0j_VM_Exception("cannot convert " + value.getClass().getSimpleName() + " to int");
    }

    private static Object toFloat(Object value) {
        if (value instanceof Double) return value;
        if (value instanceof Integer i) return i.doubleValue();
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new Bl0j_VM_Exception("cannot convert '" + s + "' to float");
            }
        }
        throw new Bl0j_VM_Exception("cannot convert " + value.getClass().getSimpleName() + " to float");
    }

    private static String typeName(Object value) {
        if (value instanceof Integer) return "int";
        if (value instanceof Double) return "float";
        if (value instanceof Boolean) return "bool";
        if (value instanceof Character) return "char";
        if (value instanceof String) return "string";
        if (value instanceof Bl0jArray) return "array";
        if (value instanceof Bl0jTuple) return "tuple";
        if (value instanceof Bl0jError) return "err";
        if (value instanceof FunDef) return "function";
        if (value instanceof Bl0jClosure) return "function";
        if (value instanceof Bl0jClass) return "class";
        if (value instanceof Bl0jInstance instance) return instance.cls.name;
        if (value instanceof Bl0jMutex) return "mutex";
        if (value == NIL_OBJECT) return "nil";
        throw new Bl0j_VM_Exception("unknown type: " + value.getClass().getSimpleName());
    }

    private String readLine() {
        synchronized (ioLock) {
            if (stdin == null)
                stdin = new BufferedReader(new InputStreamReader(System.in));
            try {
                return stdin.readLine(); // null on EOF
            } catch (IOException e) {
                throw new Bl0j_VM_Exception("read failed: " + e.getMessage());
            }
        }
    }

    public void feed_compiled_file(ByteBuffer bytes){
        bytes.order(ByteOrder.BIG_ENDIAN);

        if(bytes.getInt() != C.MAGIC){
            throw new Bl0j_VM_Exception("Wrong magic number");
        }

        short version = bytes.getShort();

        if(version != C.VERSION){
            throw new Bl0j_VM_Exception("Incompatible Bl0jv2_jVM version: [ " + C.VERSION + " != " + version+" ]");
        }

        short constants_length = bytes.getShort();
        short registers_length = bytes.getShort();

        // seeds core 0's context specifically - feed_compiled_file() is
        // always called from the "main" thread, which is exactly the
        // thread that will lazily bind as core 0 the first time
        // currentContext() runs
        CoreContext ctx = currentContext();
        ctx.callStack.clear();
        heap.clear();
        freeHeapSlots.clear();
        ctx.handlerStack.clear();
        ctx.callStack.add(new Frame(new long[registers_length], -1, -1));
        consts = new long[constants_length];

        rawMemory.reset();
        interrupts.reset();
        programStartNanos = System.nanoTime();

        for (int i = 0; i < constants_length; i++) {
            byte type = bytes.get();
            switch (type) {
                case Constants.INT -> consts[i] = NanBox.ofInt(bytes.getInt());
                case Constants.STRING -> consts[i] = boxRef(get_str(bytes));
                case Constants.BOOL -> consts[i] = NanBox.ofBoolean(bytes.get() != 0);
                case Constants.FUN -> consts[i] = boxRef(new FunDef(
                        get_str(bytes),
                        bytes.getInt() & 0xFFFF,
                        bytes.getShort(),
                        bytes.getShort()));
                case Constants.BYTE -> consts[i] = NanBox.ofInt(bytes.get());
                case Constants.FLOAT -> consts[i] = Double.doubleToLongBits(bytes.getDouble());
                // methods are always registered (and thus loaded) before
                // the class itself, so consts[methodConstIdx] is already
                // populated whenever we get here - see ClassDef's javadoc
                case Constants.CLASS -> {
                    String className = get_str(bytes);
                    int fieldCount = bytes.getShort() & 0xFFFF;
                    List<String> fieldNames = new ArrayList<>();
                    long[] fieldDefaults = new long[fieldCount];
                    Arrays.fill(fieldDefaults, NanBox.NIL);
                    for (int f = 0; f < fieldCount; f++) {
                        fieldNames.add(get_str(bytes));
                        if (bytes.get() != 0) // hasDefault
                            fieldDefaults[f] = consts[bytes.getShort() & 0xFFFF];
                    }
                    int methodCount = bytes.getShort() & 0xFFFF;
                    Map<String, FunDef> methods = new HashMap<>();
                    for (int m = 0; m < methodCount; m++) {
                        String methodName = get_str(bytes);
                        int methodConstIdx = bytes.getShort() & 0xFFFF;
                        methods.put(methodName, (FunDef) unbox(consts[methodConstIdx]));
                    }
                    int staticFieldCount = bytes.getShort() & 0xFFFF;
                    consts[i] = boxRef(new Bl0jClass(className, fieldNames, fieldDefaults, methods, staticFieldCount));
                }
                default -> throw new Bl0j_VM_Exception("Unknown const type: " + type);
            }
        }

        int remaining = bytes.remaining();

        if(remaining % C.INSTR_WIDTH != 0)
            throw new Bl0j_VM_Exception("wrong amount of instructions");

        instructions = new byte[remaining];
        bytes.get(instructions);
    }

    public void set_out_writer(Writer out){
        this.out = out;
    }

    // mirrors set_out_writer() for read()'s input side - lets a host
    // supply its own input source instead of always falling back to
    // System.in (readLine()'s existing lazy-init still does that when
    // nobody calls this)
    public void set_in_reader(Reader in){
        this.stdin = in instanceof BufferedReader br ? br : new BufferedReader(in);
    }

    // takes effect on the next boxRef() call - no need to call this before
    // feed_compiled_file() the way set_max_raw_bytes() does
    public void set_max_heap_entries(long maxHeapEntries){
        this.maxHeapEntries = maxHeapEntries;
    }

    // must be called before feed_compiled_file(), which is what actually
    // (re)sizes the raw memory arena - changing this after a program is
    // already loaded has no effect until the next load
    public void set_max_raw_bytes(long maxRawBytes){
        rawMemory.setMaxBytes(maxRawBytes);
    }

    // how many cores (real worker threads, plus core 0 itself) this VM
    // instance runs with. Must be called before run_instructions(), which
    // is what actually spawns the worker threads; n=1 (the default) is
    // today's exact single-threaded behavior with no workers spawned at all
    public void set_core_count(int n) {
        if (n < 1)
            throw new Bl0j_VM_Exception("core count must be at least 1");
        this.coreCount = n;
    }

    // raises 'vector' from Java (e.g. a host-side timer/device thread) -
    // the actual handler call happens cooperatively, the next time
    // execute()'s poll fires, not synchronously from this call
    public void raiseInterrupt(int vector) {
        interrupts.raiseInterrupt(vector);
    }

    // how many instructions execute() runs between interrupt polls at the
    // outermost call; default 5 (InterruptController's own default)
    public void set_interrupt_poll_interval(int instructions) {
        interrupts.setPollInterval(instructions);
    }

    public void run_instructions() throws IOException {
        startWorkerCoresIfNeeded();
        execute(0, -1);
    }

    // spawns cores 1..coreCount-1 as daemon threads, each idling on its own
    // inbox until dispatch() hands it work. Runs exactly once per VM
    // instance (not once per feed_compiled_file() call): reloading a new
    // program into an already-multi-core VM instance isn't supported in
    // this increment - accepted, matches every existing caller's actual
    // usage (one feed_compiled_file() + one run_instructions() each). Only
    // called from run_instructions(), i.e. always after feed_compiled_file()
    // has fully populated instructions/consts - Thread.start()'s own
    // happens-before guarantee makes those safely visible to every worker
    // with no extra synchronization needed.
    private synchronized void startWorkerCoresIfNeeded() {
        if (workersStarted || coreCount <= 1)
            return;
        workersStarted = true;
        for (int core = 1; core < coreCount; core++) {
            BlockingQueue<DispatchedWork> inbox = new LinkedBlockingQueue<>();
            coreInboxes.put(core, inbox);
            Thread t = new Thread(new CoreWorker(core, inbox), "bl0jv2-core-" + core);
            t.setDaemon(true);
            t.start();
        }
    }

    // fire-and-forget: the dispatching core never blocks on this, it just
    // hands the work to the target core's queue and moves on
    private void dispatchToCore(int core, Object callee, long argRaw) {
        BlockingQueue<DispatchedWork> inbox = coreInboxes.get(core);
        if (inbox == null)
            throw new Bl0j_VM_Exception("cannot dispatch to core " + core + ": no such worker core (coreCount=" + coreCount + ")");
        inbox.add(new DispatchedWork(callee, argRaw));
    }

    // one worker core's idle loop: bind this thread's own CoreContext, push
    // one synthetic base frame (mirrors feed_compiled_file()'s own core-0
    // seed - RETURN's callStack.size()==1 guard means invoke() only works
    // correctly with a frame already underneath it), then block on the
    // inbox until dispatch() hands over work. invoke() is reused as-is for
    // running dispatched work - no separate interpreter loop needed.
    private final class CoreWorker implements Runnable {
        private final int coreId;
        private final BlockingQueue<DispatchedWork> inbox;

        CoreWorker(int coreId, BlockingQueue<DispatchedWork> inbox) {
            this.coreId = coreId;
            this.inbox = inbox;
        }

        @Override
        public void run() {
            CoreContext ctx = new CoreContext(coreId);
            coreContext.set(ctx);
            ctx.callStack.push(new Frame(new long[1], -1, -1));

            while (true) {
                DispatchedWork work;
                try {
                    work = inbox.take();
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    invoke(work.callee(), work.argRaw());
                } catch (Bl0j_VM_Panic e) {
                    // stop accepting further work entirely - matches real
                    // hardware halting on a kernel panic, not just this one
                    // task failing. An already-idle worker (blocked in
                    // inbox.take() when the panic happened elsewhere) has
                    // no way to notice this proactively and simply never
                    // receives more work once the panicking core stops
                    // dispatching - accepted, these are daemon threads that
                    // don't block JVM shutdown either way.
                    System.err.println("core " + coreId + " halted: " + e.getMessage());
                    return;
                } catch (Exception e) {
                    // an uncaught exception must not silently kill this
                    // core's thread (it would just vanish otherwise) - report
                    // and keep idling for more dispatched work
                    System.err.println("core " + coreId + " uncaught exception: " + e);
                }
            }
        }
    }

    // resolves a callable value into its FunDef plus any captured cells to
    // prepend as leading args - shared by CALL and invoke() so a Bl0jClosure
    // (lambda) is handled identically whether it's called from bytecode or
    // from native Java code
    private record ResolvedCallee(FunDef fun, long[] capturedCells) {}

    private ResolvedCallee resolveCallee(Object callee) {
        if (callee instanceof Bl0jClosure closure)
            return new ResolvedCallee(closure.funDef(), closure.capturedCells());
        return new ResolvedCallee((FunDef) callee, EMPTY_CELLS);
    }

    // synchronously calls a bl0jv2 function from native Java code (used by
    // a class's own toString()/equals() override, which native code paths
    // like Bl0jInstance.toString() or the == operator can't otherwise
    // reach - and by a fired interrupt handler) and returns its unboxed
    // result. Pushes one frame and runs until exactly that frame returns,
    // rather than until HALT. callee is a FunDef (an ordinary function) or
    // a Bl0jClosure (a lambda) - see resolveCallee(). Public so
    // Bl0jInstance/Bl0jTuple (runtime.values) can call it for a
    // user-defined toString()/equals().
    public Object invoke(Object callee, long... args) {
        CoreContext ctx = currentContext();
        int stopAtDepth = ctx.callStack.size();
        ResolvedCallee resolved = resolveCallee(callee);
        FunDef fun = resolved.fun();
        long[] capturedCells = resolved.capturedCells();

        long[] regs = new long[fun.regs()];
        for (int i = 0; i < capturedCells.length; i++)
            regs[i + 1] = capturedCells[i];
        for (int i = 0; i < args.length; i++)
            regs[capturedCells.length + i + 1] = args[i];
        // resultReg=0 is safe to reuse here: every frame's own reg[0] is
        // never assigned to a real variable by the compiler (regIndex
        // starts at 1), so it's free scratch space for exactly this
        ctx.callStack.push(new Frame(regs, -1, 0));
        try {
            execute(fun.address() * C.INSTR_WIDTH, stopAtDepth);
        } catch (IOException e) {
            throw new Bl0j_VM_Exception("invoke failed: " + e.getMessage());
        }
        return unbox(ctx.callStack.peek().regs()[0]);
    }

    // startAddr/stopAtDepth let invoke() re-enter this same loop for a
    // synchronous nested call: run_instructions() calls this with
    // stopAtDepth=-1 (run to HALT); invoke() passes the depth its own
    // pushed frame will pop back to, so execution returns to Java once
    // that one frame's RETURN runs, without disturbing the enclosing call.
    private void execute(int startAddr, int stopAtDepth) throws IOException {

            // resolved once - this whole call runs start-to-finish on one
            // thread, so re-resolving per instruction would be pure waste
            CoreContext ctx = currentContext();

            // cooperative interrupt polling - only at the outermost call
            // (stopAtDepth < 0); a nested invoke() (toString/equals/another
            // handler) never re-enters this, so a handler can't be
            // interrupted mid-fire
            int sinceLastPoll = 0;

            for(int addr = startAddr; addr < instructions.length;){
                try {

                // checked every instruction, on every core, regardless of
                // stopAtDepth - unlike interrupt polling (deliberately
                // outermost-only), a panic must cut through nested invoke()
                // calls too, since it means the whole machine is halting
                if (panicked)
                    throw new Bl0j_VM_Panic("halted: another core panicked");

                if (stopAtDepth < 0 && ++sinceLastPoll >= interrupts.pollInterval()) {
                    sinceLastPoll = 0;
                    // the cadence above always ticks on schedule regardless
                    // of masking (matches this VM's pre-multi-core timing
                    // exactly) - only the actual delivery attempt is
                    // skipped while this core is masked, so a pending
                    // interrupt stays queued rather than being dropped
                    if (ctx.disableDepth == 0) {
                        InterruptController.Fired fired = interrupts.pollNext();
                        if (fired != null)
                            invoke(fired.handlerFn(), NanBox.ofInt(fired.vector()));
                    }
                }

                byte opcode = (byte) (instructions[addr] & 0xFF);

                int a = ((instructions[addr+1] & 0xFF) << 8) | (instructions[addr+2] & 0xFF);
                int b = ((instructions[addr+3] & 0xFF) << 8) | (instructions[addr+4] & 0xFF);
                addr += C.INSTR_WIDTH;

                long[] reg = ctx.callStack.peek().regs();

                switch (opcode) {
                    case OpCodes.LOAD_NIL -> reg[a] = NanBox.NIL;
                    case OpCodes.LOAD_CONST -> reg[a] = consts[b];

                    case OpCodes.LR_ADD -> reg[a] = box(ops.add.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_SUB -> reg[a] = box(ops.sub.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_MUL -> reg[a] = box(ops.mul.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_DIV -> reg[a] = box(ops.div.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_REM -> reg[a] = box(ops.rem.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_POW -> reg[a] = box(ops.pow.calculate(unbox(reg[a]), unbox(reg[b])));

                    case OpCodes.LR_AND -> reg[a] = box(ops.and.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_OR -> reg[a] = box(ops.or.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_XOR -> reg[a] = box(ops.xor.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_SHL -> reg[a] = box(ops.shl.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_SHR -> reg[a] = box(ops.shr.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LR_USHR -> reg[a] = box(ops.ushr.calculate(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.BIT_NOT -> reg[a] = NanBox.ofInt(bitNot(unbox(reg[a])));

                    case OpCodes.JUMP -> addr = a * C.INSTR_WIDTH;
                    case OpCodes.JUMP_IF -> { if ( (boolean) unbox(reg[a])) addr = b * C.INSTR_WIDTH; }
                    case OpCodes.JUMP_IF_NOT -> { if (!(boolean) unbox(reg[a])) addr = b * C.INSTR_WIDTH; }

                    case OpCodes.EQ -> reg[a] = NanBox.ofBoolean(valuesEqual(unbox(reg[a]), unbox(reg[b])));
                    case OpCodes.LESS -> reg[a] = NanBox.ofBoolean(toDouble(unbox(reg[a])) < toDouble(unbox(reg[b])));
                    case OpCodes.GREATER  -> reg[a] = NanBox.ofBoolean(toDouble(unbox(reg[a])) > toDouble(unbox(reg[b])));
                    case OpCodes.NOT -> reg[a] = NanBox.ofBoolean(!(boolean) unbox(reg[a]));

                    case OpCodes.MOV -> reg[a] = reg[b];
                    case OpCodes.SET -> reg[a] = NanBox.ofInt(b);
                    case OpCodes.NEG  -> reg[a] = box(negate(unbox(reg[a])));

                    // reg[a] holds either a plain FunDef (an ordinary named
                    // function, called directly) or a Bl0jClosure (a
                    // lambda) - a closure's own captured cells are
                    // prepended before the caller's own args, landing in
                    // exactly the leading parameter slots the compiler
                    // reserved for them (see Bl0jv2_Compiler's LambdaNode
                    // handling; resolveCallee() is shared with invoke())
                    case OpCodes.CALL -> {
                        ResolvedCallee resolved = resolveCallee(unbox(reg[a]));
                        FunDef fun = resolved.fun();
                        long[] capturedCells = resolved.capturedCells();

                        long[] args = new long[fun.arity()];
                        System.arraycopy(capturedCells, 0, args, 0, capturedCells.length);
                        for (int i = capturedCells.length; i < fun.arity(); i++)
                            args[i] = reg[b + 1 + (i - capturedCells.length)];

                        gen_frame(ctx, fun, args, addr, b);
                        addr = fun.address() * C.INSTR_WIDTH;
                    }

                    // writes the native function's own return value back
                    // into its operand register - harmless for print/
                    // println/wait (their status code lands somewhere
                    // nothing reads, since they're only ever used as bare
                    // statements), and what makes a value-producing native
                    // like read() usable as an expression at all
                    case OpCodes.CALL_NATIVE -> {
                        var nativeFun = nativeMethods.get((byte) a);
                        if (nativeFun == null)
                            throw new Bl0j_VM_Exception("unknown native method: " + a);
                        Object result = nativeFun.apply(unbox(reg[b]));
                        if (result instanceof Integer code && code == -1)
                            throw new Bl0j_VM_Exception("native method " + a + " returned error");
                        reg[b] = result == null ? NanBox.NIL : box(result);
                    }

                    // elements sit at reg[a+1 .. a+b], mirroring CALL's
                    // args-adjacent-to-the-base-register convention
                    case OpCodes.NEW_ARRAY -> {
                        int count = b;
                        long[] elements = new long[count];
                        for (int i = 0; i < count; i++) elements[i] = reg[a + 1 + i];
                        reg[a] = boxRef(new Bl0jArray(elements, this));
                    }

                    case OpCodes.NEW_TUPLE -> {
                        int count = b;
                        long[] elements = new long[count];
                        for (int i = 0; i < count; i++) elements[i] = reg[a + 1 + i];
                        reg[a] = boxRef(new Bl0jTuple(elements, this));
                    }

                    case OpCodes.INDEX_GET -> {
                        Object target = unbox(reg[a]);
                        int index = (int) unbox(reg[b]);
                        reg[a] = switch (target) {
                            case Bl0jArray array -> array.getRaw(index);
                            case Bl0jTuple tuple -> tuple.getRaw(index);
                            case String s -> NanBox.ofChar(charAt(s, index));
                            default -> throw new Bl0j_VM_Exception("cannot index " + target.getClass().getSimpleName());
                        };
                    }

                    // index and value sit at reg[b] and reg[b+1]
                    case OpCodes.INDEX_SET -> {
                        Bl0jArray array = requireMutableArray(unbox(reg[a]));
                        int index = (int) unbox(reg[b]);
                        array.setRaw(index, reg[b + 1]);
                    }

                    case OpCodes.LENGTH -> reg[a] = NanBox.ofInt(length(unbox(reg[a])));

                    // mutates the Bl0jArray object the reference points at,
                    // not the register holding that reference - reg[a]
                    // (the array's own slot) is never overwritten
                    case OpCodes.PUSH -> requireMutableArray(unbox(reg[a])).push(reg[b]);
                    case OpCodes.POP -> reg[a] = requireMutableArray(unbox(reg[b])).pop();

                    // count consecutive elements land in reg[a+1 .. a+count],
                    // mirroring NEW_ARRAY/NEW_TUPLE's own convention
                    case OpCodes.UNPACK -> {
                        Object target = unbox(reg[a]);
                        int count = b;
                        int len = length(target);
                        if (len != count)
                            throw new Bl0j_VM_Exception("cannot unpack " + len + " values into " + count + " targets");
                        for (int i = 0; i < count; i++)
                            reg[a + 1 + i] = switch (target) {
                                case Bl0jArray arr -> arr.getRaw(i);
                                case Bl0jTuple tup -> tup.getRaw(i);
                                default -> throw new Bl0j_VM_Exception("cannot unpack " + target.getClass().getSimpleName());
                            };
                    }

                    case OpCodes.TO_INT -> reg[a] = box(toInt(unbox(reg[a])));
                    case OpCodes.TO_FLOAT -> reg[a] = box(toFloat(unbox(reg[a])));
                    case OpCodes.TO_STRING -> reg[a] = boxRef(unbox(reg[a]).toString());
                    case OpCodes.TYPE_OF -> reg[a] = boxRef(typeName(unbox(reg[a])));

                    // b holds the catch block's address (patched by the
                    // compiler), a the register the caught error lands in
                    case OpCodes.TRY_ENTER -> ctx.handlerStack.push(new Handler(b * C.INSTR_WIDTH, a, ctx.callStack.size()));
                    case OpCodes.TRY_EXIT -> ctx.handlerStack.pop();
                    case OpCodes.MAKE_ERR -> reg[a] = boxRef(new Bl0jError(String.valueOf(unbox(reg[a]))));

                    // mutates a's own slot: class-ref in, instance-ref out
                    case OpCodes.NEW_INSTANCE -> reg[a] = boxRef(new Bl0jInstance((Bl0jClass) unbox(reg[a]), this));

                    case OpCodes.GET_FIELD -> {
                        Bl0jInstance instance = (Bl0jInstance) unbox(reg[a]);
                        String name = (String) unbox(consts[b]);
                        reg[a] = instance.getFieldRaw(name);
                    }

                    // field name's const index and the value sit at reg[b]
                    // and reg[b+1], same packing trick as INDEX_SET
                    case OpCodes.SET_FIELD -> {
                        Bl0jInstance instance = (Bl0jInstance) unbox(reg[a]);
                        String name = (String) unbox(consts[(int) unbox(reg[b])]);
                        instance.setFieldRaw(name, reg[b + 1]);
                    }

                    // b is the static field's own index, resolved at
                    // compile time - class-ref in, value out
                    case OpCodes.GET_STATIC_FIELD -> {
                        Bl0jClass cls = (Bl0jClass) unbox(reg[a]);
                        reg[a] = cls.getStaticFieldRaw(b);
                    }

                    // the field index and the value sit at reg[b] and
                    // reg[b+1], same packing trick as SET_FIELD
                    case OpCodes.SET_STATIC_FIELD -> {
                        Bl0jClass cls = (Bl0jClass) unbox(reg[a]);
                        int fieldIndex = (int) unbox(reg[b]);
                        cls.setStaticFieldRaw(fieldIndex, reg[b + 1]);
                    }

                    // mutates a's own slot: object in, resolved FunDef out
                    case OpCodes.LOOKUP_METHOD -> {
                        Bl0jInstance instance = (Bl0jInstance) unbox(reg[a]);
                        String name = (String) unbox(consts[b]);
                        reg[a] = box(instance.cls.method(name));
                    }

                    case OpCodes.MAKE_CELL -> reg[a] = boxRef(new Bl0jCell());

                    // mutates a's own slot: cell-ref in, its current value out
                    case OpCodes.CELL_GET -> reg[a] = ((Bl0jCell) unbox(reg[a])).value;

                    case OpCodes.CELL_SET -> ((Bl0jCell) unbox(reg[a])).value = reg[b];

                    // a's own slot already holds the lambda's FunDef (from
                    // a prior LOAD_CONST); the captured cells' own
                    // references sit at reg[a+1..a+b], mirroring
                    // NEW_ARRAY/NEW_TUPLE's convention
                    case OpCodes.MAKE_CLOSURE -> {
                        FunDef fun = (FunDef) unbox(reg[a]);
                        long[] cells = new long[b];
                        for (int i = 0; i < b; i++) cells[i] = reg[a + 1 + i];
                        reg[a] = boxRef(new Bl0jClosure(fun, cells));
                    }

                    // dispatches on reg[a]'s raw (still-boxed) tag, not its
                    // unboxed value, since that's the only way to tell a
                    // REF (a managed heap value) apart from a plain int (a
                    // raw-memory address, once ALLOC/PEEK/POKE exist)
                    case OpCodes.FREE -> {
                        if (NanBox.isBoxed(reg[a]) && NanBox.tagOf(reg[a]) == NanBox.TAG_REF) {
                            int idx = NanBox.asRefIndex(reg[a]);
                            heapLock.writeLock().lock();
                            try {
                                if (heap.get(idx) == FREED)
                                    throw new Bl0j_VM_Exception("double free");
                                heap.set(idx, FREED);
                                freeHeapSlots.push(idx);
                            } finally {
                                heapLock.writeLock().unlock();
                            }
                        } else if (unbox(reg[a]) instanceof Integer rawAddr) {
                            rawMemory.free(rawAddr);
                        } else {
                            throw new Bl0j_VM_Exception("cannot free " + typeName(unbox(reg[a])));
                        }
                        reg[a] = NanBox.NIL;
                    }

                    // mutates a's own slot: requested size in, address out
                    case OpCodes.ALLOC -> reg[a] = NanBox.ofInt(rawMemory.alloc((int) unbox(reg[a])));

                    // b is the width in bits (8/16/32), a compile-time
                    // immediate - not a register, unlike almost everything
                    // else b is used for elsewhere in this VM
                    case OpCodes.PEEK -> reg[a] = NanBox.ofInt((int) rawMemory.peek((int) unbox(reg[a]), b / 8));

                    // width and value sit at reg[b] and reg[b+1], same
                    // packing trick as SET_FIELD/SET_STATIC_FIELD
                    case OpCodes.POKE -> {
                        int width = (int) unbox(reg[b]);
                        long value = ((Number) unbox(reg[b + 1])).longValue();
                        rawMemory.poke((int) unbox(reg[a]), width / 8, value);
                    }

                    // fn (FunDef or Bl0jClosure) in a, [vector, priority]
                    // packed into reg[b]/reg[b+1] - same convention as
                    // POKE's [width, value]
                    case OpCodes.REGISTER_HANDLER -> {
                        int vector = (int) unbox(reg[b]);
                        int priority = (int) unbox(reg[b + 1]);
                        interrupts.registerHandler(vector, priority, unbox(reg[a]));
                    }

                    // a and b are the address and size directly, not packed
                    // registers - see RawMemory.reserve()
                    case OpCodes.RESERVE -> rawMemory.reserve((int) unbox(reg[a]), (int) unbox(reg[b]));

                    // fn in a, [core, arg] packed into reg[b]/reg[b+1] -
                    // same convention as REGISTER_HANDLER's [vector, priority]
                    case OpCodes.DISPATCH -> {
                        int core = (int) unbox(reg[b]);
                        long argRaw = reg[b + 1];
                        dispatchToCore(core, unbox(reg[a]), argRaw);
                    }

                    case OpCodes.RETURN -> {
                        if(ctx.callStack.size() == 1)
                            throw new Bl0j_VM_Exception("return call for last stack frame");

                        // any handler registered inside the frame being
                        // returned from goes out of scope with it, exactly
                        // like it would on an exception unwinding past it
                        while (!ctx.handlerStack.isEmpty() && ctx.handlerStack.peek().callStackDepth() >= ctx.callStack.size())
                            ctx.handlerStack.pop();

                        Frame frame = ctx.callStack.pop();
                        ctx.callStack.peek().regs[frame.resultReg]  = reg[a];
                        addr = frame.addressToReturn;

                        // the frame invoke() pushed has just returned -
                        // hand control back to the native Java caller
                        // instead of continuing to interpret whatever
                        // bytecode happens to sit at addressToReturn
                        if (stopAtDepth >= 0 && ctx.callStack.size() == stopAtDepth)
                            return;
                    }
                    case OpCodes.HALT -> {
                        return;
                    }
                    default -> throw new Bl0j_VM_Exception("Unknown opcode: " + opcode);
                }
                } catch (Bl0j_VM_Panic e) {
                    // unlike every other exception below, a panic is never
                    // routed to a registered handler, no matter how close
                    // one is - it always propagates straight out, through
                    // any nested invoke() calls, until it reaches the
                    // top-level caller and halts the VM entirely. Setting
                    // this flag is what makes every OTHER core notice too
                    // (see the panicked check at the top of this loop) -
                    // harmless to set repeatedly if several cores panic
                    // around the same time.
                    panicked = true;
                    throw e;
                } catch (Exception e) {
                    // a handler registered before this execute() call
                    // started (i.e. outside a nested invoke()) doesn't
                    // belong to it - let the exception propagate to the
                    // enclosing execute() call instead of catching it here
                    // with a callStack/handlerStack state this call isn't
                    // entitled to unwind
                    boolean handlerIsInThisCall = !ctx.handlerStack.isEmpty()
                            && (stopAtDepth < 0 || ctx.handlerStack.peek().callStackDepth() > stopAtDepth);

                    if (handlerIsInThisCall) {
                        Handler handler = ctx.handlerStack.pop();
                        while (ctx.callStack.size() > handler.callStackDepth())
                            ctx.callStack.pop();
                        String message = e.getMessage() != null ? e.getMessage() : e.toString();
                        ctx.callStack.peek().regs()[handler.errReg()] = box(new Bl0jError(message));
                        addr = handler.catchAddr();
                        continue;
                    }
                    throw new Bl0j_VM_Exception("Exception on address: "+addr/C.INSTR_WIDTH+" - "+ e);
                }
            }
    }

    private void gen_frame(CoreContext ctx, FunDef fun, long[] args, int addressToReturn, int resultReg) {
        long[] regs = new long[fun.regs()];

        regs[0] = NanBox.NIL;
        for (int i = 0; i < args.length; i++)
            regs[i+1] = args[i];

        ctx.callStack.push(new Frame(regs, addressToReturn, resultReg));
    }

    private long boxRef(Object value) {
        heapLock.writeLock().lock();
        try {
            if (!freeHeapSlots.isEmpty()) {
                int slot = freeHeapSlots.pop();
                heap.set(slot, value);
                return NanBox.ofRef(slot);
            }
            if (maxHeapEntries > 0 && heap.size() >= maxHeapEntries)
                throw new Bl0j_VM_Exception("out of memory: heap entry limit (" + maxHeapEntries + ") reached");
            heap.add(value);
            return NanBox.ofRef(heap.size() - 1);
        } finally {
            heapLock.writeLock().unlock();
        }
    }

    // converts a NaN-boxed register/const value into the plain Java object
    // it represents, for the (currently still Object-based) OperatorTable
    // and native methods to work with. Public so Bl0jArray and friends,
    // now in runtime.values, can unbox their own elements (e.g. for
    // toString) without duplicating this.
    public Object unbox(long bits) {
        if (!NanBox.isBoxed(bits))
            return Double.longBitsToDouble(bits);
        return switch (NanBox.tagOf(bits)) {
            case NanBox.TAG_INT -> NanBox.asInt(bits);
            case NanBox.TAG_BOOL -> NanBox.asBoolean(bits);
            case NanBox.TAG_NIL -> NIL_OBJECT;
            case NanBox.TAG_REF -> {
                Object v;
                heapLock.readLock().lock();
                try {
                    v = heap.get(NanBox.asRefIndex(bits));
                } finally {
                    heapLock.readLock().unlock();
                }
                if (v == FREED)
                    throw new Bl0j_VM_Exception("use after free");
                yield v;
            }
            case NanBox.TAG_CHAR -> NanBox.asChar(bits);
            default -> throw new Bl0j_VM_Exception("unreachable NanBox tag");
        };
    }

    // public so Bl0jInstance/Bl0jTuple (runtime.values) can box a value
    // before passing it into a user-defined equals()/toString()
    public long box(Object value) {
        if (value instanceof Integer i) return NanBox.ofInt(i);
        if (value instanceof Boolean b) return NanBox.ofBoolean(b);
        // Double.doubleToLongBits (not the raw variant) canonicalizes every
        // NaN to a single fixed pattern outside NanBox's reserved tag space,
        // so a genuine float NaN can never be mistaken for a boxed value
        if (value instanceof Double d) return Double.doubleToLongBits(d);
        if (value instanceof Character c) return NanBox.ofChar(c);
        if (value == NIL_OBJECT) return NanBox.NIL;
        return boxRef(value); // String, FunDef, ...
    }

    private String get_str(ByteBuffer bytes){
        int len = bytes.getShort() & 0xFFFF;
        byte[] strBytes = new byte[len];
        bytes.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8);
    }

    private record Frame(long[] regs, int addressToReturn, int resultReg) {}

    // callStackDepth is callStack.size() at the moment TRY_ENTER ran, so a
    // RETURN that unwinds past this depth knows the handler no longer
    // applies (see the RETURN and exception-catch cases below)
    private record Handler(int catchAddr, int errReg, int callStackDepth) {}

    // everything specific to one core's (one Java thread's) thread of
    // execution - a nested class, not a top-level file, since it needs
    // direct access to the private Frame/Handler records above. disableDepth
    // lives here, not on InterruptController, because real hardware gives
    // each core its own interrupt-enable flag: masking interrupts on one
    // core must not affect another core's own polling.
    private static final class CoreContext {
        final int coreId;
        final ArrayDeque<Frame> callStack = new ArrayDeque<>();
        final ArrayDeque<Handler> handlerStack = new ArrayDeque<>();
        // this core's own interrupt-enable state - a nesting-safe counter,
        // not a flag (disableInterrupts()/enableInterrupts() must nest
        // safely: an inner critical section returning must not re-enable an
        // outer one that's still in progress). Deliberately per-core, not
        // shared: masking interrupts on one core must not affect another
        // core's own polling, matching how real hardware gives each CPU
        // core its own interrupt-enable flag.
        int disableDepth = 0;

        CoreContext(int coreId) {
            this.coreId = coreId;
        }
    }
}
