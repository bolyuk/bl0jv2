package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** etc/rc and the service program */
class ServiceTest {

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
    void aServiceIsStartedStoppedAndReportedOn(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "service list", "crond");
        command(s, "service list", "stopped");
        command(s, "service crond status", "crond: stopped, not enabled at boot");
        command(s, "service crond start", "started crond (pid 2)");
        command(s, "service crond start", "already running");
        command(s, "service crond status", "running (pid 2, core 1)");
        command(s, "ps", "running     1");                           // on a worker core
        command(s, "cat var/run/crond.pid", "2");
        command(s, "service crond stop", "stopped crond");
        command(s, "service crond status", "crond: stopped");
        command(s, "ls var/run", "(empty)");
        command(s, "service crond stop", "is not running");
        command(s, "service crond restart", "started crond (pid 3)");
        command(s, "cat var/log/aeon.log", "service: crond started (pid 3)");
        command(s, "service crond stop", "stopped crond");
        command(s, "service nosuch start", "no such service");
        command(s, "service crond frobnicate", "not an action");
        command(s, "service", "usage:");
    }

    @Test
    void anEnabledServiceStartsFromEtcRcAtBoot(@TempDir Path dir) throws Exception {
        var first = AeonSession.shellOnOsDisk(dir, 4);
        command(first, "service crond enable", "crond will start at boot");
        command(first, "cat etc/services.enabled", "crond");
        command(first, "service crond status", "enabled at boot");
        first.type("exit\r");
        first.thread.join(10_000);

        var second = AeonSession.shellOn(dir.resolve("d.img"), false, null, 4);
        assertTrue(second.waitFor("started crond", 15_000), second.output());      // rc ran before the prompt
        command(second, "service crond status", "running");
        command(second, "ps", "crond");
        command(second, "service crond disable", "will not start at boot");
        command(second, "service crond stop", "stopped crond");
        command(second, "cat etc/services.enabled", "$");
        second.type("exit\r");
        second.thread.join(10_000);

        var third = AeonSession.shellOn(dir.resolve("d.img"), false, null, 4);
        command(third, "echo marker", "marker");                     // rc has long run by now
        assertTrue(!third.output().contains("started crond"), third.output());
    }

    @Test
    void aStalePidFileIsNotARunningService(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "echo 99 > var/run/crond.pid", "$");
        command(s, "service crond status", "stopped");
        command(s, "service crond start", "started crond");        // the stale file is overwritten
        command(s, "service crond stop", "stopped crond");
    }

    @Test
    void onlyRootChangesServicesAndOnlyTheOwnerKillsAProcess(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "useradd alice", "added alice");
        command(s, "service crond start", "started crond (pid 2)");
        command(s, "su alice", "$");
        command(s, "service crond status", "running");
        command(s, "service list", "crond");
        command(s, "service crond stop", "only root");
        command(s, "service crond enable", "only root");
        command(s, "service boot", "only root");
        command(s, "kill 2", "permission denied");
        command(s, "sleep 30 &", "[3] sleep");
        command(s, "kill 3", "$");                                  // her own process
        command(s, "exit", "$");
        command(s, "kill 2", "$");                                  // root may
        command(s, "ps", "killed");
    }

    @Test
    void aServiceRunsForItsUserAndLogsWhereItIsToldTo(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "useradd alice", "added alice");
        command(s, "echo 'description = Says hello' > etc/services/hello", "$");
        command(s, "echo 'command = echo hello from the service' >> etc/services/hello", "$");
        command(s, "echo 'user = alice' >> etc/services/hello", "$");
        command(s, "echo 'log = home/alice/hello.log' >> etc/services/hello", "$");
        command(s, "service hello start", "started hello");
        command(s, "service list", "Says hello");
        long deadline = System.currentTimeMillis() + 10_000;
        String shown = "";
        while (System.currentTimeMillis() < deadline && !shown.contains("hello from the service")) {
            int before = s.output().length();
            s.type("cat home/alice/hello.log\r");
            Thread.sleep(400);
            shown = s.output().substring(before);
        }
        assertTrue(shown.contains("hello from the service"), shown);
        command(s, "ls -l home/alice", "alice    alice");          // written by her, not by root
        command(s, "service hello status", "stopped");               // it ran and ended
        command(s, "echo 'command = nosuchprogram' > etc/services/broken", "$");
        command(s, "service broken start", "command not found");
        command(s, "echo 'description = nothing' > etc/services/empty", "$");
        command(s, "service empty start", "no command");
    }
}
