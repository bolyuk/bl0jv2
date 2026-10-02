package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** dev/ and proc/: devices and processes as files */
class DevProcTest {

    private static void command(AeonSession s, String line, String expected) throws Exception {
        int before = s.output().length();
        s.type(line + "\r");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(expected))
            Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(expected),
                "after '" + line + "' expected '" + expected + "' in:\n" + s.output().substring(before)
                        + "\nfinished=" + s.finished + " failure=" + s.failure);
    }

    @Test
    void theDevicesAreFilesWithOwnersAndModes(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "ls dev", "console");
        command(s, "ls dev", "zero");
        command(s, "ls -l dev", "-rw-rw-rw- root     root");
        command(s, "ls -l dev", "--w--w--w- root");                   // dev/console
        command(s, "cat dev/time", "2026-10-01 12:00:");
        command(s, "cat dev/uptime", "$");
        command(s, "cat dev/cpu", "core 0: shell (pid 1)");
        command(s, "stat dev/null", "mode  rw-rw-rw- (666)");
        // null swallows and reads empty
        command(s, "echo gone > dev/null", "$");
        command(s, "cat dev/null", "$");
        command(s, "cat dev/null | wc", "0 lines");
        // random: 32 hex digits, different every time
        command(s, "cat dev/random | wc", "1 lines, 1 words, 33 bytes");
        command(s, "cat dev/random > /tmp/r1", "$");
        command(s, "cat dev/random > /tmp/r2", "$");
        command(s, "diff /tmp/r1 /tmp/r2", "1 removed, 1 added");
        command(s, "cat dev/zero | wc", "64 bytes");
        // the console: what is written appears on the terminal, even from a redirected command
        command(s, "echo to the terminal > dev/console", "to the terminal");
        // refused
        command(s, "echo x > dev/time", "cannot be written");
        command(s, "echo x > dev/nosuch", "no such device");
        command(s, "rm dev/null", "cannot be removed");
        command(s, "mv dev/null dev/other", "virtual");
        command(s, "chmod 777 dev/time", "fixed owner and mode");
        command(s, "tree dev", "7 files");
        command(s, "find uptime", "/dev/uptime");
    }

    @Test
    void permissionsApplyToDevicesLikeToAnyFile(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "useradd alice", "added alice");
        command(s, "su alice", "$");
        command(s, "cat dev/time", "2026-10-01");
        command(s, "echo hi > dev/console", "hi");                      // 0222: everybody may
        command(s, "cat dev/console", "permission denied");            // but nobody reads it
        command(s, "echo x > dev/null", "$");
        command(s, "cat dev/random | wc", "33 bytes");
        command(s, "exit", "$");
    }

    @Test
    void processesAreFolders(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "sleep 60 &", "[2] sleep");
        command(s, "ls proc", "uptime");
        command(s, "ls proc", "2/");
        command(s, "ls proc/2", "status");
        command(s, "cat proc/2/status", "name: sleep");
        command(s, "cat proc/2/status", "state: running");
        command(s, "cat proc/2/status", "user: root (0)");
        command(s, "cat proc/2/cmd", "bin/sleep.bl0c");
        command(s, "cat proc/uptime", "$");
        command(s, "cat proc/nosuch/status", "no such file");
        command(s, "ls -l proc/2", "--w-------");                      // ctl: 0200
        command(s, "echo nonsense > proc/2/ctl", "the only command is kill");
        command(s, "echo kill > proc/1/ctl", "is not a running process that can be stopped");      // the shell
        command(s, "echo kill > proc/2/ctl", "$");
        command(s, "ps", "killed");
        command(s, "cat proc/2/status", "state: killed");
        command(s, "cat proc/cpu", "no such file");
    }

    @Test
    void onlyTheOwnerOrRootMayWriteAProcessControlFile(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "useradd alice", "added alice");
        command(s, "sleep 60 &", "[2] sleep");                          // root's
        command(s, "su alice", "$");
        command(s, "sleep 60 &", "[3] sleep");                          // alice's
        command(s, "cat proc/2/status", "user: root");                  // anyone may look
        command(s, "echo kill > proc/2/ctl", "permission denied");     // not hers
        command(s, "cat proc/3/status", "user: alice (1000)");
        command(s, "echo kill > proc/3/ctl", "$");                      // hers
        command(s, "exit", "$");
        command(s, "echo kill > proc/2/ctl", "$");                      // root's own
        command(s, "ps", "killed");
    }
}
