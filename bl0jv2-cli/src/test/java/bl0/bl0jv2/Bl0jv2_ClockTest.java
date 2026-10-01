package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the real-time clock ports 0x0F70-0x0F77
class Bl0jv2_ClockTest {

    private static final String READ =
            "print str(in8(0x0F70)) + ' ' + str(in8(0x0F71)) + ' ' + str(in8(0x0F72)) + ' ' + str(in8(0x0F73)) + ' ' + " +
            "str(in8(0x0F74)) + ' ' + str(in8(0x0F75)) + ' ' + str(in16(0x0F76));";

    private static long millis(int y, int mo, int d, int h, int mi, int s) {
        return LocalDateTime.of(y, mo, d, h, mi, s).toEpochSecond(ZoneOffset.UTC) * 1000;
    }

    @Test
    void theFieldsAreTheDateAndTimeInUtc() {
        // 2026-10-01 is a Thursday (4); 1970-01-01 a Thursday too; 2000-02-29 a Tuesday (2)
        assertEquals("5 4 3 1 10 4 2026", Bl0jv2_TestRunner.run(READ, vm -> vm.set_clock(() -> millis(2026, 10, 1, 3, 4, 5))));
        assertEquals("0 0 0 1 1 4 1970", Bl0jv2_TestRunner.run(READ, vm -> vm.set_clock(() -> 0)));
        assertEquals("59 59 23 29 2 2 2000", Bl0jv2_TestRunner.run(READ, vm -> vm.set_clock(() -> millis(2000, 2, 29, 23, 59, 59))));
        assertEquals("0 0 0 5 11 0 2023", Bl0jv2_TestRunner.run(READ, vm -> vm.set_clock(() -> millis(2023, 11, 5, 0, 0, 0))));   // a Sunday
    }

    @Test
    void readingTheSecondsLatchesTheRestSoTheFieldsAgree() {
        // the clock moves between the reads, but the minute/hour read after the seconds belong to the first read
        var now = new AtomicLong(millis(2026, 1, 1, 23, 59, 59));
        String out = Bl0jv2_TestRunner.run("s = in8(0x0F70); n = in8(0x0F71); h = in8(0x0F72); print str(s) + ' ' + str(n) + ' ' + str(h);",
                vm -> vm.set_clock(() -> now.getAndAdd(2000)));        // 2 s later at every latch
        assertEquals("59 59 23", out);
    }

    @Test
    void withoutASourceItIsTheHostsClock() {
        String out = Bl0jv2_TestRunner.run("in8(0x0F70); print str(in16(0x0F76));");
        assertTrue(Integer.parseInt(out) >= 2024, out);
    }

    @Test
    void theClockLibraryReadsAndFormatsTheTime(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Path entry = dir.resolve("entry.bl0");
        java.nio.file.Files.writeString(entry, "import 'stdlib/time/clock.bl0'; t = Clock.now(); " +
                "print Clock.text(t) + ' ' + Clock.weekdayName(t) + ' ' + str(Clock.minuteStamp(t) - Clock.minuteStamp([2026, 10, 1, 3, 3, 0, 4]));");
        assertEquals("2026-10-01 03:04:05 Thu 1", Bl0jv2_TestRunner.runFile(entry, vm -> vm.set_clock(() -> millis(2026, 10, 1, 3, 4, 5))));
    }
}
