package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import static bl0.bl0jv2.cli.NicFrame.*;
import static bl0.bl0jv2.cli.TcpRelay.*;

/**
 * OUTBOUND direction: lets a bl0jv2 program's own tcpConnect() (stdlib/net/
 * tcp.bl0) reach a REAL external host, the reverse of {@link TcpBridge}
 * (which lets a real host reach IN to a bl0jv2 tcpListen()). Since
 * tcpConnect() already relays its SYN out through the shared NIC TX wire
 * whenever a host bridge is attached (nicSend() -> nicHostSend() - see
 * stdlib/net/nic.bl0's own doc), NOTHING on the bl0jv2 side had to change
 * for this: this class just watches that same wire for a bare SYN (no
 * ACK) - the one frame shape that can ONLY mean "the VM is opening a new
 * connection", never a reply to something this bridge (or {@link
 * TcpBridge}) already knows about - opens a real Socket to whatever
 * address/port that SYN named as its destination, and does the OTHER half
 * of the three-way handshake (SYN+ACK, then wait for the VM's own ACK) to
 * bring it to ESTABLISHED, the same place {@link TcpBridge}'s own
 * handshake ends up, just reached from the opposite role. Everything past
 * that point is identical either way - see {@link TcpRelay}.
 *
 * <p>Multiple outbound connections can be in flight at once (each gets its
 * own thread once its SYN is seen) - unlike {@link TcpBridge}, there's no
 * "one real socket accept() at a time" constraint here, since nothing
 * bl0jv2-side inherently limits how many tcpConnect()s a program can have
 * open together (see tcp.bl0's own TcpRegistry).
 *
 * <p>Known gap: stdlib/net/tcp.bl0's own tcpConnect() has no timeout - if
 * the real destination refuses the connection, is unreachable, or this
 * watcher never sees the SYN at all, the bl0jv2 program hangs in
 * tcpConnect() forever. Fixing that belongs in tcp.bl0 itself (a bounded
 * wait there would benefit the loopback case too), not patched around
 * here.
 */
final class TcpOutboundBridge {

    private final Bl0jv2_jVM vm;
    private final TcpRelay relay;
    private int isnCounter = 9000;
    private volatile long lastSeqSeen = -1;

    TcpOutboundBridge(Bl0jv2_jVM vm) {
        this.vm = vm;
        this.relay = new TcpRelay(vm);
    }

    void start() {
        Thread t = new Thread(this::watchLoop, "tcp-outbound-watch");
        t.setDaemon(true);
        t.start();
    }

    private void watchLoop() {
        while (true) {
            NicFrame.TxPoll result = NicFrame.pollTx(vm, lastSeqSeen);
            if (result == null) {
                sleep(1);
                continue;
            }
            lastSeqSeen = result.seq;
            byte[] frame = result.frame;

            if (frame.length >= 40 && ipProto(frame) == IP_PROTO_TCP) {
                int flags = tcpFlags(frame);
                if (hasFlag(flags, SYN) && !hasFlag(flags, ACK)) {
                    int vmAddr = ipSrcAddr(frame);
                    int vmPort = tcpSrcPort(frame);
                    int destAddr = ipDstAddr(frame);
                    int destPort = tcpDstPort(frame);
                    int vmSeq = tcpSeq(frame);
                    Thread h = new Thread(() -> handleOutbound(vmAddr, vmPort, destAddr, destPort, vmSeq),
                            "tcp-outbound-connect");
                    h.setDaemon(true);
                    h.start();
                }
            }
        }
    }

    private void handleOutbound(int vmAddr, int vmPort, int destAddr, int destPort, int vmSeq) {
        Socket real;
        try {
            InetAddress destination = InetAddress.getByAddress(addrBytes(destAddr));
            real = new Socket();
            real.connect(new InetSocketAddress(destination, destPort), 5000);
        } catch (IOException e) {
            // the real destination refused/timed out/etc - nothing sent
            // back to the VM (tcp.bl0 has no RST handling - see this
            // class's own doc on the resulting hang), this connection
            // attempt just silently goes nowhere, matching a SYN lost on
            // a real unreliable network with no response ever arriving
            return;
        }

        // FakeConn's own field names are named from the CALLER's point of
        // view (see TcpRelay's own doc): remoteIp/remotePort is "whoever
        // this bridge is talking FOR" - here, that's the real destination
        // service (destAddr/destPort), since we're the one impersonating
        // it when injecting our SYN-ACK/data as if it arrived from there;
        // localIp/localPort is the VM's OWN address (vmAddr/vmPort) -
        // sendToVm() addresses every injected frame's destination there,
        // and pollTx() expects the VM's own outgoing frames to carry it as
        // their source, for tcp.bl0's own address matching
        // (ipParseHeader/tcpFindMatch) to ever recognize either side of
        // this exchange as belonging to the same connection
        FakeConn conn = new FakeConn(destAddr, destPort, vmAddr, vmPort);
        conn.ack = vmSeq + 1;
        conn.seq = isn();

        relay.sendToVm(conn, SYN | ACK, new byte[0]);
        conn.seq++;

        boolean established = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = relay.pollTx(conn);
            if (frame != null && hasFlag(tcpFlags(frame), ACK)) {
                established = true;
                break;
            }
            sleep(5);
        }

        if (!established) {
            try {
                real.close();
            } catch (IOException ignored) {
            }
            return;
        }

        relay.run(real, conn);
    }

    private synchronized int isn() {
        int v = isnCounter;
        isnCounter += 12345;
        return v;
    }

    private static byte[] addrBytes(int addr) {
        byte[] b = new byte[4];
        writeU32(b, 0, addr);
        return b;
    }
}
