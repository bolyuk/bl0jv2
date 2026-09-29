package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static bl0.bl0jv2.Bl0jv2_TestRunner.runFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

// exercises the real files under stdlib/ (not a copy embedded in the test),
// so this also catches a stdlib file that's syntactically broken or whose
// behavior drifted from what these tests expect. Each entry script imports
// the actual library by absolute path (forward slashes, to sidestep string
// escaping on a Windows-style backslash path) since import resolution is
// relative to the *importing* file, which here lives under @TempDir, not
// next to the library.
class Bl0jv2_StdlibTest {

    private static String libPath(String fileName) {
        return Path.of("stdlib", fileName).toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String run(Path dir, String libFile, String body) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import '" + libPath(libFile) + "'; " + body);
        return runFile(entry);
    }

    // --- mathlib ---

    @Test
    void mathAbsMinMax(@TempDir Path dir) throws IOException {
        assertEquals("5|3|3|7", run(dir, "mathlib.bl0",
                "print abs(-5) + '|' + abs(3) + '|' + min(3, 7) + '|' + max(3, 7);"));
    }

    @Test
    void mathFloorCeilRound(@TempDir Path dir) throws IOException {
        assertEquals("2|-3|3|-2|3|2", run(dir, "mathlib.bl0",
                "print floor(2.5) + '|' + floor(-2.5) + '|' + ceil(2.5) + '|' + ceil(-2.5) + '|' + round(2.5) + '|' + round(2.4);"));
    }

    @Test
    void mathSqrtAndClamp(@TempDir Path dir) throws IOException {
        assertEquals("2.0|5|1|10", run(dir, "mathlib.bl0",
                "print sqrt(4.0) + '|' + clamp(5, 1, 10) + '|' + clamp(-3, 1, 10) + '|' + clamp(30, 1, 10);"));
    }

    @Test
    void mathToHex(@TempDir Path dir) throws IOException {
        assertEquals("ff|10|0", run(dir, "mathlib.bl0",
                "print toHex(255) + '|' + toHex(16) + '|' + toHex(0);"));
    }

    // the actual point of toHex: a negative int's real 32-bit bit pattern,
    // not '-' plus a decimal magnitude - matches what 0xDEADBEEF itself
    // parses to (see Bl0jv2_ArithmeticTest's hex-literal tests)
    @Test
    void mathToHexOfANegativeIntShowsTheFullBitPatternNotASignedDecimal(@TempDir Path dir) throws IOException {
        assertEquals("ffffffff|deadbeef", run(dir, "mathlib.bl0",
                "print toHex(-1) + '|' + toHex(0xDEADBEEF);"));
    }

    // --- arrlib ---

    @Test
    void arrContainsAndIndexOf(@TempDir Path dir) throws IOException {
        assertEquals("true|1|-1", run(dir, "arrlib.bl0",
                "arr = [10, 20, 30]; print contains(arr, 20) + '|' + indexOf(arr, 20) + '|' + indexOf(arr, 99);"));
    }

    @Test
    void arrReverseAndSlice(@TempDir Path dir) throws IOException {
        assertEquals("[3, 2, 1]|[2, 3]", run(dir, "arrlib.bl0",
                "arr = [1, 2, 3]; print reverse(arr) + '|' + slice(arr, 1, 3);"));
    }

    @Test
    void arrJoin(@TempDir Path dir) throws IOException {
        assertEquals("1-2-3", run(dir, "arrlib.bl0", "print join([1, 2, 3], '-');"));
    }

    @Test
    void arrMapFilterReduceWithNamedFunctions(@TempDir Path dir) throws IOException {
        assertEquals("[2, 4, 6]|[2]|6", run(dir, "arrlib.bl0",
                "def double(x) { return x * 2; } " +
                "def isEven(x) { return x % 2 == 0; } " +
                "def add(a, b) { return a + b; } " +
                "arr = [1, 2, 3]; " +
                "print map(arr, double) + '|' + filter(arr, isEven) + '|' + reduce(arr, add, 0);"));
    }

    // --- str/* ---
    // split further than mathlib/arrlib: a single strlib.bl0 with all of
    // these functions together overflowed the per-program instruction
    // ceiling on its own when it was still 255 (it landed around 260 just
    // from the function bodies, before any user code), so each piece is
    // its own file and shares small dependencies (substr.bl0) via 'import'
    // instead of duplicating them. The ceiling has since been widened, but
    // the split is still a reasonable way to keep each import cheap.

    @Test
    void strTrim(@TempDir Path dir) throws IOException {
        assertEquals("hi", run(dir, "str/trim.bl0", "print trim('  hi  ');"));
    }

    @Test
    void strUpperLower(@TempDir Path dir) throws IOException {
        assertEquals("HELLO-world", run(dir, "str/case.bl0",
                "print upper('Hello') + '-' + lower('World');"));
    }

    @Test
    void strStartsEndsWith(@TempDir Path dir) throws IOException {
        assertEquals("true|true|false", run(dir, "str/affix.bl0",
                "print startsWith('hello', 'he') + '|' + endsWith('hello', 'lo') + '|' + startsWith('hi', 'hello');"));
    }

    @Test
    void strFind(@TempDir Path dir) throws IOException {
        assertEquals("6|-1", run(dir, "str/find.bl0",
                "print find('hello world', 'world') + '|' + find('hello world', 'xyz');"));
    }

    @Test
    void strSplit(@TempDir Path dir) throws IOException {
        assertEquals("[a, b, c]", run(dir, "str/split.bl0", "print split('a,b,c', ',');"));
    }
}
