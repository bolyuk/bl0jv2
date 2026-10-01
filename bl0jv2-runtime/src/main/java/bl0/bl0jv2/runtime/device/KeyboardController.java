package bl0.bl0jv2.runtime.device;

import java.util.ArrayDeque;

/**
 * The terminal's receive side: a serial line with a FIFO. The host puts the
 * bytes the terminal sent (UTF-8, ANSI sequences for arrows and the like) in the
 * FIFO and raises an interrupt; the guest drains it from the data port until
 * the status port says it is empty. Nothing is lost if the guest is slow, up to
 * the FIFO's size, and nothing needs pacing: that is what the FIFO is for.
 *
 * <pre>
 *   0x0F48  in8    next byte (0 when empty); reading removes it
 *   0x0F49  in8    1 when at least one byte is waiting
 * </pre>
 */
public final class KeyboardController implements PortDevice {
    public static final int DATA_PORT = 0x0F48;
    public static final int STATUS_PORT = 0x0F49;
    private static final int CAPACITY = 4096;

    private final ArrayDeque<Byte> fifo = new ArrayDeque<>();

    /** called by the host; false if the FIFO was too full to take everything */
    public synchronized boolean push(byte[] bytes) {
        boolean all = true;
        for (byte b : bytes) {
            if (fifo.size() >= CAPACITY) { all = false; break; }
            fifo.add(b);
        }
        return all;
    }

    @Override
    public boolean claimsRead(int port) {
        return port == DATA_PORT || port == STATUS_PORT;
    }

    @Override
    public synchronized long read(int port, int widthBytes) {
        if (port == STATUS_PORT) return fifo.isEmpty() ? 0 : 1;
        Byte b = fifo.poll();
        return b == null ? 0 : (b & 0xFF);
    }
}
