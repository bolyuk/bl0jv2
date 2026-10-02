package bl0.bl0jv2.data;

public final class NativeMethods {
    private NativeMethods() {}

    public static final byte PRINT = 0x00;
    public static final byte PRINT_LN = 0x01;
    public static final byte WAIT = 0x02;
    public static final byte READ = 0x03;
    public static final byte RAISE_INTERRUPT = 0x04;
    public static final byte DISABLE_INTERRUPTS = 0x05;
    public static final byte ENABLE_INTERRUPTS = 0x06;
    public static final byte PANIC = 0x07;
    public static final byte TICKS = 0x08;
    public static final byte CORE_COUNT = 0x09;
    public static final byte CURRENT_CORE = 0x0A;
    public static final byte NEW_MUTEX = 0x0B;
    public static final byte LOCK_MUTEX = 0x0C;
    public static final byte UNLOCK_MUTEX = 0x0D;
    // one-way: a core can only lower its own privilege, never raise it -
    // see Bl0jv2_jVM.CoreContext.privileged and its own doc
    public static final byte DROP_TO_USER_MODE = 0x0E;
    public static final byte IS_PRIVILEGED = 0x0F;
    // idle this core until an interrupt is pending - see Bl0jv2_jVM's own
    // registration for why this is a native (a blocking Java loop), not a
    // dedicated opcode
    public static final byte HALT_CORE = 0x10;
    // generation-counted event (see Bl0jEvent): eventGen() snapshots it,
    // signalEvent() bumps it and wakes every waiter, waitEvent() sleeps
    // until it differs from a snapshot (or timeout / deliverable interrupt)
    public static final byte NEW_EVENT = 0x12;
    public static final byte SIGNAL_EVENT = 0x13;
    public static final byte WAIT_EVENT = 0x14;
    public static final byte EVENT_GEN = 0x15;
    // inter-processor interrupt: raises a vector on ONE specific core
    public static final byte RAISE_INTERRUPT_ON = 0x16;
    // timers: raise a vector after a delay (SET_TIMER, packed [ms, vector,
    // periodic]) and cancel one by id
    public static final byte SET_TIMER = 0x17;
    public static final byte CANCEL_TIMER = 0x18;
    // string helpers, so library code doesn't build strings a character at a
    // time (each intermediate string is a heap entry): strSub(s, from, to),
    // strFind(s, sub, from), strUpper(s), strLower(s), strJoin(array, sep)
    public static final byte STR_SUB = 0x19;
    public static final byte STR_FIND = 0x1A;
    public static final byte STR_UPPER = 0x1B;
    public static final byte STR_LOWER = 0x1C;
    public static final byte STR_JOIN = 0x1D;
    // throw(message): raise an error a surrounding try/catch receives
    public static final byte THROW = 0x1E;
    // strChar(codePoint): the one-character string for a Unicode code point
    public static final byte STR_CHAR = 0x1F;
    // execMem(addr, size, mode): run a compiled program that sits in raw memory
    // (0x11, once exec(path), is free: the VM never reads a host file)
    public static final byte EXEC_MEM = 0x20;
    // memory(op, a, b): who holds how much of the heap, and limits - see Bl0jv2_jVM's registration
    public static final byte MEMORY = 0x21;
}
