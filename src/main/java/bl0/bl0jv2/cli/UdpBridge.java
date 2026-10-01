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
 * INBOUND direction: relays a real host UDP socket into stdlib/net/
 * nic.bl0's own host-bridge ports, so a bl0jv2 program using stdlib/net/
 * udp.bl0 can accept a REAL datagram (curl, netcat, a Python test client)
 * instead of only ever looping one back to itself. See {@link
 * UdpOutboundBridge} for the reverse direction (the VM's own udpSend()
 * reaching OUT to a real host it names, e.g. stdlib/net/dns.bl0's own DNS
 * queries), and NicFrame's own doc for why the port layout is shared
 * between every bridge in this package.
 *
 * <p>Reply addressing is deliberately the simplest thing that could work
 * for testing: "whoever sent us the most recent real packet" - not a real
 * per-connection table. Fine for poking at this with netcat/curl one
 * request at a time; genuinely concurrent real senders would need a real
 * source-address table, not built here. Kept separate from {@link
 * UdpOutboundBridge}'s own send path specifically so a reply keeps coming
 * FROM the same port a real client sent TO (this bridge's own bound
 * socket) - a strict client checking that would reject a reply arriving
 * from UdpOutboundBridge's own, different, ephemeral port instead.
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

            byte[] frame = buildUdpFrame(addressToInt(packet.getAddress()), srcPort, localFakeIp, socket.getLocalPort(),
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

            byte[] payload = parseUdpPayload(frame);
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
}
