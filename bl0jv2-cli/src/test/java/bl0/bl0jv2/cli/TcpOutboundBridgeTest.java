package bl0.bl0jv2.cli;

import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// exercises TcpOutboundBridge/UdpOutboundBridge against a REAL local
// socket this test opens itself (loopback, not the actual internet - see
// UdpOutboundBridge's own doc on why "does the real send/receive
// mechanism work" is testable without real internet access, and
// Bl0jv2_StdlibTest's own dns.bl0 tests for the wire-format logic that
// doesn't need a socket at all). Package-private access to
// TcpOutboundBridge/UdpOutboundBridge is why this lives in bl0.bl0jv2.cli
// rather than alongside every other stdlib/net test in bl0.bl0jv2.
class TcpOutboundBridgeTest {

    private static byte[] compileWithImports(Path entryFile) throws IOException {
        String source = Files.readString(entryFile);
        var lexer = new Bl0jv2_Lexer();
        var parser = new Bl0jv2_Parser();
        var compiler = new Bl0jv2_Compiler();

        parser.setSourceCode(source);
        var ast = parser.getAST(lexer.getTokens(source));
        if (!(ast instanceof PROGRAM_N program))
            throw new IllegalStateException("parser did not produce a program");
        var linked = Bl0jv2_Linker.resolveImports(program, entryFile);
        return compiler.compile(linked);
    }

    private static String libPath(String fileName) {
        return Path.of("stdlib", fileName).toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    @Test
    void tcpConnectReachesARealLocalServerAndExchangesData(@TempDir Path dir) throws IOException, InterruptedException {
        ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        int realPort = server.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try (Socket client = server.accept()) {
                byte[] buf = new byte[1024];
                int n = client.getInputStream().read(buf);
                String received = new String(buf, 0, n, StandardCharsets.US_ASCII);
                client.getOutputStream().write(("echo: " + received).getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
            } catch (IOException ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import '" + libPath("net/tcp.bl0") + "'; " +
                "Nic.initWithHostBridge(); " +
                "conn = TcpConn.connect(0x0A000002, 6100, 0x7F000001, " + realPort + "); " +
                "conn.send('hi from bl0jv2'); " +
                "i = 0; while (i < 200 && !conn.hasData()) { i = i + 1; wait(20); } " +
                "print conn.receive(); " +
                "conn.close();");

        byte[] bytecode = compileWithImports(entry);
        var vm = new Bl0jv2_jVM();
        vm.set_interrupt_poll_interval(1);
        StringWriter sw = new StringWriter();
        vm.set_out_writer(new PrintWriter(sw));
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        new TcpOutboundBridge(vm).start();
        vm.run_instructions();

        serverThread.join(2000);
        server.close();

        assertEquals("echo: hi from bl0jv2", sw.toString());
    }

    @Test
    void udpSendReachesARealLocalServerAndGetsAReply(@TempDir Path dir) throws IOException {
        java.net.DatagramSocket server = new java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress());
        int realPort = server.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try {
                byte[] buf = new byte[1024];
                java.net.DatagramPacket packet = new java.net.DatagramPacket(buf, buf.length);
                server.receive(packet);
                String received = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.US_ASCII);
                byte[] reply = ("echo: " + received).getBytes(StandardCharsets.US_ASCII);
                server.send(new java.net.DatagramPacket(reply, reply.length, packet.getSocketAddress()));
            } catch (IOException ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Nic.send() loops a sent frame back to its own sender too, even
        // with a real bridge attached (see nic.bl0's own doc) - the sent
        // query arrives back through UdpPacket.receive() just like a real
        // reply would, almost always before the real one does, so the
        // script has to skip a packet whose source is itself (see
        // dns.bl0's own Dns.resolve() for the same pattern used for real)
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import '" + libPath("net/udp.bl0") + "'; " +
                "Nic.initWithHostBridge(); " +
                "Udp.send(0x0A000002, 6200, 0x7F000001, " + realPort + ", 'hi from bl0jv2 udp'); " +
                "pkt = nil; " +
                "i = 0; " +
                "while (i < 200) { " +
                "  i = i + 1; " +
                "  if (!Udp.hasPacket()) { wait(20); } " +
                "  else { " +
                "    candidate = UdpPacket.receive(); " +
                "    if (candidate.srcIp != 0x0A000002 || candidate.srcPort != 6200) { pkt = candidate; break; } " +
                "  } " +
                "} " +
                "print pkt.message;");

        byte[] bytecode = compileWithImports(entry);
        var vm = new Bl0jv2_jVM();
        vm.set_interrupt_poll_interval(1);
        StringWriter sw = new StringWriter();
        vm.set_out_writer(new PrintWriter(sw));
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        new UdpOutboundBridge(vm).start();
        vm.run_instructions();

        server.close();

        assertEquals("echo: hi from bl0jv2 udp", sw.toString());
    }
}
