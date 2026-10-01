package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// shared libraries: compiled once, loaded once (execMem mode 2), linked by name into the programs
// loaded after them - functions, classes, static state and all
class Bl0jv2_SharedLibTest {

    @TempDir
    Path dir;

    private static final int AT = 30000;

    private Path write(String name, String source) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, source);
        return file;
    }

    // compiles 'file'; the files named in 'shared' are libraries (declared, not included)
    private byte[] compile(Path file, Path... shared) throws IOException {
        Set<String> keys = new HashSet<>();
        for (Path s : shared) keys.add(Bl0jv2_Linker.libraryKey(s));
        String source = Files.readString(file);
        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        return new Bl0jv2_Compiler().compile(Bl0jv2_Linker.resolveImports(ast, file, java.util.List.of(), keys));
    }

    // statements that put the image in memory: as hex text in a constant and a loop (a poke per byte
    // would need a register per instruction and overflow the frame of a big image)
    private static String place(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        StringBuilder sb = new StringBuilder("def put(s, at) { i = 0; while (i < len(s)) { a = int(s[i]); b = int(s[i + 1]); ");
        sb.append("poke8(at + (i / 2), ((a < 58 ? a - 48 : a - 87) << 4) | (b < 58 ? b - 48 : b - 87)); i += 2; } } ");
        for (int from = 0; from < hex.length(); from += 8000)
            sb.append("put('").append(hex, from, Math.min(hex.length(), from + 8000)).append("', ").append(AT + from / 2).append("); ");
        return sb.toString();
    }

    // loads an image into memory and runs it in 'mode'
    private static int loads;

    private static String load(byte[] image, int mode) {
        // each load defines its own put() helper; a distinct name per load keeps them apart
        return place(image).replace("put(", "put" + (++loads) + "(") + "execMem(" + AT + ", " + image.length + ", " + mode + "); ";
    }

    @Test
    void aProgramCallsALibraryFunctionAndUsesItsClasses() throws IOException {
        Path lib = write("lib.bl0",
                "def twice(x) { return x * 2; } " +
                "def class Counter { static field total; field n; def init(n) { this.n = n; } def bump() { this.n = this.n + 1; Counter.total = Counter.total + 1; return this.n; } static def reset() { Counter.total = 0; } } " +
                "Counter.total = 100;");
        Path app = write("app.bl0", "import 'lib.bl0'; c = new Counter(5); c.bump(); c.bump(); print str(twice(21)) + '|' + str(c.n) + '|' + str(Counter.total); Counter.reset(); print '|' + str(Counter.total);");
        String out = Bl0jv2_TestRunner.run(load(compile(lib), 2) + load(compile(app, lib), 0));
        assertEquals("42|7|102|0", out);
    }

    @Test
    void theLibraryRunsItsOwnCodeOnceAndItsStateIsSharedByEveryProgram() throws IOException {
        Path lib = write("lib.bl0", "def class Box { static field v; } Box.v = 0; print 'init;';");
        Path first = write("a.bl0", "import 'lib.bl0'; Box.v = Box.v + 10;");
        Path second = write("b.bl0", "import 'lib.bl0'; print 'seen ' + str(Box.v);");
        String out = Bl0jv2_TestRunner.run(
                load(compile(lib), 2) + load(compile(first, lib), 0) + load(compile(second, lib), 0) + load(compile(second, lib), 0));
        assertEquals("init;seen 10seen 10", out);
    }

    @Test
    void aProgramThatIsMuchSmallerThanItsLibrary() throws IOException {
        String body = "def big%d(x) { return x + %d; } ";
        StringBuilder lib = new StringBuilder();
        for (int i = 0; i < 200; i++) lib.append(String.format(body, i, i));
        Path libFile = write("lib.bl0", lib.toString());
        Path app = write("app.bl0", "import 'lib.bl0'; print big7(1);");
        assertTrue(compile(app, libFile).length < compile(libFile).length / 20, "the program carries no library code");
        assertEquals("8", Bl0jv2_TestRunner.run(load(compile(libFile), 2) + load(compile(app, libFile), 0)));
    }

    @Test
    void aLibraryMayBuildOnAnotherLibrary() throws IOException {
        Path base = write("base.bl0", "def inc(x) { return x + 1; } def class Cfg { static field name; } Cfg.name = 'cfg';");
        Path mid = write("mid.bl0", "import 'base.bl0'; def inc2(x) { return inc(inc(x)); }");
        Path app = write("app.bl0", "import 'mid.bl0'; print str(inc2(1)) + '|' + Cfg.name + '|' + str(inc(10));");
        String out = Bl0jv2_TestRunner.run(load(compile(base), 2) + load(compile(mid, base), 2) + load(compile(app, base, mid), 0));
        assertEquals("3|cfg|11", out);
    }

    @Test
    void callsToALibraryAreCheckedAtCompileTimeLikeLocalOnes() throws IOException {
        Path lib = write("lib.bl0", "def twice(x) { return x * 2; } def class P { field a; def get() { return this.a; } static def make() { return new P(); } }");
        Path wrongArity = write("a.bl0", "import 'lib.bl0'; twice(1, 2);");
        assertThrows(Bl0j_CompilerException.class, () -> compile(wrongArity, lib));
        Path unknownMember = write("b.bl0", "import 'lib.bl0'; p = new P(); p.nosuch();");
        assertThrows(Bl0j_CompilerException.class, () -> compile(unknownMember, lib));
        Path staticOk = write("c.bl0", "import 'lib.bl0'; p = P.make();");
        compile(staticOk, lib);
    }

    @Test
    void aProgramCannotDefineWhatALibraryAlreadyDefines() throws IOException {
        Path lib = write("lib.bl0", "def twice(x) { return x * 2; }");
        Path app = write("app.bl0", "import 'lib.bl0'; def twice(x) { return x; }");
        assertThrows(Bl0j_CompilerException.class, () -> compile(app, lib));
    }

    @Test
    void aProgramWhoseLibraryIsNotLoadedFailsAtLoadTime() throws IOException {
        Path lib = write("lib.bl0", "def twice(x) { return x * 2; }");
        Path app = write("app.bl0", "import 'lib.bl0'; print twice(2);");
        String out = Bl0jv2_TestRunner.run(place(compile(app, lib)) +
                "try { execMem(" + AT + ", " + compile(app, lib).length + ", 0); } catch (e) { print e; }");
        assertEquals("exec: cannot load 'memory at " + AT + "': unresolved external 'twice': no loaded library defines it", out);
    }

    @Test
    void twoLibrariesCannotExportTheSameName() throws IOException {
        Path one = write("one.bl0", "def clash() { return 1; }");
        Path two = write("two.bl0", "def clash() { return 2; }");
        String out = Bl0jv2_TestRunner.run(load(compile(one), 2) +
                place(compile(two)) + "try { execMem(" + AT + ", " + compile(two).length + ", 2); } catch (e) { print e; }");
        assertTrue(out.contains("it defines 'clash', which another shared library already exports"), out);
    }

    @Test
    void lambdasAreNotExportedAndUnloadingAProgramLeavesTheLibraryIntact() throws IOException {
        Path lib = write("lib.bl0", "def apply(f, x) { return f(x); } def class K { static field n; } K.n = 0;");
        Path app = write("app.bl0", "import 'lib.bl0'; K.n = K.n + apply((v) -> v * 2, 3);");
        byte[] appImage = compile(app, lib);
        byte[] reader = compile(write("reader.bl0", "import 'lib.bl0'; print K.n;"), lib);
        // 5000 runs: far more than the 16-bit constant pool could hold if a run stayed loaded, and
        // the library's own class and function are untouched by each unload
        String out = Bl0jv2_TestRunner.run(load(compile(lib), 2) + place(appImage) +
                "i = 0; while (i < 5000) { execMem(" + AT + ", " + appImage.length + ", 0); i += 1; } " +
                load(reader, 0));
        assertEquals("30000", out);
    }

    @Test
    void aSharedLibraryMayNotImportAnInlinedFile() throws IOException {
        Path inner = write("inner.bl0", "def inner() { return 1; }");
        Path lib = write("lib.bl0", "import 'inner.bl0'; def outer() { return inner(); }");
        Path app = write("app.bl0", "import 'lib.bl0';");
        assertThrows(Bl0j_CompilerException.class, () -> compile(app, lib));   // lib is shared, inner is not
    }
}
