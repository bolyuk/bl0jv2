package bl0.bl0jv2.runtime.device;

/**
 * A device that sits behind some I/O ports. Plain ports are just storage
 * (PortIO); a device claims the ones where reading or writing must DO
 * something: pop a FIFO, start a transfer, put a byte on a wire.
 */
public interface PortDevice {
    /** true if reading 'port' is the device's to answer (otherwise the stored value is returned) */
    default boolean claimsRead(int port) {
        return false;
    }

    /** the value of a claimed read */
    default long read(int port, int widthBytes) {
        return 0;
    }

    /** called after every guest write to a port, with the value written */
    default void onWrite(int port, long value) {
    }
}
