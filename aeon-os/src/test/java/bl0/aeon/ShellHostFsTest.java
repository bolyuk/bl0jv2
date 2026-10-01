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
    void filesMoveBetweenTheHostFolderAndTheDisk(@TempDir Path dir) throws Exception {
        Path share = Files.createDirectories(dir.resolve("share"));
        Files.writeString(share.resolve("in.txt"), "from the host\nline two\n");
        var s = AeonSession.shellOn(dir.resolve("d.img"), true, share);

        command(s, "hls", "in.txt");
        command(s, "hcat in.txt", "line two");
        command(s, "hget in.txt copy.txt", "23 bytes from host:in.txt to copy.txt");
        command(s, "cat copy.txt", "from the host");

        command(s, "echo made in aeon > out.txt", "$ ");
        command(s, "hput out.txt", "bytes from out.txt to host:out.txt");
        assertEquals("made in aeon\n", Files.readString(share.resolve("out.txt")));

        command(s, "hrm in.txt", "$ ");
        assertTrue(!Files.exists(share.resolve("in.txt")));
        command(s, "hcat in.txt", "no such file");
        command(s, "whoami", "user");
    }

    @Test
    void theHostRefusesToLeaveTheFolder(@TempDir Path dir) throws Exception {
        Path share = Files.createDirectories(dir.resolve("share"));
        Files.writeString(dir.resolve("secret.txt"), "top secret");
        var s = AeonSession.shellOn(dir.resolve("d.img"), true, share);
        command(s, "hcat ../secret.txt", "refused by the host");
        command(s, "hget ../secret.txt", "refused by the host");
        assertTrue(!s.output().contains("top secret"), s.output());
    }

    @Test
    void withoutTheBridgeTheCommandsExplain(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "hls", "no host folder attached");
    }
}
