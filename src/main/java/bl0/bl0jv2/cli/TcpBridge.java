package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

import static bl0.bl0jv2.cli.NicFrame.*;

/**
 * Relays a real host TCP socket into stdlib/net/nic.bl0's own host-bridge
 * ports, so a bl0jv2 program using stdlib/net/tcp.bl0 (or http.bl0) can
 * accept a REAL connection (curl, a browser, telnet) instead of only ever
 * talking to itself. Unlike {@link UdpBridge} (one real datagram = one
 * frame, no state), TCP is connection-oriented: this class is a small,
 * separate implementation of tcp.bl0's own client-side protocol, written in
 * Java, that does a real three-way handshake with the bl0jv2-side listener
 * on this bridge's behalf, tracks seq/ack, and does a real FIN close - see
 * stdlib/net/tcp.bl0's own header comment for the wire format this mirrors
 * (and NicFrame's own doc for why the port layout is shared with
 * UdpBridge, and what that does and doesn't let run at once).
 *
 * <p>One real connection at a time: the bl0jv2-side tcpListen()+tcpAccept()
 * pair this bridges into only ever accepts one connection per call anyway
 * (see tcpListen()'s own doc) - accept() blocks here until the current
 * bridged connection is fully done before taking the next real one.
 */
final class TcpBridge {

    private static final int FIN = 0x01, SYN = 0x02, ACK = 0x10, PSH = 0x08;
    private static final int RELAY_CHUNK = 1400; // leaves room for the 40-byte IP+TCP header under MAX_FRAME_BYTES

    private final Bl0jv2_jVM vm;
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
            try {
                handleClient(client);
            } catch (IOException ignored) {
                // best-effort - a broken relay just ends this one
                // connection, the same as any other network failure would
            }
        }
    }

    private void handleClient(Socket client) throws IOException {
        client.setTcpNoDelay(true);
        // bounds relayRealToVm()'s own read() below - without this, a
        // real client that sends one request, reads one reply, and simply
        // stops (without ever explicitly half-closing its own write side
        // first) leaves that thread blocked forever. A plain timeout-
        // driven close from ANOTHER thread while a read() is still
        // outstanding is what to avoid instead (see relayRealToVm()'s own
        // doc): closing a socket out from under a pending read can make
        // the OS send an RST instead of a graceful FIN, discarding
        // whatever this bridge JUST wrote and flushed to the real client
        // moments earlier - the actual bug this timeout replaces.
        client.setSoTimeout(1500);
        FakeConn conn = new FakeConn(remoteFakeIp, nextEphemeralPort++, localFakeIp, localFakePort);

        if (!handshake(conn)) {
            client.close();
            return; // nothing on the bl0jv2 side ever answered - no listener, or it's busy with another connection
        }

        Thread toVm = new Thread(() -> relayRealToVm(client, conn), "tcp-bridge-to-vm");
        Thread fromVm = new Thread(() -> relayVmToReal(client, conn), "tcp-bridge-from-vm");
        toVm.setDaemon(true);
        fromVm.setDaemon(true);
        toVm.start();
        fromVm.start();

        try {
            toVm.join();
            fromVm.join();
        } catch (InterruptedException ignored) {
        }
        client.close();
    }

    // three-way handshake, acting as the CLIENT toward whatever bl0jv2
    // program is tcpListen()ing at (localFakeIp, localFakePort) - mirrors
    // stdlib/net/tcp.bl0's own tcpConnect()
    private boolean handshake(FakeConn conn) {
        conn.seq = isn();
        sendToVm(conn, SYN, new byte[0]);
        conn.seq++;

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = pollTx(conn);
            if (frame != null) {
                int flags = tcpFlags(frame);
                if (hasFlag(flags, SYN) && hasFlag(flags, ACK)) {
                    conn.ack = tcpSeq(frame) + 1;
                    sendToVm(conn, ACK, new byte[0]);
                    return true;
                }
            }
            sleep(5);
        }
        return false;
    }

    // real client -> bl0jv2 side: blocking reads off the real socket,
    // each chunk becomes one DATA segment (no MSS-based re-segmentation
    // beyond this fixed chunk size - see tcp.bl0's own header comment on
    // why one segment per send is fine for this toy stack). EOF on the
    // real side becomes a real FIN to the bl0jv2 side - and so does the
    // OTHER side (relayVmToReal) finishing first: a real client that
    // sends one request, reads one reply, and simply stops (without ever
    // explicitly half-closing its own write side) would otherwise leave
    // this read() blocked forever, which is exactly why handleClient()
    // gave the socket a read timeout - conn.vmDone is what this checks on
    // each timeout to decide whether to keep waiting or wind down too.
    private void relayRealToVm(Socket client, FakeConn conn) {
        byte[] buf = new byte[RELAY_CHUNK];
        try {
            InputStream in = client.getInputStream();
            while (!conn.vmDone) {
                int n;
                try {
                    n = in.read(buf);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (n < 0)
                    break; // real EOF
                if (n > 0) {
                    sendToVm(conn, ACK | PSH, Arrays.copyOf(buf, n));
                    conn.seq += n;
                }
            }
        } catch (IOException ignored) {
            // real socket errored - treat exactly like a clean EOF below,
            // there's nothing more this direction can do either way
        }
        sendToVm(conn, FIN | ACK, new byte[0]);
        conn.seq++;
        try {
            client.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    // bl0jv2 side -> real client: polls the TX ports for segments
    // addressed to THIS connection's fake 4-tuple, writes any payload to
    // the real socket, and ends this direction on a FIN - mirrors
    // stdlib/net/tcp.bl0's own tcpReceive()/its FIN handling in
    // tcpHandleSegment()
    private void relayVmToReal(Socket client, FakeConn conn) {
        try {
            relayVmToRealLoop(client, conn);
        } finally {
            // unblocks relayRealToVm()'s own read() loop once this
            // direction is done, one way or another - see its own doc
            conn.vmDone = true;
        }
    }

    private void relayVmToRealLoop(Socket client, FakeConn conn) {
        OutputStream out;
        try {
            out = client.getOutputStream();
        } catch (IOException e) {
            return;
        }

        while (true) {
            byte[] frame = pollTx(conn);
            if (frame == null) {
                sleep(1);
                continue;
            }

            int flags = tcpFlags(frame);
            byte[] payload = tcpPayload(frame);

            if (payload.length > 0) {
                conn.ack = tcpSeq(frame) + payload.length;
                try {
                    out.write(payload);
                    out.flush();
                } catch (IOException e) {
                    return;
                }
                sendToVm(conn, ACK, new byte[0]);
            }

            if (hasFlag(flags, FIN)) {
                conn.ack = tcpSeq(frame) + 1;
                sendToVm(conn, ACK, new byte[0]);
                try {
                    client.shutdownOutput();
                } catch (IOException ignored) {
                }
                return;
            }
        }
    }

    // injects a segment for 'conn' as if it just arrived from the real
    // network - the RX-port counterpart to nicHostSend() (see
    // stdlib/net/nic.bl0's own doc), just built here in Java instead of
    // bl0jv2 since this bridge (not the VM) is the one speaking for the
    // fake remote client. relayRealToVm() (forwarding the real client's
    // own bytes/FIN) and relayVmToReal() (acknowledging what it just
    // received) are two DIFFERENT threads for the SAME connection, and
    // both call this - NicFrame.injectRx()'s own synchronization and
    // consumed-before-overwritten wait (see its own doc) is what keeps
    // those from racing on the single RX slot.
    private void sendToVm(FakeConn conn, int flags, byte[] payload) {
        byte[] frame = buildTcpFrame(conn.remoteIp, conn.remotePort, conn.localIp, conn.localPort,
                conn.seq, conn.ack, flags, payload);
        injectRx(vm, frame);
    }

    // reads whatever the bl0jv2 side most recently sent via nicHostSend()
    // (see nic.bl0's own doc on why this is a poll, not a callback), and
    // returns it only if it's TCP and addressed exactly the other way
    // around from sendToVm() above (bl0jv2's local address as source, this
    // connection's fake remote address as destination) - anything else
    // (someone else's traffic sharing the one wire - see NicFrame's own
    // doc) is silently not ours, same as UdpBridge's own TX filtering
    private byte[] pollTx(FakeConn conn) {
        NicFrame.TxPoll result = NicFrame.pollTx(vm, conn.lastTxSeqSeen);
        if (result == null)
            return null;
        conn.lastTxSeqSeen = (int) result.seq;
        byte[] frame = result.frame;

        if (frame.length < 40 || ipProto(frame) != IP_PROTO_TCP)
            return null;
        if (ipSrcAddr(frame) != conn.localIp || tcpSrcPort(frame) != conn.localPort)
            return null;
        if (ipDstAddr(frame) != conn.remoteIp || tcpDstPort(frame) != conn.remotePort)
            return null;
        return frame;
    }

    private synchronized int isn() {
        int v = isnCounter;
        isnCounter += 12345;
        return v;
    }

    // mutable per-connection state - seq/ack/lastTxSeqSeen are written
    // from one relay thread and read from the other (each direction also
    // sends its own ACKs), so all three are volatile: no compound
    // check-then-act atomicity is needed here (this toy protocol tolerates
    // a slightly-stale ack the same way tcp.bl0 itself does - see its own
    // doc on skipping real retransmission/windowing), just visibility
    // between the two threads. vmDone is the same kind of flag, purely for
    // relayRealToVm()'s own timeout check - see its own doc.
    private static final class FakeConn {
        final int remoteIp;
        final int remotePort;
        final int localIp;
        final int localPort;
        volatile int seq;
        volatile int ack;
        volatile int lastTxSeqSeen = -1;
        volatile boolean vmDone = false;

        FakeConn(int remoteIp, int remotePort, int localIp, int localPort) {
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.localIp = localIp;
            this.localPort = localPort;
        }
    }

    // mirrors stdlib/net/tcp.bl0's own tcpBuildHeader()/tcpApplyChecksum()/
    // tcpSendSegment() - a 20-byte TCP header (no options) checksummed
    // over a 12-byte pseudo-header + itself + the payload, wrapped in a
    // 20-byte IP header (see NicFrame.buildIpHeader())
    private static byte[] buildTcpFrame(int srcAddr, int srcPort, int dstAddr, int dstPort,
                                         int seq, int ack, int flags, byte[] payload) {
        byte[] tcpHeader = new byte[20];
        writeU16(tcpHeader, 0, srcPort);
        writeU16(tcpHeader, 2, dstPort);
        writeU32(tcpHeader, 4, seq);
        writeU32(tcpHeader, 8, ack);
        tcpHeader[12] = 0x50;
        tcpHeader[13] = (byte) flags;
        writeU16(tcpHeader, 14, 0xFFFF); // window - fixed, no real flow control, see tcp.bl0's own doc
        writeU16(tcpHeader, 16, 0);      // checksum patched in below
        writeU16(tcpHeader, 18, 0);      // urgent pointer - unused

        int tcpLen = 20 + payload.length;
        byte[] pseudo = new byte[12];
        writeU32(pseudo, 0, srcAddr);
        writeU32(pseudo, 4, dstAddr);
        pseudo[8] = 0;
        pseudo[9] = (byte) IP_PROTO_TCP;
        writeU16(pseudo, 10, tcpLen);

        byte[] combined = new byte[12 + tcpLen];
        System.arraycopy(pseudo, 0, combined, 0, 12);
        System.arraycopy(tcpHeader, 0, combined, 12, 20);
        System.arraycopy(payload, 0, combined, 32, payload.length);
        writeU16(tcpHeader, 16, ipChecksum(combined, 0, combined.length));

        byte[] ipHeader = buildIpHeader(srcAddr, dstAddr, IP_PROTO_TCP, tcpLen);

        byte[] frame = new byte[20 + tcpLen];
        System.arraycopy(ipHeader, 0, frame, 0, 20);
        System.arraycopy(tcpHeader, 0, frame, 20, 20);
        System.arraycopy(payload, 0, frame, 40, payload.length);
        return frame;
    }

    private static int tcpSrcPort(byte[] frame) {
        return readU16(frame, 20);
    }

    private static int tcpDstPort(byte[] frame) {
        return readU16(frame, 22);
    }

    private static int tcpSeq(byte[] frame) {
        return readU32(frame, 24);
    }

    private static int tcpFlags(byte[] frame) {
        return frame[33] & 0xFF;
    }

    // fixed 20-byte TCP header (no options, matching tcp.bl0's own
    // tcpBuildHeader() - see its own doc), so the payload always starts at
    // byte 40 (20 IP + 20 TCP); dataOffsetWords is read anyway rather than
    // hardcoded, so a frame carrying real options would fail loudly
    // (truncated/garbage payload) instead of silently assuming none
    private static byte[] tcpPayload(byte[] frame) {
        int totalLen = ipTotalLen(frame);
        int dataOffsetWords = (frame[32] >>> 4) & 0xF;
        int headerLen = dataOffsetWords * 4;
        int payloadLen = totalLen - 20 - headerLen;
        if (payloadLen <= 0 || 20 + headerLen + payloadLen > frame.length)
            return new byte[0];
        return Arrays.copyOfRange(frame, 20 + headerLen, 20 + headerLen + payloadLen);
    }

    private static boolean hasFlag(int flags, int flag) {
        return (flags & flag) != 0;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }
}
