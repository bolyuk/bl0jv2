package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** net/: the network as files */
class NetFsTest {

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
    void theMachineAndItsDnsAreFiles(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "ls net", "tcp");
        command(s, "cat net/ip", "10.0.0.2");
        command(s, "cat net/dns/server", ".");
        command(s, "cat net/dns/10.1.2.3", "10.1.2.3");
        command(s, "echo x > net/ip", "cannot be written");
    }

    @Test
    void aTcpConnectionIsAFolderOfFiles(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "cat net/tcp/clone", "1");                          // the listener
        command(s, "cat net/tcp/clone", "2");                          // the client
        command(s, "echo listen 7000 > net/tcp/1/ctl", "$");
        command(s, "cat net/tcp/1/status", "LISTEN");
        command(s, "echo connect 10.0.0.2 7000 > net/tcp/2/ctl", "$");
        command(s, "echo accept > net/tcp/1/ctl", "$");
        command(s, "cat net/tcp/1/status", "ESTABLISHED");
        command(s, "echo hello over files > net/tcp/2/data", "$");
        command(s, "cat net/tcp/1/data", "hello over files");
        command(s, "echo and back > net/tcp/1/data", "$");
        command(s, "cat net/tcp/2/data", "and back");
        command(s, "echo close > net/tcp/2/ctl", "$");
        command(s, "ls -l net/tcp/2", "-rw-------");
    }

    @Test
    void udpDatagramsGoThroughFiles(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "cat net/udp/clone", "1");
        command(s, "cat net/udp/clone", "2");
        command(s, "echo bind 5300 > net/udp/1/ctl", "$");
        command(s, "echo connect 10.0.0.2 5300 > net/udp/2/ctl", "$");
        command(s, "echo ping > net/udp/2/data", "$");
        command(s, "cat net/udp/1/data", "ping");
        command(s, "cat net/udp/1/status", "BOUND");
    }

    @Test
    void aConnectionBelongsToItsMaker(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "useradd alice", "added alice");
        command(s, "cat net/udp/clone", "1");                          // root's
        command(s, "su alice", "$");
        command(s, "cat net/udp/1/status", "BOUND");                   // anyone may look at the status
        command(s, "echo close > net/udp/1/ctl", "permission denied");
        command(s, "cat net/udp/1/data", "permission denied");
        command(s, "cat net/udp/clone", "2");                          // her own
        command(s, "echo close > net/udp/2/ctl", "$");
    }
}
