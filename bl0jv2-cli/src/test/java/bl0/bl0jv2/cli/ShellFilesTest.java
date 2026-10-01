package bl0.bl0jv2.cli;

import bl0.bl0jv2.AeonSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the aeon-os shell's file commands over a real host image file (--disk)
class ShellFilesTest {

    private static AeonSession shell(Path image) throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("shell.bl0"), 1, vm -> {
            try {
                vm.attach_disk(new FileDisk(image, 256));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(s.waitFor("aeon-shell ready", 15_000), s.output());
        return s;
    }

    private static void command(AeonSession s, String line, String expected) throws Exception {
        int before = s.output().length();
        s.type(line + "\r");
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(expected))
            Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(expected),
                "after '" + line + "' expected '" + expected + "' in:\n" + s.output().substring(before) + "\nfinished=" + s.finished + " failure=" + s.failure);
    }

    @Test
    void anUnformattedDiskAsksForAFormat(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("d.img"));
        command(s, "ls", "no filesystem");
        command(s, "format", "run \"format yes\"");
        command(s, "format yes", "formatted 256 sectors");
        command(s, "ls", "(no files)");
    }

    @Test
    void filesCanBeWrittenReadCopiedRenamedAndRemoved(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("d.img"));
        command(s, "format yes", "formatted");
        command(s, "write notes.txt hello  from the shell", "wrote 20 bytes to notes.txt");
        command(s, "cat notes.txt", "hello from the shell");
        command(s, "append notes.txt !", "notes.txt is now 21 bytes");
        command(s, "cat notes.txt", "hello from the shell!");
        command(s, "cp notes.txt docs/copy.txt", "$ ");
        command(s, "mv notes.txt old.txt", "$ ");
        command(s, "ls", "old.txt");
        assertTrue(s.output().contains("docs/copy.txt"), s.output());
        command(s, "ls docs/", "docs/copy.txt");
        command(s, "cat notes.txt", "notes.txt: no such file");
        command(s, "rm old.txt", "$ ");
        command(s, "rm old.txt", "old.txt: no such file");
        command(s, "df", "1 files");
    }

    @Test
    void errorsAreReportedAndTheShellCarriesOn(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("d.img"));
        command(s, "format yes", "formatted");
        command(s, "write 0123456789012345678901234567890123456789012345678 x", "fs: a file name must be 1 to 47 bytes");
        command(s, "mv a b", "a: no such file");
        command(s, "cat", "usage: cat <file>");
        command(s, "whoami", "user");
    }

    @Test
    void filesSurviveARestartBecauseTheyLiveInTheImage(@TempDir Path dir) throws Exception {
        Path image = dir.resolve("d.img");
        var first = shell(image);
        command(first, "format yes", "formatted");
        command(first, "write keep.txt still here", "wrote");

        var second = shell(image);
        command(second, "cat keep.txt", "still here");
        assertFalse(second.output().contains("no filesystem"), second.output());
    }

    @Test
    void withoutADiskTheCommandsSaySo() throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("shell.bl0"), 1);
        assertTrue(s.waitFor("aeon-shell ready", 15_000), s.output());
        command(s, "ls", "no disk attached");
        command(s, "format yes", "no disk attached");
    }
}
