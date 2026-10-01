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

// exec(path): loads a SEPARATE compiled (.bl0c) file and runs it in THIS
// SAME Bl0jv2_jVM instance - same managed heap, raw memory, port space,
// interrupt table and cores as whatever called it. An earlier version of
// this spun up a second Bl0jv2_jVM instance per call instead; that model
// is gone now (see Bl0jv2_jVM's own exec() doc for why: it doesn't match
// real hardware, and doesn't port to a future non-JVM backend). Every
// address the loaded program's own bytecode encodes gets relocated by
// loadRelocated() to land past whatever this VM already had loaded - see
// its own doc for exactly which operands need that.
class Bl0jv2_ExecTest {

    @TempDir
    Path tempDir;

    private Path writeCompiled(String name, String source) throws IOException {
        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        Path file = tempDir.resolve(name);
        Files.write(file, bytecode);
        return file;
    }

    @Test
    void execRunsAChildProgramAndItsOutputAppearsInline() throws IOException {
        Path child = writeCompiled("child.bl0c", "print 'from the child';");

        assertEquals("before|from the child|after", run(
                "print 'before|'; " +
                "exec('" + escaped(child) + "'); " +
                "print '|after';"));
    }

    @Test
    void execEvaluatesToZeroOnSuccess() throws IOException {
        Path child = writeCompiled("child.bl0c", "print 'hi';");

        assertEquals("hi|0", run(
                "status = exec('" + escaped(child) + "'); " +
                "print '|' + status;"));
    }

    @Test
    void execOfAMissingFileThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("exec('does/not/exist.bl0c');"));
    }

    @Test
    void execFromUserModeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "dropToUserMode(); exec('anything.bl0c');"));
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
                "exec('" + escaped(child) + "'); " +
                "if (isPrivileged()) { print 'still privileged'; } else { print 'wrongly demoted'; }"));
    }

    // an ordinary (non-panic) error inside the loaded program still
    // propagates as a normal, catchable Bl0j_VM_Exception at the exec()
    // call site - the caller keeps running afterward
    @Test
    void anOrdinaryErrorInTheLoadedProgramIsCatchableByTheCaller() throws IOException {
        Path child = writeCompiled("buggy.bl0c", "x = 1 / 0;");

        assertEquals("survived", run(
                "try { exec('" + escaped(child) + "'); } catch (e) { } " +
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
                "try { exec('" + escaped(child) + "'); } catch (e) { print 'unreachable'; } " +
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
                "exec('" + escaped(child) + "'); " +
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
                "exec('" + escaped(child) + "'); " +
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
                "exec('" + escaped(child) + "'); " +
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
            "def journalExec(name, path) { " +
            "  pid = ProcessJournal.nextPid; ProcessJournal.nextPid = ProcessJournal.nextPid + 1; " +
            "  status = 'ok'; " +
            "  try { exec(path); } catch (e) { status = 'crashed: ' + e; } " +
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
                "journalExec('greeter', '" + escaped(child) + "'); " +
                "printJournal();"));
    }

    @Test
    void journalExecRecordsAnOrdinaryFailureWithoutPropagatingIt() throws IOException {
        Path child = writeCompiled("bad.bl0c", "x = 1 / 0;");

        String out = run(JOURNAL +
                "journalExec('buggy', '" + escaped(child) + "'); " +
                "printJournal();");

        assertTrue(out.contains("pid=1 name=buggy status=crashed:"), "journal entry missing crash status: " + out);
        assertTrue(out.contains("division by zero"), "journal entry missing the underlying error: " + out);
    }

    @Test
    void journalExecReturnsTheStatusItRecorded() throws IOException {
        Path child = writeCompiled("ok.bl0c", "");

        assertEquals("ok", run(JOURNAL +
                "print journalExec('x', '" + escaped(child) + "');"));
    }

    private static String escaped(Path p) {
        // bl0jv2 string literals are single-quoted with (as far as this
        // suite has ever needed) no escape support - Windows paths can
        // contain backslashes, which forward slashes sidestep entirely
        // and Path/Files accept identically
        return p.toString().replace('\\', '/');
    }
}
