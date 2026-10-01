package bl0.bl0jv2.runtime.device;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A 16550-style UART: the serial port a terminal hangs off. Eight byte-wide
 * registers at {@link #BASE}, laid out and behaving as the guest's driver would find
 * them on a real chip:
 *
 * <pre>
 *   +0  RBR (read) / THR (write)     received byte / byte to send         (DLL when DLAB=1)
 *   +1  IER                          interrupt enables: 1 receive data, 2 transmitter empty,
 *                                    4 line status, 8 modem                (DLM when DLAB=1)
 *   +2  IIR (read) / FCR (write)     interrupt cause / FIFO control: 1 enable, 2 clear receive,
 *                                    4 clear transmit, bits 6-7 receive trigger (1, 4, 8, 14 bytes)
 *   +3  LCR                          line control; bit 7 = DLAB, selects the divisor latch
 *   +4  MCR                          modem control (stored)
 *   +5  LSR                          line status: 1 data ready, 2 overrun (cleared by reading),
 *                                    0x20 transmit FIFO empty, 0x40 transmitter idle
 *   +6  MSR                          modem status (clear to send and data set ready, always)
 *   +7  SCR                          scratch
 * </pre>
 *
 * Both directions have 16-byte FIFOs (one byte when the guest has not enabled them,
 * as on a 16450). The transmitter sends bytes to {@code sink} as UTF-8 decoded text;
 * with a baud rate set ({@link #setBaud}) it sends one byte per character time and
 * the transmit FIFO stays full meanwhile, so a driver that does not check LSR loses
 * bytes exactly as it would on hardware; with none (the default) it empties at once.
 * The host feeds the receiver through {@link #receive}: with flow control (the
 * default, like RTS/CTS) bytes wait outside the chip while the FIFO is full; without
 * it they are lost and the overrun flag is set. The interrupt is raised through
 * {@code irq} whenever an enabled cause is pending (data below the trigger level counts
 * as the chip's character timeout having elapsed: simulated time passes however it likes), and the guest reads IIR until it
 * reports none.
 */
public final class UartController implements PortDevice {
    public static final int BASE = 0x0F40;
    private static final int FIFO_DEPTH = 16;

    private final Consumer<String> sink;
    private final Runnable irq;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final ByteBuffer pending = ByteBuffer.allocate(8);
    private final CharBuffer chars = CharBuffer.allocate(8);

    private final ArrayDeque<Byte> rx = new ArrayDeque<>();
    private final ArrayDeque<Byte> wire = new ArrayDeque<>();   // outside the chip, waiting for FIFO room
    private final ArrayDeque<Byte> tx = new ArrayDeque<>();
    private int ier, lcr, mcr, scr, dll = 1, dlm;
    private boolean fifoEnabled;
    private int triggerLevel = 1;
    private boolean overrun;
    private boolean threPending;        // transmitter became empty since IIR was last read
    private boolean flowControl = true;
    private int baud;                   // 0 = send instantly
    private ScheduledExecutorService sender;

    public UartController(Consumer<String> sink, Runnable irq) {
        this.sink = sink;
        this.irq = irq;
    }

    // ---- host side ----

    /** the terminal sent these bytes */
    public synchronized void receive(byte[] bytes) {
        for (byte b : bytes) wire.add(b);
        pump();
        update();
    }

    public synchronized void setFlowControl(boolean on) {
        this.flowControl = on;
    }

    /** bits per second the transmitter sends at; 0 sends instantly */
    public synchronized void setBaud(int baud) {
        this.baud = baud;
    }

    // ---- the guest's side ----

    @Override
    public boolean claimsRead(int port) {
        return port >= BASE && port < BASE + 8;
    }

    @Override
    public synchronized long read(int port, int widthBytes) {
        switch (port - BASE) {
            case 0 -> {
                if (dlab()) return dll;
                Byte b = rx.poll();
                pump();
                update();
                return b == null ? 0 : (b & 0xFF);
            }
            case 1 -> { return dlab() ? dlm : ier; }
            case 2 -> { return iir(); }
            case 3 -> { return lcr; }
            case 4 -> { return mcr; }
            case 5 -> { return lsr(); }
            case 6 -> { return 0x30; }
            default -> { return scr; }
        }
    }

    @Override
    public synchronized void onWrite(int port, long value) {
        int v = (int) value & 0xFF;
        switch (port - BASE) {
            case 0 -> { if (dlab()) dll = v; else transmit((byte) v); }
            case 1 -> {
                if (dlab()) { dlm = v; return; }
                ier = v & 0x0F;
                // enabling the transmitter-empty interrupt while it is empty raises it at once
                if ((ier & 2) != 0 && tx.isEmpty()) threPending = true;
                update();
            }
            case 2 -> {
                fifoEnabled = (v & 1) != 0;
                triggerLevel = switch ((v >> 6) & 3) { case 0 -> 1; case 1 -> 4; case 2 -> 8; default -> 14; };
                if ((v & 2) != 0) { rx.clear(); pump(); }
                if ((v & 4) != 0) tx.clear();
                update();
            }
            case 3 -> lcr = v;
            case 4 -> mcr = v;
            case 7 -> scr = v;
            default -> { }
        }
    }

    private boolean dlab() {
        return (lcr & 0x80) != 0;
    }

    private int depth() {
        return fifoEnabled ? FIFO_DEPTH : 1;
    }

    private int lsr() {
        int v = 0;
        if (!rx.isEmpty()) v |= 0x01;
        if (overrun) v |= 0x02;
        if (tx.isEmpty()) v |= 0x60;
        overrun = false;
        update();
        return v;
    }

    // 0x01 = nothing pending; otherwise the cause in bits 1-3, highest priority first
    private int iir() {
        int fifoBits = fifoEnabled ? 0xC0 : 0;
        if ((ier & 4) != 0 && overrun) return fifoBits | 0x06;
        if ((ier & 1) != 0 && rx.size() >= triggerLevel) return fifoBits | 0x04;
        if ((ier & 1) != 0 && !rx.isEmpty()) return fifoBits | 0x0C;   // below the trigger: the character timeout
        if ((ier & 2) != 0 && threPending) {
            threPending = false;            // reading IIR acknowledges this cause
            return fifoBits | 0x02;
        }
        return fifoBits | 0x01;
    }

    private boolean interruptPending() {
        return ((ier & 4) != 0 && overrun)
                || ((ier & 1) != 0 && !rx.isEmpty())
                || ((ier & 2) != 0 && threPending);
    }

    private void update() {
        if (interruptPending()) irq.run();
    }

    // moves bytes from the wire into the receive FIFO while it has room
    private void pump() {
        while (!wire.isEmpty()) {
            if (rx.size() >= depth()) {
                if (flowControl) return;
                wire.poll();
                overrun = true;
                continue;
            }
            rx.add(wire.poll());
        }
    }

    private void transmit(byte b) {
        if (tx.size() >= FIFO_DEPTH) return;      // a full FIFO drops the byte: the driver should have looked at LSR
        if (baud <= 0) {
            emit(b);
            threPending = true;
            update();
            return;
        }
        tx.add(b);
        if (sender == null) {
            sender = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "uart-tx");
                t.setDaemon(true);
                return t;
            });
        }
        long charTime = 10_000_000_000L / baud;     // ten bits per character
        sender.schedule(() -> {
            synchronized (UartController.this) {
                Byte next = tx.poll();
                if (next != null) emit(next);
                if (tx.isEmpty()) { threPending = true; update(); }
            }
        }, charTime * tx.size(), TimeUnit.NANOSECONDS);
    }

    // a sent byte reaches the other end: UTF-8, a character may arrive in pieces
    private void emit(byte b) {
        if (b >= 0 && pending.position() == 0) {
            sink.accept(String.valueOf((char) b));
            return;
        }
        pending.put(b);
        pending.flip();
        chars.clear();
        decoder.decode(pending, chars, false);
        pending.compact();
        chars.flip();
        if (chars.hasRemaining()) sink.accept(chars.toString());
    }
}
