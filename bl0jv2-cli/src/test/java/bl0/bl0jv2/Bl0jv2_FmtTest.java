package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// stdlib/str/fmt.bl0 - Fmt.format(template, args)
class Bl0jv2_FmtTest {

    // same import convention as Bl0jv2_StdlibTest: a 'stdlib/...' path that
    // doesn't exist on disk falls back to the classpath copy
    private static String run(Path dir, String body) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/str/fmt.bl0'; " + body);
        return Bl0jv2_TestRunner.runFile(entry);
    }

    private static String fmt(Path dir, String expr) throws IOException {
        return run(dir, "print " + expr + ";");
    }

    @Test
    void sequentialPlaceholders(@TempDir Path dir) throws IOException {
        assertEquals("3 + 4 = 7", fmt(dir, "Fmt.format('{} + {} = {}', [3, 4, 3 + 4])"));
    }

    @Test
    void mixedTypes(@TempDir Path dir) throws IOException {
        assertEquals("x=1.5 s=hi b=true n=nil", fmt(dir, "Fmt.format('x={} s={} b={} n={}', [1.5, 'hi', true, nil])"));
    }

    @Test
    void indexedPlaceholdersDoNotMoveTheSequentialCounter(@TempDir Path dir) throws IOException {
        assertEquals("b a a b", fmt(dir, "Fmt.format('{1} {0} {} {}', ['a', 'b'])"));
    }

    @Test
    void escapedBraces(@TempDir Path dir) throws IOException {
        assertEquals("{x} 5 }", fmt(dir, "Fmt.format('{{x}} {} }}', [5])"));
    }

    @Test
    void hexAndZeroPaddedHex(@TempDir Path dir) throws IOException {
        assertEquals("ff|000000ff|ffffffff", fmt(dir, "Fmt.format('{:x}|{:08x}|{:x}', [255, 255, -1])"));
    }

    @Test
    void widthAndAlignment(@TempDir Path dir) throws IOException {
        assertEquals("[    42][42    ][  hi][0042]", fmt(dir, "Fmt.format('[{:6}][{:<6}][{:>4}][{:04}]', [42, 42, 'hi', 42])"));
    }

    @Test
    void unterminatedBraceIsCopiedThrough(@TempDir Path dir) throws IOException {
        assertEquals("a { b {", fmt(dir, "Fmt.format('a { b {', [1])"));
    }

    @Test
    void missingArgumentIsACatchableError(@TempDir Path dir) throws IOException {
        assertEquals("err", run(dir, "try { Fmt.format('{} {}', [1]); } catch (e) { print typeOf(e); }"));
    }

    @Test
    void emptyTemplateAndNoPlaceholders(@TempDir Path dir) throws IOException {
        assertEquals("|plain", fmt(dir, "Fmt.format('', []) + '|' + Fmt.format('plain', [])"));
    }
}
