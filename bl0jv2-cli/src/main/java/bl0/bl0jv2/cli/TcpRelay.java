package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;

import static bl0.bl0jv2.cli.NicFrame.*;

/**
 * Everything about relaying a real TCP socket against stdlib/net/tcp.bl0's
 * own wire format that doesn't care which SIDE opened the connection -
 * frame building/parsing, and the bidirectional byte pump once a
 * connection is ESTABLISHED. {@link TcpBridge} (a real client connects IN,
 * this bridge acts as the CLIENT toward the VM's own tcpListen()) and
 * {@link TcpOutboundBridge} (the VM calls tcpConnect() itself, this bridge
 * acts as the SERVER toward it AND opens a real outbound Socket) only
 * differ in how a {@link FakeConn} gets to ESTABLISHED - the three-way
 * handshake's own direction - not in anything below that.
 */
final class TcpRelay {

    static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08, ACK = 0x10, URG = 0x20;
    private static final int RELAY_CHUNK = 1400; // leaves room for the 40-byte IP+TCP header under MAX_FRAME_BYTES

    private final Bl0jv2_jVM vm;

    TcpRelay(Bl0jv2_jVM vm) {
        this.vm = vm;
    }

    // mutable per-connection state - seq/ack/lastTxSeqSeen are written
    // from one relay thread and read from the other (each direction also
    // sends its own ACKs), so all three are volatile: no compound
    // check-then-act atomicity is needed here (this toy protocol tolerates
    // a slightly-stale ack the same way tcp.bl0 itself does - see its own
    // doc on skipping real retransmission/windowing), just visibility
    // between the two threads. vmDone is the same kind of flag, purely for
    // relayRealToVm()'s own timeout check - see its own doc.
    static final class FakeConn {
        final int remoteIp;
        final int remotePort;
        final int localIp;
        final int localPort;
        volatile int seq;
        volatile int ack;
        volatile boolean vmDone = false;
        // every frame the VM sends to this connection's 4-tuple, in order -
        // see TxDispatcher for why a connection does not read the TX window itself
        final TxDispatcher.Subscription tx;

        private FakeConn(int remoteIp, int remotePort, int localIp, int localPort, TxDispatcher.Subscription tx) {
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.localIp = localIp;
            this.localPort = localPort;
            this.tx = tx;
        }

        void close() {
            tx.close();
        }
    }

    // subscribes BEFORE anything is sent, so no reply to the first frame can
    // be missed. remote/local are as in FakeConn's doc: the VM's own address is
    // 'local', and what it sends back to us carries it as the SOURCE.
    FakeConn newConn(int remoteIp, int remotePort, int localIp, int localPort) {
        var tx = TxDispatcher.of(vm).subscribe(frame ->
                frame.length >= 40 && ipProto(frame) == IP_PROTO_TCP
                        && ipSrcAddr(frame) == localIp && tcpSrcPort(frame) == localPort
                        && ipDstAddr(frame) == remoteIp && tcpDstPort(frame) == remotePort);
        return new FakeConn(remoteIp, remotePort, localIp, localPort, tx);
    }

    // starts both relay directions and blocks until either one ends on
    // its own, then closes the real socket - shared by both bridges once
    // their own handshake (mirror images of each other) has left 'conn'
    // ESTABLISHED
    void run(Socket real, FakeConn conn) {
        try {
            real.setTcpNoDelay(true);
            // bounds relayRealToVm()'s own read() below - see its own doc
            // on why: a real peer that sends one message, reads one reply,
            // and simply stops (without ever explicitly half-closing its
            // own write side) would otherwise leave that thread blocked
            // forever.
            real.setSoTimeout(1500);
        } catch (IOException e) {
            return;
        }

        Thread toVm = new Thread(() -> relayRealToVm(real, conn), "tcp-relay-to-vm");
        Thread fromVm = new Thread(() -> relayVmToReal(real, conn), "tcp-relay-from-vm");
        toVm.setDaemon(true);
        fromVm.setDaemon(true);
        toVm.start();
        fromVm.start();

        try {
            toVm.join();
            fromVm.join();
        } catch (InterruptedException ignored) {
        }
        try {
            real.close();
        } catch (IOException ignored) {
        }
        conn.close();
    }

    // real peer -> bl0jv2 side: blocking reads off the real socket, each
    // chunk becomes one DATA segment (no MSS-based re-segmentation beyond
    // this fixed chunk size - see tcp.bl0's own header comment on why one
    // segment per send is fine for this toy stack). EOF on the real side
    // becomes a real FIN to the bl0jv2 side - and so does the OTHER
    // direction (relayVmToReal) finishing first: conn.vmDone is what the
    // read timeout's own retry checks, so a real peer that never
    // explicitly half-closes doesn't block this thread forever once the
    // OTHER direction is already done.
    private void relayRealToVm(Socket real, FakeConn conn) {
        byte[] buf = new byte[RELAY_CHUNK];
        try {
            InputStream in = real.getInputStream();
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
            real.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    // bl0jv2 side -> real peer: polls the TX ports for segments addressed
    // to THIS connection's fake 4-tuple, writes any payload to the real
    // socket, and ends this direction on a FIN - mirrors
    // stdlib/net/tcp.bl0's own tcpReceive()/its FIN handling in
    // tcpHandleSegment()
    private void relayVmToReal(Socket real, FakeConn conn) {
        try {
            relayVmToRealLoop(real, conn);
        } finally {
            // unblocks relayRealToVm()'s own read() loop once this
            // direction is done, one way or another - see its own doc
            conn.vmDone = true;
        }
    }

    private void relayVmToRealLoop(Socket real, FakeConn conn) {
        OutputStream out;
        try {
            out = real.getOutputStream();
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

            // the VM retransmits anything unacknowledged (tcp.bl0), so the same
            // segment can arrive twice - if our ACK was slow or lost, or an
            // earlier copy is still in flight. Writing a repeat to the real
            // socket would corrupt the stream, so only the segment that
            // starts exactly at the next expected byte is accepted; a repeat is
            // just acknowledged again, and a segment from the future (an
            // earlier one was lost) is dropped until the VM resends in order.
            int seqNo = tcpSeq(frame);
            int behind = conn.ack - seqNo; // > 0: already received

            if (payload.length > 0) {
                if (behind > 0) {
                    sendToVm(conn, ACK, new byte[0]);
                    continue;
                }
                if (behind < 0)
                    continue;
                conn.ack = seqNo + payload.length;
                try {
                    out.write(payload);
                    out.flush();
                } catch (IOException e) {
                    return;
                }
                sendToVm(conn, ACK, new byte[0]);
            }

            if (hasFlag(flags, FIN)) {
                if (behind > 0) {
                    sendToVm(conn, ACK, new byte[0]);
                    continue;
                }
                if (behind < 0)
                    continue;
                conn.ack = seqNo + 1;
                sendToVm(conn, ACK, new byte[0]);
                try {
                    real.shutdownOutput();
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
    // fake peer. relayRealToVm() and relayVmToReal() are two DIFFERENT
    // threads for the SAME connection, and both call this -
    // NicFrame.injectRx()'s own synchronization and consumed-before-
    // overwritten wait (see its own doc) is what keeps those from racing
    // on the single RX slot.
    void sendToVm(FakeConn conn, int flags, byte[] payload) {
        byte[] frame = buildTcpFrame(conn.remoteIp, conn.remotePort, conn.localIp, conn.localPort,
                conn.seq, conn.ack, flags, payload);
        injectRx(vm, frame);
    }

    // the next frame the bl0jv2 side sent to this connection's 4-tuple, or
    // null if none is waiting. (Used to read the shared TX window directly and
    // filter - see TxDispatcher for why that lost other connections' frames.)
    byte[] pollTx(FakeConn conn) {
        return conn.tx.poll();
    }

    // mirrors stdlib/net/tcp.bl0's own tcpBuildHeader()/tcpApplyChecksum()/
    // tcpSendSegment() - a 20-byte TCP header (no options) checksummed
    // over a 12-byte pseudo-header + itself + the payload, wrapped in a
    // 20-byte IP header (see NicFrame.buildIpHeader())
    static byte[] buildTcpFrame(int srcAddr, int srcPort, int dstAddr, int dstPort,
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

    static int tcpSrcPort(byte[] frame) {
        return readU16(frame, 20);
    }

    static int tcpDstPort(byte[] frame) {
        return readU16(frame, 22);
    }

    static int tcpSeq(byte[] frame) {
        return readU32(frame, 24);
    }

    static int tcpFlags(byte[] frame) {
        return frame[33] & 0xFF;
    }

    // fixed 20-byte TCP header (no options, matching tcp.bl0's own
    // tcpBuildHeader() - see its own doc), so the payload always starts at
    // byte 40 (20 IP + 20 TCP); dataOffsetWords is read anyway rather than
    // hardcoded, so a frame carrying real options would fail loudly
    // (truncated/garbage payload) instead of silently assuming none
    static byte[] tcpPayload(byte[] frame) {
        int totalLen = ipTotalLen(frame);
        int dataOffsetWords = (frame[32] >>> 4) & 0xF;
        int headerLen = dataOffsetWords * 4;
        int payloadLen = totalLen - 20 - headerLen;
        if (payloadLen <= 0 || 20 + headerLen + payloadLen > frame.length)
            return new byte[0];
        return Arrays.copyOfRange(frame, 20 + headerLen, 20 + headerLen + payloadLen);
    }

    static boolean hasFlag(int flags, int flag) {
        return (flags & flag) != 0;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }
}
