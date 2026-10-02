package bl0.bl0jv2.runtime.device;

import java.security.SecureRandom;
import java.util.Random;

/**
 * A source of random numbers, the way a hardware one (a TRNG: the ESP32's, the Raspberry Pi's bcm2835-rng) is read:
 * every read of the port gives fresh random bits. The host decides where they come from ({@link #setSource}); by
 * default the host's SecureRandom. A test gives it a seeded generator, so a run can be repeated.
 *
 * <pre>
 *   0x0F78  in8 / in16 / in32   random bits (as many as the read is wide); every read gives new ones
 * </pre>
 */
public final class RandomDevice implements PortDevice {
    public static final int PORT = 0x0F78;

    private volatile Random source = new SecureRandom();

    public void setSource(Random source) {
        this.source = source;
    }

    @Override
    public boolean claimsRead(int port) {
        return port == PORT;
    }

    @Override
    public long read(int port, int widthBytes) {
        int bits = source.nextInt();
        return switch (widthBytes) {
            case 1 -> bits & 0xFF;
            case 2 -> bits & 0xFFFF;
            default -> bits & 0xFFFFFFFFL;
        };
    }
}
