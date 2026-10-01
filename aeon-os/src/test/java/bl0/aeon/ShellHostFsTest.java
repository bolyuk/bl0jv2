package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the shell's view of --bridge-fs: hls, hcat, hget, hput, hrm
class ShellHostFsTest {

    private static void command(AeonSession s, String line, String expected) throws Exception {
        int before = s.output().length();
        s.type(line + "\r");
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(expected))
            Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(expected),
                "after '" + line + "' expected '" + expected + "' in:\n" + s.output().substring(before));
    }

    @Test
    void theHostFolderIsJustADirectoryOfTheTree(@TempDir Path dir) throws Exception {
        Path share = Files.createDirectories(dir.resolve("share"));
        Files.writeString(share.resolve("in.txt"), "from the host\nline two\n");
        var s = AeonSession.shellOn(dir.resolve("d.img"), true, share);

        command(s, "ls", "<dir>  host/");
        command(s, "ls host", "in.txt");
        command(s, "cat host/in.txt", "line two");
        command(s, "grep two host/in.txt", "line two");
        command(s, "cp host/in.txt copy.txt", "$ ");
        command(s, "cat copy.txt", "from the host");

        command(s, "echo made in aeon > out.txt", "$ ");
        command(s, "cp out.txt host/out.txt", "$ ");
        assertEquals("made in aeon\n", Files.readString(share.resolve("out.txt")));
        command(s, "mkdir host/sub", "$ ");
        command(s, "mv out.txt host/sub/moved.txt", "$ ");
        assertEquals("made in aeon\n", Files.readString(share.resolve("sub/moved.txt")));
        command(s, "cd host/sub", "/host/sub $ ");
        command(s, "ls", "moved.txt");
        command(s, "cd /", "$ ");
        command(s, "echo more >> host/in.txt", "$ ");
        assertEquals("from the host\nline two\nmore\n", Files.readString(share.resolve("in.txt")));

        command(s, "rm host/in.txt", "$ ");
        assertTrue(!Files.exists(share.resolve("in.txt")));
        command(s, "cat host/in.txt", "no such file");
        command(s, "rm host/sub/moved.txt", "$ ");
        command(s, "rmdir host/sub", "$ ");
        assertTrue(!Files.exists(share.resolve("sub")));
        command(s, "whoami", "user");
    }

    @Test
    void theHostFolderCannotBeEscapedFromTheShell(@TempDir Path dir) throws Exception {
        Path share = Files.createDirectories(dir.resolve("share"));
        Files.writeString(dir.resolve("secret.txt"), "top secret");
        var s = AeonSession.shellOn(dir.resolve("d.img"), true, share);
        // the shell folds ".." away before anything reaches the host, so this names a DISK file
        command(s, "cat host/../secret.txt", "no such file");
        command(s, "cp host/../../secret.txt x", "no such file");
        assertTrue(!s.output().contains("top secret"), s.output());
    }

    @Test
    void withoutTheBridgeThereIsNoHostDirectory(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "ls host", "no such folder");
        command(s, "echo x > host", "$ "); // 'host' is then an ordinary disk name
        command(s, "cat host", "x");
    }
}
