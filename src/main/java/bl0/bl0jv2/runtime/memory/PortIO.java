package bl0.bl0jv2.runtime.memory;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A second, 16-bit, port-addressed bus (0-65535) - the reference-VM
 * stand-in for x86's IN/OUT instructions, deliberately kept separate from
 * {@link RawMemory}'s arena: real x86 has two independent address spaces
 * (memory and I/O ports), and code that means "the network card's status
 * port" should never be able to alias "byte 0x3F8 of RAM" just because the
 * numbers happen to collide.
 *
 * <p>Every port is always "live" here (backed by a flat byte[]) - there is
 * no reservation/registration step, matching real hardware where every port
 * number is wired to *something*, even if that something is a floating bus
 * that reads back whatever was last written (or 0, which is what an
 * unregistered port reads here). A host-side per-port device hook (so a
 * test or the CLI could back a specific port with a fake UART/keyboard/etc,
 * observing writes and producing its own reads instead of falling through
 * to this backing array) is a natural extension point but isn't built yet -
 * out of scope for this PoC.
 *
 * <p>Same locking rationale as RawMemory: read() takes the read lock purely
 * for the JMM happens-before edge between cores, write() takes it too since
 * there's no separate allocator-style bookkeeping to protect here at all -
 * unlike RawMemory, this class has no write-lock-only mutation path.
 */
public final class PortIO {
    private static final int PORT_SPACE = 1 << 16; // 0-65535

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final byte[] ports = new byte[PORT_SPACE];

    private void checkBounds(int port, int widthBytes) {
        if (port < 0 || (long) port + widthBytes > PORT_SPACE)
            throw new Bl0j_VM_Exception("port I/O out of bounds: port " + port + " width " + (widthBytes * 8) + " bits");
    }

    // big-endian, matching RawMemory.peek()/this project's own bytecode
    // format
    public long read(int port, int widthBytes) {
        lock.readLock().lock();
        try {
            checkBounds(port, widthBytes);
            long value = 0;
            for (int i = 0; i < widthBytes; i++)
                value = (value << 8) | (ports[port + i] & 0xFFL);
            return value;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void write(int port, int widthBytes, long value) {
        lock.readLock().lock();
        try {
            checkBounds(port, widthBytes);
            for (int i = widthBytes - 1; i >= 0; i--) {
                ports[port + i] = (byte) value;
                value >>>= 8;
            }
        } finally {
            lock.readLock().unlock();
        }
    }
}
