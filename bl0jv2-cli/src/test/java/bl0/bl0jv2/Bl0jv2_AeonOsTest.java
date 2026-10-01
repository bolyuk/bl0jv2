package bl0.bl0jv2;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the aeon-os programs, run for real: compiled from source, executed, output
// checked. Until now nothing ran them, so a language change could break them
// unnoticed (arity checks did, in smp_boot: its dispatched tasks took no
// argument although dispatch() passes one).
class Bl0jv2_AeonOsTest {

    @Test
    void childHelloRunsToCompletion() throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("child_hello.bl0"), 1);
        assertTrue(s.waitFor("wrote and read back: 12648430", 10_000), s.output());
    }

    @Test
    void childCrashFailsWithItsDivisionByZero() throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("child_crash.bl0"), 1);
        assertTrue(s.waitFor("about to fail", 10_000), s.output());
        s.thread.join(5000);
        assertTrue(s.failure != null && s.failure.getMessage().contains("division by zero"), String.valueOf(s.failure));
    }

    @Test
    void bootRunsTheSchedulerIsolatesTheFaultAndLaunchesChildren() throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("boot.bl0"), 1);
        assertTrue(s.waitFor("aeon-shell ready", 20_000), s.output());
        String out = s.output();
        assertTrue(out.contains("[network] received packet: c0ffee"), out);
        assertTrue(out.contains("[fault] task faulty failed: division by zero"), out);
        assertTrue(out.contains("faults isolated by the scheduler: 1"), out);
        assertTrue(out.contains("pid 1 child_hello: ok"), out);
        assertTrue(out.contains("pid 2 child_crash: crashed"), out);
        // the shell answers on the keyboard, then exits
        s.type("help\r");
        assertTrue(s.waitFor("available commands: help", 10_000), s.output());
        s.type("exit\r");
        s.thread.join(10_000);
        assertTrue(s.finished, s.output());
    }

    @Test
    void smpBootDispatchesEverySixTaskAcrossThreeWorkerCores() throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("smp_boot.bl0"), 4);
        assertTrue(s.waitFor("all 6 tasks completed across 3 worker cores", 20_000), s.output());
        String out = s.output();
        assertTrue(out.contains("[rogue] blocked: privileged instruction 'out' requires kernel mode"), out);
        assertTrue(out.contains("[network] running on core"), out);
        assertTrue(out.contains("[console] driver wrote 0xee to port 0"), out);
        assertEquals(3, out.split("\\[compute\\] running on core").length - 1, out);
        assertTrue(s.waitFor("aeon-shell ready", 10_000), out);
        s.type("exit\r");
        s.thread.join(10_000);
        assertTrue(s.finished, s.output());
    }

    @Test
    void theShellReactsToTypingWithoutPolling() throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("shell.bl0"), 1);
        assertTrue(s.waitFor("aeon-shell ready", 10_000), s.output());
        Thread.sleep(300);                     // idle at the prompt: asleep in kbWait()
        s.type("echo hi there\r");
        assertTrue(s.waitFor("hi there", 5_000), s.output());
        s.type("exit\r");
        s.thread.join(10_000);
        assertTrue(s.finished && s.output().contains("aeon-shell exiting after 2 commands"), s.output());
    }

    // ---- the shell's network commands, loopback only (no bridge attached) ----

    private static AeonSession shell() throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("shell.bl0"), 1);
        assertTrue(s.waitFor("aeon-shell ready", 10_000), s.output());
        return s;
    }

    private static void command(AeonSession s, String line, String expectedOutput) throws Exception {
        s.type(line + "\r");
        assertTrue(s.waitFor(expectedOutput, 15_000), "after '" + line + "' expected '" + expectedOutput + "' in:\n" + s.output());
    }

    @Test
    void netShowsTheAddressAndThatThereIsNoBridge() throws Exception {
        var s = shell();
        command(s, "net", "bridge   none (loopback only)");
        assertTrue(s.output().contains("address  10.0.0.2"), s.output());
        assertTrue(s.output().contains("dns      8.8.8.8"), s.output());
    }

    @Test
    void pingOurOwnAddressAnswersEveryRequest() throws Exception {
        var s = shell();
        command(s, "ping 10.0.0.2", "4 sent, 4 received");
        assertTrue(s.output().contains("reply from 10.0.0.2: seq=1"), s.output());
    }

    @Test
    void pingTheLoopbackRangeAnswers() throws Exception {
        var s = shell();
        command(s, "ping 127.0.0.1", "4 sent, 4 received");
    }

    @Test
    void pingAnotherAddressTimesOutWithoutABridge() throws Exception {
        var s = shell();
        command(s, "ping 10.0.0.99", "4 sent, 0 received");
        assertTrue(s.output().contains("seq=1: request timed out"), s.output());
    }

    @Test
    void setipChangesTheAddressTheMachineAnswersFor() throws Exception {
        var s = shell();
        command(s, "setip 10.0.0.9", "address is now 10.0.0.9");
        command(s, "ping 10.0.0.9", "4 sent, 4 received");
        command(s, "ping 10.0.0.2", "4 sent, 0 received"); // no longer ours
    }

    @Test
    void badArgumentsGetUsageMessagesNotCrashes() throws Exception {
        var s = shell();
        command(s, "setip 300.1.1.1", "300.1.1.1: not an address");
        command(s, "setdns abc", "abc: not an address");
        command(s, "ping", "usage: ping <host>");
        command(s, "http", "usage: http <host[:port]> [path]");
        command(s, "tcp 10.0.0.2", "usage: tcp <host> <port> <text>");
        command(s, "tcp 10.0.0.2 99999 hi", "bad port");
        command(s, "udp 10.0.0.2 x hi", "bad port");
        command(s, "httpd", "usage: httpd <port>");
        // and the shell is still alive
        command(s, "echo still here", "still here");
    }

    @Test
    void netstatListsNoConnectionsAtFirst() throws Exception {
        var s = shell();
        command(s, "netstat", "(no connections)");
    }

    @Test
    void anUnreachableTcpPeerTimesOutAndLeavesTheShellUsable() throws Exception {
        var s = shell();
        command(s, "tcp 10.0.0.99 80 hi", "connection to 10.0.0.99:80 timed out");
        command(s, "netstat", "CLOSED");
        command(s, "echo ok", "ok");
    }

    @Test
    void helpListsTheNetworkCommands() throws Exception {
        var s = shell();
        command(s, "help", "httpd <port>");
        assertTrue(s.output().contains("ping <host>"), s.output());
    }
}
