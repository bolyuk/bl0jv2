package bl0.bl0jv2.cli;

import bl0.bl0jv2.AeonSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import static org.junit.jupiter.api.Assertions.assertTrue;

// the aeon-os shell's network commands against REAL loopback sockets, through
// the same host bridges the CLI starts with --bridge-outbound / --bridge-tcp
class ShellNetTest {

    private static AeonSession shell(Consumer<Bl0jv2_jVM> bridges) throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("shell.bl0"), 1, vm -> {
            vm.set_interrupt_poll_interval(1);
            bridges.accept(vm);
        });
        assertTrue(s.waitFor("aeon-shell ready", 15_000), s.output());
        return s;
    }

    private static void outbound(Bl0jv2_jVM vm) {
        new TcpOutboundBridge(vm).start();
        new UdpOutboundBridge(vm).start();
        new IcmpOutboundBridge(vm, a -> true).start();
    }

    private static Thread daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void httpFetchesAPageFromARealServer() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            daemon(() -> {
                try (Socket c = server.accept()) {
                    byte[] buf = new byte[2048];
                    int n = c.getInputStream().read(buf);
                    String request = new String(buf, 0, Math.max(n, 0), StandardCharsets.US_ASCII);
                    String body = request.startsWith("GET /hi ") ? "hello shell" : "wrong request";
                    OutputStream out = c.getOutputStream();
                    out.write(("HTTP/1.0 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (IOException ignored) {
                }
            });
            var s = shell(ShellNetTest::outbound);
            s.type("http 127.0.0.1:" + server.getLocalPort() + " /hi\r");
            assertTrue(s.waitFor("hello shell", 15_000), s.output());
            assertTrue(s.output().contains("200 OK"), s.output());
        }
    }

    @Test
    void tcpSendsTextAndShowsTheReply() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            daemon(() -> {
                try (Socket c = server.accept()) {
                    byte[] buf = new byte[1024];
                    int n = c.getInputStream().read(buf);
                    c.getOutputStream().write(("echo: " + new String(buf, 0, Math.max(n, 0), StandardCharsets.US_ASCII))
                            .getBytes(StandardCharsets.US_ASCII));
                    c.getOutputStream().flush();
                } catch (IOException ignored) {
                }
            });
            var s = shell(ShellNetTest::outbound);
            s.type("tcp 127.0.0.1 " + server.getLocalPort() + " ping pong\r");
            assertTrue(s.waitFor("echo: ping pong", 15_000), s.output());
        }
    }

    @Test
    void udpSendsADatagramAndShowsTheReply() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            daemon(() -> {
                try {
                    DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
                    server.receive(p);
                    byte[] reply = ("echo: " + new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII))
                            .getBytes(StandardCharsets.US_ASCII);
                    server.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
                } catch (IOException ignored) {
                }
            });
            var s = shell(ShellNetTest::outbound);
            s.type("udp 127.0.0.1 " + server.getLocalPort() + " hello udp\r");
            assertTrue(s.waitFor("echo: hello udp", 15_000), s.output());
            assertTrue(s.output().contains("reply from 127.0.0.1:" + server.getLocalPort()), s.output());
        }
    }

    @Test
    void pingReportsRepliesFromAHostTheBridgeSaysIsUp() throws Exception {
        var s = shell(vm -> new IcmpOutboundBridge(vm, a -> true).start());
        s.type("ping 10.1.2.3\r");
        assertTrue(s.waitFor("4 sent, 4 received", 15_000), s.output());
    }

    @Test
    void httpdAnswersARealClientThroughTheInboundBridge() throws Exception {
        int hostPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            hostPort = probe.getLocalPort();
        }
        final int port = hostPort;
        var s = shell(vm -> {
            try {
                new TcpBridge(vm, port, 0x0A000002, 8080, 0x0A000001).start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        s.type("httpd 8080\r");
        assertTrue(s.waitFor("listening on 10.0.0.2:8080", 15_000), s.output());

        Thread.sleep(300); // the listen() call follows the banner
        try (Socket c = new Socket(InetAddress.getLoopbackAddress(), port)) {
            c.setSoTimeout(15_000);
            c.getOutputStream().write("GET /page HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            c.getOutputStream().flush();
            StringBuilder got = new StringBuilder();
            byte[] buf = new byte[1024];
            int n;
            try {
                while ((n = c.getInputStream().read(buf)) > 0) got.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
            } catch (IOException ignored) {
            }
            assertTrue(got.toString().contains("hello from aeon-os, you asked for /page"), got + "\n--- shell:\n" + s.output());
        }
        assertTrue(s.waitFor("served one request", 15_000), s.output());
    }

    @Test
    void resolveGivesUpWhenNothingAnswers() throws Exception {
        var s = shell(ShellNetTest::outbound);
        // port 53 of a loopback address nobody listens on: no answer comes back
        s.type("setdns 127.0.0.1\r");
        assertTrue(s.waitFor("dns server is now 127.0.0.1", 10_000), s.output());
        s.type("resolve nowhere.test\r");
        assertTrue(s.waitFor("no answer from DNS server 127.0.0.1", 20_000), s.output());
    }
}
