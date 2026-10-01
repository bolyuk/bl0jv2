package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static bl0.bl0jv2.Bl0jv2_TestRunner.runFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * import/static-linking is AST-level source merging (Bl0jv2_Linker), driven
 * from real files on disk since paths are resolved relative to the
 * importing file's own location - Bl0jv2_TestRunner.run(String) can't
 * exercise that, hence these tests write to @TempDir instead.
 */
class Bl0jv2_ImportTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void basicTwoFileImport(@TempDir Path dir) throws IOException {
        write(dir, "mathlib.bl0", "def square(x) { return x * x; }");
        Path main = write(dir, "main.bl0", "import 'mathlib.bl0'; print square(5);");

        assertEquals("25", runFile(main));
    }

    @Test
    void diamondDependencyDoesNotDuplicateDefinitions(@TempDir Path dir) throws IOException {
        write(dir, "d.bl0", "def base() { return 1; }");
        write(dir, "b.bl0", "import 'd.bl0'; def fromB() { return base() + 10; }");
        write(dir, "c.bl0", "import 'd.bl0'; def fromC() { return base() + 100; }");
        Path main = write(dir, "main.bl0",
                "import 'b.bl0'; import 'c.bl0'; print fromB() + '|' + fromC();");

        assertEquals("11|101", runFile(main));
    }

    @Test
    void importCycleDoesNotInfiniteLoop(@TempDir Path dir) throws IOException {
        write(dir, "a.bl0", "import 'b.bl0'; def fromA() { return 1; }");
        write(dir, "b.bl0", "import 'a.bl0'; def fromB() { return 2; }");
        Path main = write(dir, "main.bl0",
                "import 'a.bl0'; import 'b.bl0'; print fromA() + '|' + fromB();");

        assertEquals("1|2", runFile(main));
    }

    @Test
    void importingNonexistentFileFailsClearly(@TempDir Path dir) throws IOException {
        Path main = write(dir, "main.bl0", "import 'missing.bl0'; print 1;");

        assertThrows(UncheckedIOException.class, () -> runFile(main));
    }
}
