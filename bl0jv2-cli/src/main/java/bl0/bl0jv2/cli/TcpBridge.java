package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static bl0.bl0jv2.cli.TcpRelay.*;

/**
 * INBOUND direction: relays a real host TCP socket into stdlib/net/nic.bl0's
 * own host-bridge ports, so a bl0jv2 program using stdlib/net/tcp.bl0 (or
 * http.bl0) can accept a REAL connection (curl, a browser, telnet) instead
 * of only ever talking to itself. This bridge does a real three-way
 * handshake ACTING AS THE CLIENT toward the VM's own tcpListen() - see
 * {@link TcpOutboundBridge} for the reverse direction (the VM calls
 * tcpConnect() itself, reaching OUT to a real host), and {@link TcpRelay}
 * for everything past the handshake that both share.
 *
 * <p>One real connection at a time: the bl0jv2-side tcpListen()+tcpAccept()
 * pair this bridges into only ever accepts one connection per call anyway
 * (see tcpListen()'s own doc) - accept() blocks here until the current
 * bridged connection is fully done before taking the next real one.
 */
final class TcpBridge {

    private final Bl0jv2_jVM vm;
    private final TcpRelay relay;
    private final ServerSocket serverSocket;
    private final int localFakeIp;
    private final int localFakePort;
    private final int remoteFakeIp;
    private int nextEphemeralPort = 40000;
    private int isnCounter = 5000;

    // localFakeIp/localFakePort: the bl0jv2 program's own address and the
    // port its tcpListen() is bound to - this bridge has to know both, up
    // front, since (unlike UDP, where every packet already carries its own
    // destination) it has to actively address a SYN there itself.
    // remoteFakeIp: the address this bridge tells the VM every real client
    // connects FROM - one fixed "outside" address, not a real per-client
    // one, the same simplification UdpBridge's own localFakeIp doc
    // describes. Bound to loopback specifically - see UdpBridge's own doc
    // on why (a wildcard bind makes Windows prompt for network access this
    // bridge never actually needs, since it's local-testing-only).
    TcpBridge(Bl0jv2_jVM vm, int hostTcpPort, int localFakeIp, int localFakePort, int remoteFakeIp) throws IOException {
        this.vm = vm;
        this.relay = new TcpRelay(vm);
        this.localFakeIp = localFakeIp;
        this.localFakePort = localFakePort;
        this.remoteFakeIp = remoteFakeIp;
        this.serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), hostTcpPort));
    }

    void start() {
        Thread t = new Thread(this::acceptLoop, "tcp-bridge-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                return; // socket closed - nothing left for this thread to do
            }
            handleClient(client);
        }
    }

    private void handleClient(Socket client) {
        FakeConn conn = relay.newConn(remoteFakeIp, nextEphemeralPort++, localFakeIp, localFakePort);

        if (!handshake(conn)) {
            conn.close();
            try {
                client.close();
            } catch (IOException ignored) {
            }
            return; // nothing on the bl0jv2 side ever answered - no listener, or it's busy with another connection
        }

        relay.run(client, conn);
    }

    // three-way handshake, acting as the CLIENT toward whatever bl0jv2
    // program is tcpListen()ing at (localFakeIp, localFakePort) - mirrors
    // stdlib/net/tcp.bl0's own tcpConnect()
    private boolean handshake(FakeConn conn) {
        conn.seq = isn();
        relay.sendToVm(conn, SYN, new byte[0]);
        conn.seq++;

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = relay.pollTx(conn);
            if (frame != null) {
                int flags = tcpFlags(frame);
                if (hasFlag(flags, SYN) && hasFlag(flags, ACK)) {
                    conn.ack = tcpSeq(frame) + 1;
                    relay.sendToVm(conn, ACK, new byte[0]);
                    return true;
                }
            }
            sleep(5);
        }
        return false;
    }

    private synchronized int isn() {
        int v = isnCounter;
        isnCounter += 12345;
        return v;
    }
}
