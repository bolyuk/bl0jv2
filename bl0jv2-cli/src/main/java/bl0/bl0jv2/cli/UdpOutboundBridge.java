package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static bl0.bl0jv2.cli.NicFrame.*;

/**
 * OUTBOUND direction: lets a bl0jv2 program's own udpSend() (stdlib/net/
 * udp.bl0) reach a REAL external host - stdlib/net/dns.bl0's own DNS
 * queries are the reason this exists, but it works for any UDP traffic
 * the VM addresses to somewhere real. The reverse of {@link UdpBridge}
 * (a real sender reaching IN). Since udpSend() already relays its frame
 * out through the shared NIC TX wire whenever a host bridge is attached
 * (nicSend() -> nicHostSend() - see stdlib/net/nic.bl0's own doc), nothing
 * on the bl0jv2 side had to change for this: this class watches that same
 * wire for ANY outbound UDP frame and, for each one, opens a fresh
 * ephemeral real socket, sends the payload to whatever real address/port
 * the frame named as its destination, waits (bounded) for ONE reply, and
 * relays that back in tagged as coming from the real destination.
 *
 * <p>One socket per outbound datagram, not a shared/pooled one - mirrors
 * {@link TcpOutboundBridge}'s own per-connection-attempt thread, and
 * sidesteps having to track which of possibly several concurrent VM-side
 * senders a given reply belongs to (UDP itself gives no such correlation
 * beyond "same 4-tuple", which a dedicated socket per request already
 * gives for free). Fine for a request/response pattern like DNS; a UDP
 * "session" that expects several back-and-forth exchanges on one socket
 * would need a real table instead - not built here.
 */
final class UdpOutboundBridge {

    private final Bl0jv2_jVM vm;
    private volatile long lastSeqSeen = -1;

    UdpOutboundBridge(Bl0jv2_jVM vm) {
        this.vm = vm;
    }

    void start() {
        Thread t = new Thread(this::watchLoop, "udp-outbound-watch");
        t.setDaemon(true);
        t.start();
    }

    private void watchLoop() {
        while (true) {
            NicFrame.TxPoll result = NicFrame.pollTx(vm, lastSeqSeen);
            if (result == null) {
                TcpRelay.sleep(1);
                continue;
            }
            lastSeqSeen = result.seq;
            byte[] frame = result.frame;

            if (frame.length < 28 || ipProto(frame) != IP_PROTO_UDP)
                continue; // not UDP (or a TCP bridge's own traffic sharing this wire - see NicFrame's own doc) - not ours

            byte[] payload = parseUdpPayload(frame);
            if (payload == null)
                continue;

            int vmAddr = ipSrcAddr(frame);
            int vmPort = udpSrcPort(frame);
            int destAddr = ipDstAddr(frame);
            int destPort = udpDstPort(frame);

            Thread h = new Thread(() -> handleOutbound(vmAddr, vmPort, destAddr, destPort, payload),
                    "udp-outbound-send");
            h.setDaemon(true);
            h.start();
        }
    }

    private void handleOutbound(int vmAddr, int vmPort, int destAddr, int destPort, byte[] payload) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5000);
            InetAddress destination = InetAddress.getByAddress(addrBytes(destAddr));
            socket.send(new DatagramPacket(payload, payload.length, destination, destPort));

            byte[] buf = new byte[MAX_FRAME_BYTES];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            socket.receive(reply); // throws SocketTimeoutException if nothing answers in time

            byte[] replyFrame = buildUdpFrame(addressToInt(reply.getAddress()), reply.getPort(), vmAddr, vmPort,
                    reply.getData(), reply.getOffset(), reply.getLength());
            injectRx(vm, replyFrame);
        } catch (IOException ignored) {
            // no reply within the timeout, or the send itself failed -
            // matches how a real UDP query that never gets answered just
            // times out silently, nothing more this direction can do
        }
    }

    private static byte[] addrBytes(int addr) {
        byte[] b = new byte[4];
        writeU32(b, 0, addr);
        return b;
    }
}
