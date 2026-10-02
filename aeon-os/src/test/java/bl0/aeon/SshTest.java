package bl0.aeon;

import bl0.bl0jv2.cli.TcpBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sshd (bin/sshd.bl0, stdlib/net/ssh.bl0) against a client written from the RFCs with the JDK's crypto */
class SshTest {

    private static void command(AeonSession s, String line, String expected) throws Exception {
        int before = s.output().length();
        s.type(line + "\r");
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(expected))
            Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(expected),
                "after '" + line + "' expected '" + expected + "' in:\n" + s.output().substring(before));
    }

    private static void secret(AeonSession s, String text, String then) throws Exception {
        Thread.sleep(300);
        int mark = s.output().length();
        s.type(text + "\r");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(mark).contains(then)) Thread.sleep(20);
        assertTrue(s.output().substring(mark).contains(then), "expected '" + then + "' in:\n" + s.output().substring(mark));
    }

    /** a machine with sshd listening, and the host port the bridge makes of it */
    private static int hostPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static AeonSession machine(Path dir, int hostPort) throws Exception {
        var s = AeonSession.shellOn(dir.resolve("d.img"), true, null, 4, java.util.List.of(), vm -> {
            try {
                new TcpBridge(vm, hostPort, 0x0A000002, 22, 0x0A000001).start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        command(s, "useradd alice", "added alice");
        command(s, "passwd alice", "New password");
        secret(s, "wonderland", "Retype");
        secret(s, "wonderland", "password changed for alice");
        return s;
    }

    @Test
    void aClientLogsInWithAPasswordAndRunsACommand(@TempDir Path dir) throws Exception {
        int port = hostPort();
        var s = machine(dir, port);
        command(s, "sshd &", "[2] sshd");
        assertTrue(s.waitFor("listening on 10.0.0.2:22", 30_000), s.output());
        Thread.sleep(500);
        try (var c = new SshTestClient(port)) {
            c.handshake();
            assertEquals("publickey,password", c.methods());
            assertTrue(c.loginWithPassword("alice", "wonderland"), s.output());
            String out = c.exec("echo hello over ssh; whoami; pwd");
            assertTrue(out.contains("hello over ssh") && out.contains("alice") && out.contains("/home/alice"), out + "\n" + s.output());
            assertEquals(Integer.valueOf(0), c.exitStatus);
        }
        try (var c = new SshTestClient(port)) {
            c.handshake();
            assertTrue(c.loginWithPassword("alice", "wonderland"));
            String refused = c.exec("edit notes.txt; echo status $?");
            assertTrue(refused.contains("needs a terminal") && refused.contains("status 1"), refused);
        }
    }

    @Test
    void anInteractiveShellWithEchoAndAFolderThatStays(@TempDir Path dir) throws Exception {
        int port = hostPort();
        var s = machine(dir, port);
        command(s, "sshd &", "[2] sshd");
        assertTrue(s.waitFor("listening on 10.0.0.2:22", 30_000), s.output());
        Thread.sleep(500);
        try (var c = new SshTestClient(port)) {
            long t0 = System.nanoTime();
            c.handshake();
            long t1 = System.nanoTime();
            assertTrue(c.loginWithPassword("alice", "wonderland"));
            long t2 = System.nanoTime();
            c.openShell(true);
            c.readUntil("alice /home/alice $ ");
            long t3 = System.nanoTime();
            System.out.println("TIMES handshake " + (t1 - t0) / 1_000_000 + " ms, login " + (t2 - t1) / 1_000_000 + " ms, shell " + (t3 - t2) / 1_000_000 + " ms");
            c.send("echo hi there\r");
            String seen;
            long t4 = System.nanoTime();
            try {
                seen = c.readUntil("hi there\r\nalice /home/alice $ ");
            } catch (IOException e) {
                s.type("tail -n 12 /var/log/aeon.log\r");
                Thread.sleep(1500);
                throw new AssertionError(e + "\n" + s.output(), e);
            }
            System.out.println("TIMES one command " + (System.nanoTime() - t4) / 1_000_000 + " ms");
            assertTrue(seen.contains("echo hi there\r\n"), seen);          // what was typed was echoed
            c.send("cd /tmp\r");
            c.readUntil("alice /tmp $ ");
            c.send("pwd\rexit\r");
            seen = c.readUntil("/tmp\r\n");
            c.send("");
            while (!c.channelClosed) c.readUntil("never appears");
            assertTrue(c.exitStatus != null && c.exitStatus == 0, "status " + c.exitStatus);
        }
        Thread.sleep(1500);
        command(s, "tail -n 8 /var/log/aeon.log", "ended: closed");
        command(s, "cat /var/log/aeon.log", "ssh: alice: echo hi there");       // what ran for the user is in the log
    }

    @Test
    void badPasswordsAndEmptyPasswordsAreRefusedAndAKeyLogsIn(@TempDir Path dir) throws Exception {
        int port = hostPort();
        var s = machine(dir, port);
        command(s, "useradd bob", "added bob");                          // no password
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) seed[i] = (byte) (i * 5 + 1);
        String blob = java.util.Base64.getEncoder().encodeToString(SshTestClient.keyBlob(SshTestClient.publicKeyOf(seed)));
        command(s, "mkdir /home/alice/.ssh", "$");
        command(s, "echo 'ssh-ed25519 " + blob + " test' > /home/alice/.ssh/authorized_keys", "$");
        command(s, "sshd &", "[2] sshd");
        assertTrue(s.waitFor("listening on 10.0.0.2:22", 30_000), s.output());
        Thread.sleep(500);
        byte[] firstHostKey;
        try (var c = new SshTestClient(port)) {
            c.handshake();
            firstHostKey = c.hostKey;
            assertTrue(!c.loginWithPassword("alice", "wrong"));
            assertTrue(!c.loginWithPassword("bob", ""));                 // an account without a password is not for ssh
            assertTrue(!c.loginWithPassword("nobody", "x"));
            assertTrue(c.loginWithPassword("alice", "wonderland"));      // the same connection goes on after refusals
        }
        Thread.sleep(500);
        try (var c = new SshTestClient(port)) {                          // the next connection, the same host key
            c.handshake();
            assertTrue(java.util.Arrays.equals(firstHostKey, c.hostKey));
            assertTrue(!c.loginWithKey("alice", new byte[32], true));    // a key that is not listed
            assertTrue(c.loginWithKey("alice", seed, true));
        }
        Thread.sleep(500);
        try (var c = new SshTestClient(port)) {
            c.handshake();
            assertTrue(!c.loginWithKey("alice", seed, false), "a wrong signature");
        }
    }
}
