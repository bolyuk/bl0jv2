package bl0.aeon;

import bl0.bl0jv2.cli.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the aeon-os shell's file commands over a real host image file (--disk)
class ShellFilesTest {

    // a shell on a copy of the OS disk: bin/ holds the command programs
    private static AeonSession shell(Path image) throws Exception {
        return AeonSession.shellOnOsDisk(image.getParent());
    }

    // a shell on a disk that holds no filesystem at all
    private static AeonSession shellOnBlankDisk(Path dir) throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("shell.bl0"), 1, vm -> {
            try {
                vm.attach_disk(AeonImage.blank(dir.resolve("blank.img"), 256));
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
    void aBlankDiskAsksForAFormatAndFormattingMakesItUsable(@TempDir Path dir) throws Exception {
        var s = shellOnBlankDisk(dir);
        command(s, "cd x", "the disk has no filesystem");
        command(s, "ls", "ls: command not found");
        command(s, "format", "run \"format yes\"");
        command(s, "format yes", "formatted 256 sectors");
        command(s, "echo hi > a.txt", "$ ");
        command(s, "cd /", "$ ");
    }

    @Test
    void filesCanBeWrittenReadCopiedRenamedAndRemoved(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("d.img"));
        command(s, "write notes.txt hello  from the shell", "wrote 20 bytes to notes.txt");
        command(s, "cat notes.txt", "hello from the shell");
        command(s, "append notes.txt !", "notes.txt is now 21 bytes");
        command(s, "cat notes.txt", "hello from the shell!");
        command(s, "cp notes.txt docs/copy.txt", "$ ");
        command(s, "mv notes.txt old.txt", "$ ");
        command(s, "ls", "old.txt");
        assertTrue(s.output().contains("<dir>  docs/"), s.output());
        command(s, "ls docs", "copy.txt");
        command(s, "cat notes.txt", "notes.txt: no such file");
        command(s, "rm old.txt", "$ ");
        command(s, "rm old.txt", "old.txt: no such file");
        command(s, "df", "files,");
    }

    @Test
    void foldersCanBeMadeEnteredAndRemovedAndPathsAreRelative(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("d.img"));
        command(s, "mkdir a", "$ ");
        command(s, "mkdir a/b", "$ ");
        command(s, "cd a/b", "/a/b $ ");
        command(s, "pwd", "/a/b");
        command(s, "write f.txt deep", "wrote 4 bytes");
        command(s, "cd ..", "/a $ ");
        command(s, "ls", "<dir>  b/");
        command(s, "cat b/f.txt", "deep");
        command(s, "cat /a/b/f.txt", "deep");
        command(s, "rmdir b", "fs: a/b is not empty");
        command(s, "cd /", "$ ");
        command(s, "mkdir a", "fs: a already exists");
        command(s, "mkdir x/y", "fs: x: no such directory");
        command(s, "cd nowhere", "nowhere: no such folder");
        command(s, "rm a/b/f.txt", "$ ");
        command(s, "rmdir a/b", "$ ");
        command(s, "rmdir a", "$ ");
        command(s, "cd a", "a: no such folder");
    }

    @Test
    void cpAndMvIntoAFolderKeepTheName(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("d.img"));
        command(s, "mkdir box", "$ ");
        command(s, "write one.txt 1", "wrote");
        command(s, "cp one.txt box", "$ ");
        command(s, "mv one.txt box/two.txt", "$ ");
        command(s, "ls box", "two.txt");
        assertTrue(s.output().contains("one.txt"), s.output());
    }

    @Test
    void dataCommandsWorkOnLinesAndBytes(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("d.img"));
        command(s, "write t.txt \"one\\ntwo apple\\nthree\"", "wrote 19 bytes");
        command(s, "cat t.txt", "three");
        command(s, "wc t.txt", "3 lines, 4 words, 19 bytes");
        command(s, "head -n 1 t.txt", "one");
        command(s, "grep apple t.txt", "two apple");
        command(s, "grep zzz t.txt", "(no match)");
        command(s, "echo hello > e.txt", "$ ");
        command(s, "echo world >> e.txt", "$ ");
        command(s, "wc e.txt", "2 lines, 2 words, 12 bytes");
        command(s, "hexdump e.txt", "0000  68 65 6c 6c 6f 0a 77 6f 72 6c 64 0a");
        command(s, "stat e.txt", "file, 12 bytes, 1 sectors");
        command(s, "touch empty", "$ ");
        command(s, "stat empty", "file, 0 bytes, 0 sectors");
    }

    // a program compiled on the host and put on a copy of the OS disk as bin/<name>.bl0c
    private static AeonSession shellWith(Path dir, String name, String source) throws Exception {
        Path src = dir.resolve(name + ".bl0");
        java.nio.file.Files.writeString(src, source);
        var s = new AeonSession();
        s.start(AeonSession.compile("init.bl0"), 1, vm -> {
            try {
                var disk = AeonImage.os(dir.resolve("d.img"));
                DiskImport.put(disk, java.util.List.of(DiskImport.Spec.parse(src + ":bin/" + name + ".bl0c")), java.util.List.of(Path.of("aeon-os/bin")), AeonImage.shared());
                vm.attach_disk(disk);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(s.waitFor("aeon-shell ready", 20_000), s.output() + " failure=" + s.failure);
        return s;
    }

    @Test
    void aProgramOnTheDiskRunsByNameWithoutPrivilege(@TempDir Path dir) throws Exception {
        var s = shellWith(dir, "hello", "println 'hello from the disk, privileged: ' + str(isPrivileged());");
        command(s, "ls bin", "hello.bl0c");
        command(s, "hello", "hello from the disk, privileged: false");
        command(s, "exec bin/hello.bl0c", "hello from the disk, privileged: false");
        command(s, "whoami", "user"); // the shell itself is still in user mode, and alive
    }

    @Test
    void aProgramSeesItsArgumentsAndTheCurrentFolder(@TempDir Path dir) throws Exception {
        var s = shellWith(dir, "where", "import '../lib/userland.bl0'; startProgram(); say('args=' + str(progWords()) + ' cwd=[' + progCwd() + ']');");
        command(s, "mkdir work", "$ ");
        command(s, "cd work", "/work $ ");
        command(s, "where one  \"two  words\"", "args=[where, one, two  words] cwd=[work]");
    }

    @Test
    void aProgramThatFailsReportsAndTheShellCarriesOn(@TempDir Path dir) throws Exception {
        var s = shellWith(dir, "bad", "println 'about to fail'; throw('boom');");
        command(s, "bad", "boom");
        command(s, "whoami", "user");
        command(s, "nosuchthing", "nosuchthing: command not found");
        command(s, "write notaprogram hello", "wrote");
        command(s, "exec notaprogram", "notaprogram: ");
        command(s, "whoami", "user");
    }

    @Test
    void repeatedRunsDoNotUseUpTheVm(@TempDir Path dir) throws Exception {
        var s = shellWith(dir, "tick", "print '.';");
        for (int i = 0; i < 40; i++) command(s, "ls", "$ ");
        command(s, "tick", ".");
        command(s, "whoami", "user");
    }

    @Test
    void theOsWritesItsLogToTheDisk(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("x"));
        command(s, "whoami", "user");
        command(s, "ls /", "var/");
        command(s, "cat var/log/aeon.log", "shell: started");
        assertTrue(s.output().contains("shell: whoami"), s.output());
        command(s, "tail -n 1 var/log/aeon.log", "shell: tail -n 1 var/log/aeon.log");
    }

    @Test
    void errorsAreReportedAndTheShellCarriesOn(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("d.img"));
        command(s, "write 0123456789012345678901234567890123456789012345678 x", "fs: a file name must be 1 to 47 bytes");
        command(s, "mv a b", "a: no such file");
        command(s, "mv one", "usage: mv <from> <to>");
        command(s, "whoami", "user");
    }

    @Test
    void filesSurviveARestartBecauseTheyLiveInTheImage(@TempDir Path dir) throws Exception {
        Path image = dir.resolve("d.img");
        var first = shell(image);
        command(first, "write keep.txt still here", "wrote");

        var second = AeonSession.shellOn(image, false);
        command(second, "cat keep.txt", "still here");
        assertFalse(second.output().contains("no filesystem"), second.output());
    }

    @Test
    void withoutADiskTheCommandsSaySo() throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("shell.bl0"), 1);
        assertTrue(s.waitFor("aeon-shell ready", 15_000), s.output());
        command(s, "cd x", "no disk attached");
        command(s, "format yes", "no disk attached");
        command(s, "ls", "ls: command not found");
    }

    @Test
    void variablesAndScripts(@TempDir Path dir) throws Exception {
        var s = shell(dir.resolve("d.img"));
        command(s, "name=world", "$ ");
        command(s, "echo hello $name", "hello world");
        command(s, "echo '$name'", "$name");
        command(s, "set", "name=world");
        command(s, "unset name", "$ ");
        command(s, "echo [$name]", "[]");
        command(s, "echo '# a comment' > s.sh", "$ ");
        command(s, "echo 'echo [$0] [$1] [$2]' >> s.sh", "$ ");
        command(s, "echo 'x=set-in-script' >> s.sh", "$ ");
        command(s, "echo 'echo $x' >> s.sh", "$ ");
        command(s, "sh s.sh a b", "[s.sh] [a] [b]");
        command(s, "echo [$1]", "[]");                           // the arguments are gone after the script
        command(s, "sh nosuch.sh", "no such file");
    }
}
