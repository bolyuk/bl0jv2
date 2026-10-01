package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// throw(), and the stdlib that now uses it (Arr.removeAt bounds, Map growth)
class Bl0jv2_ThrowAndLibTest {

    private static String run(Path dir, String body) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/arrlib.bl0'; import 'stdlib/map.bl0'; " + body);
        return Bl0jv2_TestRunner.runFile(entry);
    }

    @Test
    void throwIsCaughtWithItsMessage() {
        assertEquals("boom", Bl0jv2_TestRunner.run("try { throw('boom'); } catch (e) { print e; }"));
    }

    @Test
    void throwCrossesFunctionCalls() {
        assertEquals("deep failure|after", Bl0jv2_TestRunner.run(
                "def inner() { throw('deep failure'); } def outer() { return inner(); } " +
                "try { outer(); } catch (e) { print e + '|'; } print 'after';"));
    }

    @Test
    void anErrValueCanBeRethrown() {
        assertEquals("original", Bl0jv2_TestRunner.run(
                "try { try { throw('original'); } catch (e) { throw(e); } } catch (e2) { print e2; }"));
    }

    @Test
    void uncaughtThrowEndsTheProgramWithItsMessage() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(bl0.bl0jv2.exceptions.Bl0j_VM_Exception.class,
                () -> Bl0jv2_TestRunner.run("throw('fatal');"));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().endsWith("fatal"), e.getMessage());
    }

    @Test
    void aUserFunctionNamedThrowTakesPrecedence() {
        assertEquals("mine", Bl0jv2_TestRunner.run("def throw(x) { return 'mine'; } print throw('a');"));
    }

    @Test
    void removeAtRejectsAnOutOfRangeIndex(@TempDir Path dir) throws IOException {
        assertEquals("Arr.removeAt: index 3 is outside the array (length 3)|Arr.removeAt: index -1 is outside the array (length 3)", run(dir,
                "a = [1, 2, 3]; try { Arr.removeAt(a, 3); } catch (e) { print e + '|'; } try { Arr.removeAt(a, -1); } catch (e) { print e; }"));
    }

    @Test
    void removeAtStillRemoves(@TempDir Path dir) throws IOException {
        // swaps the last element into the hole (order is not preserved)
        assertEquals("[1, 3]|[1, 2]", run(dir,
                "a = [1, 2, 3]; Arr.removeAt(a, 1); b = [1, 2, 3]; Arr.removeAt(b, 2); print str(a) + '|' + str(b);"));
    }

    @Test
    void aMapKeepsEveryEntryWhileItGrows(@TempDir Path dir) throws IOException {
        // starts with 2 buckets and takes 500 entries: it must resize repeatedly
        assertEquals("500|v123|v499|true|false", run(dir,
                "m = new Map(2); i = 0; while (i < 500) { m.set('k' + str(i), 'v' + str(i)); i = i + 1; } " +
                "print str(m.size) + '|' + m.get('k123') + '|' + m.get('k499') + '|' + str(m.has('k0')) + '|' + str(m.has('zz'));"));
    }

    @Test
    void theMapGrowsItsBucketCount(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir,
                "m = new Map(2); i = 0; while (i < 100) { m.set(i, i); i = i + 1; } print m.bucketCount >= 128;"));
    }

    @Test
    void overwritingAndRemovingAcrossResizes(@TempDir Path dir) throws IOException {
        assertEquals("99|true|false|100", run(dir,
                "m = new Map(1); i = 0; while (i < 100) { m.set(i, i); i = i + 1; } m.set(5, 500); m.remove(7); " +
                "print str(m.size) + '|' + str(m.get(5) == 500) + '|' + str(m.has(7)) + '|' + str(m.get(99) + 1);"));
    }

    @Test
    void aMapNeedsAtLeastOneBucket(@TempDir Path dir) throws IOException {
        assertEquals("Map: bucket count must be at least 1, got 0", run(dir,
                "try { m = new Map(0); } catch (e) { print e; }"));
    }
}
