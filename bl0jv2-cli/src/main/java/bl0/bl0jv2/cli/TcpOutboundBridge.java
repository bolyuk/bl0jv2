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
 * <p>The VM's tcp.bl0 retransmits an unanswered SYN, so the same SYN can
 * show up several times while the real connect is still in progress (it can
 * take seconds). Only the FIRST starts a connection; repeats of a connection
 * already being set up or running are ignored.
 *
 * <p>Because this lets the VM program reach any host it can name, it is
 * opt-in (--bridge-outbound), limited to {@link #MAX_CONNECTIONS} at a time
 * (further SYNs are dropped and the VM retransmits them later), and never
 * connects to an address that is meaningless or dangerous to reach from
 * here: the wildcard address, multicast, and link-local (169.254.0.0/16 -
 * where cloud instances serve their credentials-bearing metadata endpoint).
 * Loopback and private ranges are allowed: reaching a service on this
 * machine or LAN is the point of the bridge.
 */
public final class TcpOutboundBridge {

    static final int MAX_CONNECTIONS = 64;

    private final Bl0jv2_jVM vm;
    private final TcpRelay relay;
    private int isnCounter = 9000;
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(MAX_CONNECTIONS);
    // 4-tuples of connections being set up or running - see the class doc
    private final java.util.Set<String> active = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public TcpOutboundBridge(Bl0jv2_jVM vm) {
        this.vm = vm;
        this.relay = new TcpRelay(vm);
    }

    /** false for an address this bridge refuses to connect to - see the class doc */
    static boolean isAllowedDestination(InetAddress address) {
        return !(address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress());
    }

    public void start() {
        // a bare SYN (no ACK) is the one frame shape that can only mean "the
        // VM is opening a new connection"
        var syns = TxDispatcher.of(vm).subscribe(frame ->
                frame.length >= 40 && ipProto(frame) == IP_PROTO_TCP
                        && hasFlag(tcpFlags(frame), SYN) && !hasFlag(tcpFlags(frame), ACK));
        Thread t = new Thread(() -> watchLoop(syns), "tcp-outbound-watch");
        t.setDaemon(true);
        t.start();
    }

    private void watchLoop(TxDispatcher.Subscription syns) {
        while (true) {
            byte[] frame = syns.poll(100);
            if (frame == null)
                continue;

            int vmAddr = ipSrcAddr(frame);
            int vmPort = tcpSrcPort(frame);
            int destAddr = ipDstAddr(frame);
            int destPort = tcpDstPort(frame);
            int vmSeq = tcpSeq(frame);

            String key = vmAddr + ":" + vmPort + ">" + destAddr + ":" + destPort;
            if (!active.add(key))
                continue; // a retransmitted SYN for a connection already in progress

            if (!slots.tryAcquire()) {
                active.remove(key);
                continue; // at the limit - dropped, the VM resends it
            }

            Thread h = new Thread(() -> {
                try {
                    handleOutbound(vmAddr, vmPort, destAddr, destPort, vmSeq);
                } finally {
                    active.remove(key);
                    slots.release();
                }
            }, "tcp-outbound-connect");
            h.setDaemon(true);
            h.start();
        }
    }

    private void handleOutbound(int vmAddr, int vmPort, int destAddr, int destPort, int vmSeq) {
        Socket real;
        try {
            InetAddress destination = InetAddress.getByAddress(addrBytes(destAddr));
            if (!isAllowedDestination(destination))
                return; // refused: nothing sent back, the VM's connect() times out
            real = new Socket();
            real.connect(new InetSocketAddress(destination, destPort), 5000);
        } catch (IOException e) {
            // the real destination refused/timed out/etc - nothing sent
            // back to the VM (tcp.bl0 has no RST handling), so its
            // connect() gives up after its own timeout
            return;
        }

        // FakeConn's own field names are named from the CALLER's point of
        // view (see TcpRelay's own doc): remoteIp/remotePort is "whoever
        // this bridge is talking FOR" - here, that's the real destination
        // service (destAddr/destPort), since we're the one impersonating
        // it when injecting our SYN-ACK/data as if it arrived from there;
        // localIp/localPort is the VM's OWN address (vmAddr/vmPort)
        FakeConn conn = relay.newConn(destAddr, destPort, vmAddr, vmPort);
        conn.ack = vmSeq + 1;
        conn.seq = isn();

        relay.sendToVm(conn, SYN | ACK, new byte[0]);
        conn.seq++;

        boolean established = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = conn.tx.poll(5);
            if (frame != null && hasFlag(tcpFlags(frame), ACK)) {
                established = true;
                break;
            }
        }

        if (!established) {
            conn.close();
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
