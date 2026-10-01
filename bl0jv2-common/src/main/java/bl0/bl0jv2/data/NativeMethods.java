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
    // loads and runs a separate compiled (.bl0c) file as a genuinely
    // isolated child process - see Bl0jv2_jVM's own registration
    public static final byte EXEC = 0x11;
    // generation-counted event (see Bl0jEvent): eventGen() snapshots it,
    // signalEvent() bumps it and wakes every waiter, waitEvent() sleeps
    // until it differs from a snapshot (or timeout / deliverable interrupt)
    public static final byte NEW_EVENT = 0x12;
    public static final byte SIGNAL_EVENT = 0x13;
    public static final byte WAIT_EVENT = 0x14;
    public static final byte EVENT_GEN = 0x15;
    // inter-processor interrupt: raises a vector on ONE specific core
    public static final byte RAISE_INTERRUPT_ON = 0x16;
}
