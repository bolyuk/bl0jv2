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
}
