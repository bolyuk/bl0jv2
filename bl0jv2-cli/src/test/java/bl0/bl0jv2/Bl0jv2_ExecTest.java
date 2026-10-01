package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import bl0.bl0jv2.exceptions.Bl0j_VM_Panic;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// execMem(addr, size, mode): loads a compiled program that sits in raw memory
// and runs it in THIS SAME Bl0jv2_jVM instance - same managed heap, raw memory,
// port space, interrupt table and cores as whatever called it. The VM never
// reads a host file: these tests put the program's bytes in memory the way a
// kernel would after reading them from a disk. Every address the loaded
// program's bytecode encodes is relocated by loadRelocated() to land past
// whatever this VM already had loaded.
class Bl0jv2_ExecTest {

    @TempDir
    Path tempDir;

    private static final int AT = 30000;

    // a compiled program, as the bl0 statements that place it in raw memory
    private static final java.util.Map<Path, byte[]> PROGRAMS = new java.util.HashMap<>();

    private Path writeCompiled(String name, String source) throws IOException {
        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        Path file = tempDir.resolve(name);
        PROGRAMS.put(file, bytecode);
        return file;
    }

    private static String place(Path program) {
        byte[] b = PROGRAMS.get(program);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) sb.append("poke8(").append(AT + i).append(", ").append(b[i] & 0xFF).append("); ");
        return sb.toString();
    }

    // statements that load the program into memory and run it in 'mode' (0 user, 1 kernel)
    private static String load(Path program, int mode) {
        byte[] b = PROGRAMS.get(program);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) sb.append("poke8(").append(AT + i).append(", ").append(b[i] & 0xFF).append("); ");
        return sb + "execMem(" + AT + ", " + b.length + ", " + mode + ")";
    }

    private static String load(Path program) {
        return load(program, 1);
    }

    @Test
    void execRunsAChildProgramAndItsOutputAppearsInline() throws IOException {
        Path child = writeCompiled("child.bl0c", "print 'from the child';");

        assertEquals("before|from the child|after", run(
                "print 'before|'; " +
                "" + load(child) + "; " +
                "print '|after';"));
    }

    @Test
    void execEvaluatesToZeroOnSuccess() throws IOException {
        Path child = writeCompiled("child.bl0c", "print 'hi';");

        assertEquals("hi|0", run(
                place(child) + "status = execMem(" + AT + ", " + PROGRAMS.get(child).length + ", 1); " +
                "print '|' + status;"));
    }

    @Test
    void execMemOfGarbageThrowsACatchableError() {
        assertEquals("exec: cannot load 'memory at 20000': Wrong magic number", run(
                "poke8(20000, 1); try { execMem(20000, 16, 1); } catch (e) { print e; }"));
    }

    @Test
    void execMemFromUserModeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("dropToUserMode(); execMem(20000, 16, 1);"));
    }

    @Test
    void thereIsNoWayToReadAHostFile() {
        // exec(path) is gone: a name that is not a builtin is just an undefined function
        assertThrows(RuntimeException.class, () -> run("exec('anything.bl0c');"));
    }

    @Test
    void aUserModeProgramRunsUnprivilegedAndTheCallerKeepsItsPrivilege() throws IOException {
        Path child = writeCompiled("user.bl0c", "print 'child privileged: ' + str(isPrivileged());");
        assertEquals("child privileged: false|caller privileged: true", run(
                load(child, 0) + "; print '|caller privileged: ' + str(isPrivileged());"));
    }

    @Test
    void aUserModeProgramLeavesNothingLoadedBehind() throws IOException {
        Path child = writeCompiled("quiet.bl0c", "def f() { return 7; } class_free = f(); s = 'text';");
        // 40000 constants would not fit a 16-bit pool if each run stayed loaded
        assertEquals("done", run(
                place(child) + "i = 0; while (i < 10000) { execMem(" + AT + ", " + PROGRAMS.get(child).length + ", 0); i += 1; } print 'done';"));
    }

    @Test
    void aKernelProgramStaysLoaded() throws IOException {
        // its function is still there for the caller to find afterwards: the program
        // stores nothing, so what survives is only observable as "no crash"; the
        // callerCodeStillWorksCorrectlyAfterExec test covers the contents
        Path child = writeCompiled("kernel.bl0c", "def g() { return 1; }");
        assertEquals("ok", run(load(child, 1) + "; print 'ok';"));
    }

    // ctx.privileged is one flag shared by the whole call stack on this
    // core, not scoped per-frame - so if a loaded program calls
    // dropToUserMode() (aeon-os's own shell.bl0 does exactly this) and
    // exec() didn't restore it afterward, the CALLER would be stuck in
    // user mode for everything it does next, even though it never
    // dropped privilege itself. Caught for real via aeon-os/boot.bl0's own
    // end-to-end run: its final panic() was being rejected as
    // unprivileged after exec()'ing aeon-os/shell.bl0
    @Test
    void execRestoresTheCallersPrivilegeLevelEvenIfTheLoadedProgramDroppedIt() throws IOException {
        Path child = writeCompiled("dropsPrivilege.bl0c", "dropToUserMode();");

        assertEquals("still privileged", run(
                "" + load(child) + "; " +
                "if (isPrivileged()) { print 'still privileged'; } else { print 'wrongly demoted'; }"));
    }

    // an ordinary (non-panic) error inside the loaded program still
    // propagates as a normal, catchable Bl0j_VM_Exception at the exec()
    // call site - the caller keeps running afterward
    @Test
    void anOrdinaryErrorInTheLoadedProgramIsCatchableByTheCaller() throws IOException {
        Path child = writeCompiled("buggy.bl0c", "x = 1 / 0;");

        assertEquals("survived", run(
                "try { " + load(child) + "; } catch (e) { } " +
                "print 'survived';"));
    }

    // panic() is different IN KIND, not just degree: since the loaded
    // program shares this VM's own 'panicked' flag now (there is no
    // second, independent instance for it to belong to - see
    // Bl0jv2_jVM's own exec() doc), a panic() anywhere inside it halts
    // the whole machine exactly like a direct panic() call would, and is
    // NOT caught here even by a try/catch wrapped directly around exec()
    @Test
    void aPanicInTheLoadedProgramHaltsTheWholeMachineUncaught() throws IOException {
        Path child = writeCompiled("crashy.bl0c", "panic('child fault');");

        Bl0j_VM_Panic ex = assertThrows(Bl0j_VM_Panic.class, () -> run(
                "try { " + load(child) + "; } catch (e) { print 'unreachable'; } " +
                "print 'also unreachable';"));
        assertTrue(ex.getMessage().contains("child fault"));
    }

    // proves the loaded program runs in this SAME raw memory, not a
    // separate arena: writing through the child, reading back from the
    // caller afterward, must see what the child actually wrote
    @Test
    void execSharesRawMemoryWithTheCaller() throws IOException {
        Path child = writeCompiled("pokes.bl0c", "poke32(0, 999);");

        assertEquals("999", run(
                "poke32(0, 111); " +
                "" + load(child) + "; " +
                "print peek32(0);"));
    }

    // proves the caller's OWN code, function addresses and constants are
    // unaffected by loading a second program alongside them - the
    // relocation has to be correct in both directions, not just "the
    // child doesn't crash"
    @Test
    void callerCodeStillWorksCorrectlyAfterExec() throws IOException {
        Path child = writeCompiled("child.bl0c", "def f() { return 999; } print f();");

        assertEquals("42|999|42", run(
                "def f() { return 42; } " +
                "print f() + '|'; " +
                "" + load(child) + "; " +
                "print '|' + f();"));
    }

    // relocation has to handle classes correctly too: fields, methods,
    // and field ASSIGNMENT (SET_FIELD packs its field-name const index
    // through a different, easy-to-miss encoding than a plain LOAD_CONST -
    // see loadRelocated's own doc) - and the caller and the loaded program
    // may perfectly legally define same-named classes with zero collision,
    // since each compiled file gets its own constant-pool entries
    @Test
    void execRelocatesClassesWithFieldsAndMethodsCorrectly() throws IOException {
        Path child = writeCompiled("child.bl0c",
                "def class Box { field v; def init(v) { this.v = v; } def bump() { this.v = this.v + 1; return this.v; } } " +
                "b = new Box(10); " +
                "print b.bump() + ',' + b.bump();");

        assertEquals("before|11,12|after", run(
                "def class Box { field v; def init(v) { this.v = v; } def bump() { this.v = this.v + 1; return this.v; } } " +
                "a = new Box(100); " +
                "print 'before|'; " +
                "" + load(child) + "; " +
                "print '|after';"));
    }

    // journalExec()/printJournal() (aeon-os/kernel.bl0) - a bl0jv2-level
    // wrapper around exec() that logs what it launched and how it ended.
    // exec() itself knows nothing about this; tested here as the same
    // pure logic kernel.bl0 defines, not by loading that file (keeps this
    // test self-contained and independent of aeon-os's own file layout).
    // Only ordinary errors get logged - see aPanicInTheLoadedProgramHalts...
    // above for why a real panic() can't be journaled at all
    private static final String JOURNAL =
            "def class ProcessJournal { static field entries; static field nextPid; } " +
            "ProcessJournal.entries = []; ProcessJournal.nextPid = 1; " +
            "def journalExec(name, addr, size) { " +
            "  pid = ProcessJournal.nextPid; ProcessJournal.nextPid = ProcessJournal.nextPid + 1; " +
            "  status = 'ok'; " +
            "  try { execMem(addr, size, 1); } catch (e) { status = 'crashed: ' + e; } " +
            "  push(ProcessJournal.entries, 'pid=' + pid + ' name=' + name + ' status=' + status); " +
            "  return status; " +
            "} " +
            "def printJournal() { " +
            "  i = 0; " +
            "  while (i < len(ProcessJournal.entries)) { print ProcessJournal.entries[i] + '|'; i = i + 1; } " +
            "} ";

    @Test
    void journalExecRecordsASuccessfulRun() throws IOException {
        Path child = writeCompiled("ok.bl0c", "print 'ran';");

        assertEquals("ranpid=1 name=greeter status=ok|", run(JOURNAL +
                place(child) + "journalExec('greeter', " + AT + ", " + PROGRAMS.get(child).length + "); " +
                "printJournal();"));
    }

    @Test
    void journalExecRecordsAnOrdinaryFailureWithoutPropagatingIt() throws IOException {
        Path child = writeCompiled("bad.bl0c", "x = 1 / 0;");

        String out = run(JOURNAL +
                place(child) + "journalExec('buggy', " + AT + ", " + PROGRAMS.get(child).length + "); " +
                "printJournal();");

        assertTrue(out.contains("pid=1 name=buggy status=crashed:"), "journal entry missing crash status: " + out);
        assertTrue(out.contains("division by zero"), "journal entry missing the underlying error: " + out);
    }

    @Test
    void journalExecReturnsTheStatusItRecorded() throws IOException {
        Path child = writeCompiled("ok.bl0c", "");

        assertEquals("ok", run(JOURNAL +
                place(child) + "print journalExec('x', " + AT + ", " + PROGRAMS.get(child).length + ");"));
    }

    private static String escaped(Path p) {
        // bl0jv2 string literals are single-quoted with (as far as this
        // suite has ever needed) no escape support - Windows paths can
        // contain backslashes, which forward slashes sidestep entirely
        // and Path/Files accept identically
        return p.toString().replace('\\', '/');
    }

    // programs loaded and unloaded by several cores at once: nobody loses a program, every run
    // completes, and the pool is not corrupted (each run adds to a shared counter)
    @Test
    void coresMayLoadAndRunProgramsAtTheSameTime() throws IOException {
        Path child = writeCompiled("count.bl0c", "def bump(n) { return n + 1; } atomicAdd(100, bump(0));");
        byte[] image = PROGRAMS.get(child);
        StringBuilder hex = new StringBuilder();
        for (byte b : image) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        String program =
                "def put(s, at) { i = 0; while (i < len(s)) { a = int(s[i]); b = int(s[i + 1]); " +
                "poke8(at + (i / 2), ((a < 58 ? a - 48 : a - 87) << 4) | (b < 58 ? b - 48 : b - 87)); i += 2; } } " +
                // every core has its own copy of the image to load from, and its own counter of finished runs
                "def work(core) { base = 20000 + core * 4000; put('" + hex + "', base); i = 0; " +
                "  while (i < 300) { execMem(base, " + image.length + ", 0); i += 1; } atomicAdd(200 + core * 4, 1); } " +
                "poke32(100, 0); poke32(204, 0); poke32(208, 0); poke32(212, 0); " +
                "dispatch(work, 1, 1); dispatch(work, 2, 2); dispatch(work, 3, 3); " +
                "while (peek32(204) + peek32(208) + peek32(212) < 3) { wait(5); } " +
                "print peek32(100);";
        assertEquals("900", Bl0jv2_TestRunner.run(program, vm -> vm.set_core_count(4)));
    }
}
