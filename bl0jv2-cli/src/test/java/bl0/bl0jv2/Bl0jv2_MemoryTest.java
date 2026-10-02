package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// memory(op, a, b): accounts, limits and statistics of the heap
class Bl0jv2_MemoryTest {

    private static String run(Path dir, String code) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, code);
        return Bl0jv2_TestRunner.runFile(entry);
    }

    @Test
    void anAccountHoldsWhatItsCoreAllocatesAndGivesItBackOnFree(@TempDir Path dir) throws IOException {
        assertEquals("true|true|true", run(dir,
                "memory(0, 5, 0); a = []; i = 0; while (i < 100) { push(a, 'text number ' + str(i)); i += 1; } " +
                "held = memory(1, 5, 0); other = memory(1, 6, 0); " +
                "memory(0, 0, 0); " +
                "print str(held > 5000) + '|' + str(other == 0) + '|'; " +
                "memory(0, 5, 0); free(a); print str(memory(1, 5, 0) < held);"));
    }

    @Test
    void anAccountOverItsLimitFailsInsteadOfTakingMore(@TempDir Path dir) throws IOException {
        String out = run(dir,
                "memory(0, 7, 0); memory(2, 7, 20000); dropToUserMode(); a = []; ok = true; " +   // limits bind a program, not the kernel
                "try { i = 0; while (i < 100000) { push(a, 'x' + str(i)); i += 1; } } catch (e) { print str(e); }");
        assertTrue(out.contains("out of memory") && out.contains("20000"), out);
    }

    @Test
    void theWholeHeapCanHaveALimitAndStatisticsSayWhatIsHeld(@TempDir Path dir) throws IOException {
        String out = run(dir,
                "base = memory(4, 1, 0); memory(5, base + 30000, 0); dropToUserMode(); a = []; " +
                "try { i = 0; while (i < 100000) { push(a, 'y' + str(i)); i += 1; } } catch (e) { print str(e) + '|'; } " +
                "print str(memory(4, 0, 0) > 100) + '|' + str(memory(4, 6, 0) > 0) + '|' + str(memory(4, 2, 0) > 0);");
        assertTrue(out.contains("heap limit") && out.endsWith("true|true|true"), out);
    }

    @Test
    void whenAProcessEndsWhatItHeldGoesToTheSystemAndItsLimitEnds(@TempDir Path dir) throws IOException {
        assertEquals("true|true|true", run(dir,
                "memory(0, 9, 0); memory(2, 9, 100000); a = 'held ' + str(1); b = []; push(b, a); memory(0, 0, 0); " +
                "held = memory(1, 9, 0); sys = memory(1, 0, 0); memory(6, 9, 0); " +
                "print str(held > 0) + '|' + str(memory(1, 9, 0) == 0) + '|' + str(memory(1, 0, 0) >= sys + held - 1 && memory(3, 9, 0) == 0);"));
    }
}
