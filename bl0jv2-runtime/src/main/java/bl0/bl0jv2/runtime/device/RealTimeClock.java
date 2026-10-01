package bl0.bl0jv2.runtime.device;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.LongSupplier;

/**
 * A real-time clock, the way a PC's CMOS one is read: the date and time as separate fields, in UTC. Reading the
 * seconds port takes a snapshot of the whole time, so the other fields read after it belong to the same instant.
 *
 * <pre>
 *   0x0F70  in8   second (0-59); reading it latches the time for the ports below
 *   0x0F71  in8   minute (0-59)
 *   0x0F72  in8   hour (0-23)
 *   0x0F73  in8   day of the month (1-31)
 *   0x0F74  in8   month (1-12)
 *   0x0F75  in8   day of the week (0 = Sunday)
 *   0x0F76  in16  year (e.g. 2026)
 * </pre>
 *
 * The host decides what "now" is ({@link #setSource}); by default the host's own clock. A test gives it a clock
 * it moves by hand.
 */
public final class RealTimeClock implements PortDevice {
    public static final int FIRST_PORT = 0x0F70;
    public static final int LAST_PORT = 0x0F77;

    private volatile LongSupplier millis = System::currentTimeMillis;
    private LocalDateTime latched = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);

    /** the clock reads 'epochMillis' of this supplier (milliseconds since 1970-01-01 UTC) */
    public void setSource(LongSupplier epochMillis) {
        this.millis = epochMillis;
    }

    @Override
    public boolean claimsRead(int port) {
        return port >= FIRST_PORT && port <= LAST_PORT;
    }

    @Override
    public synchronized long read(int port, int widthBytes) {
        if (port == FIRST_PORT) latched = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis.getAsLong()), ZoneOffset.UTC);
        return switch (port) {
            case 0x0F70 -> latched.getSecond();
            case 0x0F71 -> latched.getMinute();
            case 0x0F72 -> latched.getHour();
            case 0x0F73 -> latched.getDayOfMonth();
            case 0x0F74 -> latched.getMonthValue();
            case 0x0F75 -> latched.getDayOfWeek().getValue() % 7;      // java: Monday 1 ... Sunday 7
            case 0x0F76 -> widthBytes >= 2 ? latched.getYear() & 0xFFFF : latched.getYear() & 0xFF;
            case 0x0F77 -> (latched.getYear() >> 8) & 0xFF;
            default -> 0;
        };
    }
}
