package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import static bl0.bl0jv2.cli.NicFrame.*;

/**
 * Relays a real host UDP socket into stdlib/net/nic.bl0's own host-bridge
 * ports, so a bl0jv2 program using stdlib/net/udp.bl0 can talk to a REAL
 * external program (curl, netcat, a Python test client) instead of only
 * ever looping a packet back to itself. See nic.bl0's own header comment
 * for the port layout and why the frame format crossing that boundary is
 * this project's OWN fake IP/UDP framing, not a real Ethernet/IP frame -
 * this class is exactly the piece that translates between the two, one
 * direction each way. See NicFrame's own doc for why the port layout is
 * shared with TcpBridge, and what that does and doesn't let run at once.
 *
 * <p>Reply addressing is deliberately the simplest thing that could work
 * for testing: "whoever sent us the most recent real packet" - not a real
 * per-connection table. Fine for poking at this with netcat/curl one
 * request at a time; genuinely concurrent real senders would need a real
 * source-address table, not built here.
 */
final class UdpBridge {

    private final Bl0jv2_jVM vm;
    private final DatagramSocket socket;
    private final int localFakeIp;
    private volatile SocketAddress lastRealSender;

    // localFakeIp: the 32-bit address this bridge tells the VM every real
    // packet came FROM originally being the real sender's own address
    // would be more faithful, but every demo in this project addresses
    // packets by a fixed convention (0x0A0000xx - see aeon-os/kernel.bl0
    // and the stdlib/net test suite) rather than parsing an arbitrary
    // sender address, so a single configured "outside" address is what
    // those demos actually expect to see
    //
    // bound to the loopback address specifically, not the wildcard
    // (0.0.0.0, every interface) a bare `new DatagramSocket(port)` binds
    // to - this is meant for local testing only (curl/netcat/a test
    // script on the SAME machine), and a wildcard bind makes Windows (and
    // most other firewalls) prompt to allow the process incoming network
    // access regardless of who actually connects, since the OS has no way
    // to know in advance that only loopback traffic will ever arrive. A
    // loopback-only bind is never exposed to the network at all, so
    // there's nothing for a firewall to gate.
    UdpBridge(Bl0jv2_jVM vm, int hostUdpPort, int localFakeIp) throws SocketException {
        this.vm = vm;
        this.localFakeIp = localFakeIp;
        this.socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), hostUdpPort));
        this.socket.setSoTimeout(50); // lets the RX loop re-check for shutdown without blocking forever
    }

    void start() {
        Thread rx = new Thread(this::runRx, "udp-bridge-rx");
        rx.setDaemon(true);
        rx.start();

        Thread tx = new Thread(this::runTx, "udp-bridge-tx");
        tx.setDaemon(true);
        tx.start();
    }

    private void runRx() {
        byte[] buf = new byte[MAX_FRAME_BYTES];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return; // socket closed - nothing left for this thread to do
            }

            lastRealSender = packet.getSocketAddress();
            int srcPort = packet.getPort();
            int payloadLen = packet.getLength();

            byte[] frame = buildFrame(addressToInt(packet.getAddress()), srcPort, localFakeIp, socket.getLocalPort(),
                    packet.getData(), packet.getOffset(), payloadLen);
            injectRx(vm, frame);
        }
    }

    private void runTx() {
        long lastSeq = -1;
        while (!socket.isClosed()) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                return;
            }

            NicFrame.TxPoll result = pollTx(vm, lastSeq);
            if (result == null)
                continue;
            lastSeq = result.seq;
            byte[] frame = result.frame;

            if (frame.length < 28 || ipProto(frame) != IP_PROTO_UDP)
                continue; // not a UDP frame (or a TCP bridge's own traffic sharing this wire - see NicFrame's own doc) - not ours

            SocketAddress dest = lastRealSender;
            if (dest == null)
                continue; // nothing has ever reached us for real - nowhere to send a reply

            byte[] payload = parsePayload(frame);
            if (payload == null)
                continue;

            try {
                socket.send(new DatagramPacket(payload, payload.length, dest));
            } catch (IOException e) {
                // best-effort relay - a dropped reply is no different from a
                // dropped packet on a real, unreliable network
            }
        }
    }

    // builds a frame in this project's OWN fake IP/UDP format (see
    // stdlib/net/ip.bl0 and udp.bl0's own header comments) - NOT a real
    // Ethernet/IP frame, so this can be built here with a plain UDP socket
    // and no raw-socket privileges at all
    private static byte[] buildFrame(int srcAddr, int srcPort, int dstAddr, int dstPort,
                                      byte[] payload, int payloadOffset, int payloadLen) {
        int udpLen = 8 + payloadLen;
        byte[] ipHeader = buildIpHeader(srcAddr, dstAddr, IP_PROTO_UDP, udpLen);

        byte[] frame = new byte[20 + udpLen];
        System.arraycopy(ipHeader, 0, frame, 0, 20);

        writeU16(frame, 20, srcPort);
        writeU16(frame, 22, dstPort);
        writeU16(frame, 24, udpLen);
        writeU16(frame, 26, 0); // UDP checksum - always 0, see udp.bl0's own doc

        System.arraycopy(payload, payloadOffset, frame, 28, payloadLen);
        return frame;
    }

    // the reverse of buildFrame(): pulls the UDP payload back out of a
    // frame the VM sent us via nicHostSend() - IP header (20 bytes, fixed,
    // no options - see ip.bl0's own doc) then an 8-byte UDP header then
    // the payload
    private static byte[] parsePayload(byte[] frame) {
        int totalLen = ipTotalLen(frame);
        int udpLen = readU16(frame, 24);
        int payloadLen = udpLen - 8;
        if (payloadLen < 0 || 20 + udpLen > totalLen || 28 + payloadLen > frame.length)
            return null;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(frame, 28, payload, 0, payloadLen);
        return payload;
    }
}
