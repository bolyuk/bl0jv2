package bl0.bl0jv2;

import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static bl0.bl0jv2.Bl0jv2_TestRunner.runFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** what the linker leaves out: imported functions, classes and methods nothing uses (Bl0jv2_Shaker) */
class Bl0jv2_ShakeTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static String compiled(Path file, boolean shake) throws IOException {
        String source = Files.readString(file);
        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        byte[] bytes = new Bl0jv2_Compiler().compile(Bl0jv2_Linker.resolveImports(ast, file, List.of(), Set.of(), shake));
        return new String(bytes, StandardCharsets.ISO_8859_1);     // names are in the constant pool
    }

    private static final String LIB = String.join("\n",
            "def used() { return helper() + 1; }",
            "def helper() { return 41; }",
            "def unused() { return 0; }",
            "def class Box {",
            "    field v;",
            "    def init(v) { this.v = v; }",
            "    def get() { return this.v; }",
            "    def neverCalled() { return 0; }",
            "    static def make(v) { return new Box(v); }",
            "    static def idle() { return 1; }",
            "}",
            "def class Gone { static field x; }",
            "Gone.x = 5;",
            "");

    @Test
    void unusedImportedFunctionsAndClassesAreLeftOut(@TempDir Path dir) throws IOException {
        write(dir, "lib.bl0", LIB);
        Path main = write(dir, "main.bl0", "import 'lib.bl0'; print used();");
        String shaken = compiled(main, true);
        assertTrue(shaken.contains("used") && shaken.contains("helper"), "what is reached stays");
        assertFalse(shaken.contains("unused"), "an unused function goes");
        assertFalse(shaken.contains("Gone"), "an unused class goes, with its set-up statement");
        assertFalse(shaken.contains("Box"), "an unused class goes");
        assertTrue(compiled(main, false).contains("unused"), "and without shaking it is all there");
        assertEquals("42", runFile(main));
    }

    @Test
    void aClassKeepsOnlyTheMethodsUsed(@TempDir Path dir) throws IOException {
        write(dir, "lib.bl0", LIB);
        Path main = write(dir, "main.bl0", "import 'lib.bl0'; b = Box.make(7); print b.get();");
        String shaken = compiled(main, true);
        assertTrue(shaken.contains("Box.make") && shaken.contains("get") && shaken.contains("init"));
        assertFalse(shaken.contains("neverCalled"));
        assertFalse(shaken.contains("Box.idle"));
        assertEquals("7", runFile(main));
    }

    @Test
    void theEntryFilesOwnDefinitionsAreKept(@TempDir Path dir) throws IOException {
        Path main = write(dir, "main.bl0", "def neverUsed() { return 1; } print 2;");
        assertTrue(compiled(main, true).contains("neverUsed"));
    }

    @Test
    void aFileMarkedLibraryIsKeptWhole(@TempDir Path dir) throws IOException {
        write(dir, "lib.bl0", "// @library\n" + LIB);
        Path main = write(dir, "main.bl0", "import 'lib.bl0'; print used();");
        String shaken = compiled(main, true);
        assertTrue(shaken.contains("unused") && shaken.contains("Gone") && shaken.contains("neverCalled"));
    }

    @Test
    void aNameUsedOnlyThroughAFieldKeepsItsClassForTheCheck(@TempDir Path dir) throws IOException {
        // 'p.get()' on a value that was never made by a visible 'new': the method has to stay declared
        write(dir, "lib.bl0", LIB);
        Path main = write(dir, "main.bl0", "import 'lib.bl0'; def f(p) { return p.get(); } print 1;");
        assertTrue(compiled(main, true).contains("get"));
    }

    @Test
    void functionsUsedAsValuesAndThroughLambdasStay(@TempDir Path dir) throws IOException {
        write(dir, "lib.bl0", "def twice(x) { return x * 2; } def inc(x) { return x + 1; } def dead() { return 0; }");
        Path main = write(dir, "main.bl0",
                "import 'lib.bl0'; f = twice; g = (v) -> inc(v); print f(g(4));");
        String shaken = compiled(main, true);
        assertFalse(shaken.contains("dead"));
        assertEquals("10", runFile(main));
    }
}
