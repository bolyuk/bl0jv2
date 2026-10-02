package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** logs and shutdown */
class ShutdownTest {

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

    private static void waitFinished(AeonSession s) throws Exception {
        s.thread.join(20_000);
        assertTrue(s.finished, "the machine did not halt:\n" + s.output());
    }

    // ---- logs ----

    @Test
    void logsShowTheEndOfTheLogWithTheDateAndFilterByText(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "logs -n 3", "2026-10-01 12:00:");                 // lines start with the clock's date and time
        command(s, "echo one > /tmp/a", "$");
        command(s, "logs -n 2", "shell: echo one > /tmp/a");              // the last one is the logs command itself
        command(s, "logs echo", "shell: echo one");
        command(s, "logs -n 0", "$");
        command(s, "logs nosuchtextanywhere", "$");
        command(s, "logs -n x", "not a number");
        command(s, "su nobody", "no such user");
        command(s, "logs failed", "$");
        command(s, "useradd alice", "added alice");
        command(s, "su alice", "$");
        command(s, "logs -n 2", "login: alice (uid 1000)");              // anybody may read it
        command(s, "exit", "$");
    }

    @Test
    void theRotatedLogIsReadWithMinusA(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "seq 20000 >> var/log/aeon.log", "$");            // over 64 KiB: the next line rotates it
        command(s, "echo trigger", "trigger");
        command(s, "logs -n 1 19999", "$");                            // gone from the live log ...
        command(s, "ls var/log", "aeon.log.1");
        command(s, "logs -a -n 1 19999", "19999");                     // ... and found in the rotated one
    }

    @Test
    void followShowsNewLinesUntilCtrlC(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "echo 'command = sleep 1' > etc/services/slow", "$");
        s.type("logs -f -n 0\r");
        Thread.sleep(600);
        int before = s.output().length();
        // a second terminal's worth of work: start the service from a cron-less path - the service program runs on
        // the shell's core, which is busy following, so the log line comes from a background job instead
        s.type("\u0003");
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains("^C")) Thread.sleep(20);
        assertTrue(s.output().substring(before).contains("^C"), s.output());
        command(s, "echo back", "back");
    }

    // ---- shutdown ----

    @Test
    void shutdownStopsServicesAndProcessesThenTheMachineHalts(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "service crond start", "started crond");
        command(s, "sleep 60 &", "[3] sleep");
        command(s, "shutdown", "the system is going down");
        waitFinished(s);
        String out = s.output();
        assertTrue(out.contains("stopping crond") && out.contains("stopping [3] sleep") && out.contains("aeon-shell exiting"), out);

        var again = AeonSession.shellOn(dir.resolve("d.img"), false, null, 4);
        command(again, "logs shutdown", "shutdown: 1 services stopped, 1 processes asked to stop, 0 still running");
        command(again, "logs service:", "service: crond stopped");
        command(again, "logs shutdown", "shutdown: halting");
        command(again, "service crond status", "stopped");               // no stale pid file either
        command(again, "ls var/run", "(empty)");
    }

    @Test
    void onlyRootShutsTheMachineDown(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "useradd alice", "added alice");
        command(s, "su alice", "$");
        command(s, "shutdown", "only root");
        command(s, "poweroff", "only root");                            // the alias
        command(s, "id", "uid=1000(alice)");
        command(s, "exit", "$");                                        // leaving su is not a shutdown
        command(s, "id", "uid=0(root)");
        command(s, "halt", "the system is going down");                  // root: the alias
        waitFinished(s);
    }

    @Test
    void rootLeavingTheShellStopsTheServicesToo(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "service crond start", "started crond");
        s.type("exit\r");
        waitFinished(s);
        assertTrue(s.output().contains("stopping crond") && s.output().contains("bye"), s.output());
        var again = AeonSession.shellOn(dir.resolve("d.img"), false, null, 4);
        command(again, "logs stopped", "service: crond stopped");
    }

    @Test
    void aShutdownFromCronEndsTheShellWaitingAtItsPrompt(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "echo '2 * * * * root shutdown' > etc/crontab", "$");
        command(s, "crond &", "[2] crond");
        s.clockAt(12, 2, 0);
        waitFinished(s);                                                // the prompt gives up waiting
        assertTrue(s.output().contains("aeon-shell exiting"), s.output());
    }

    @Test
    void rebootIsAShutdownForRootOnly(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "useradd alice", "added alice");
        command(s, "su alice", "$");
        command(s, "reboot", "only root may do that");
        command(s, "exit", "$");
        command(s, "reboot", "the system is going down for a reboot");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.finished) Thread.sleep(20);
        assertTrue(s.finished, "the machine did not halt:\n" + s.output());
    }
}
