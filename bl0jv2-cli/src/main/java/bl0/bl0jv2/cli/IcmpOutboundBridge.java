package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.Semaphore;

import static bl0.bl0jv2.cli.NicFrame.*;

/**
 * OUTBOUND ping: lets a bl0jv2 program's {@code Icmp.ping()} (stdlib/net/
 * icmp.bl0) find out whether a REAL host is reachable. The VM's echo request
 * leaves through the NIC's TX window like every other frame; this bridge sees
 * it, asks the host whether the destination answers, and if so injects an echo
 * reply that looks like it came from there.
 *
 * <p>"Reachable" is {@link InetAddress#isReachable}: a real ICMP echo when the
 * process may send one (privileged), otherwise a TCP connection attempt to the
 * echo port - which many hosts refuse, so a host that is up can still look
 * unreachable here. That is a limit of doing this from plain Java without raw
 * sockets, not of the VM side.
 *
 * <p>Requests for 127.0.0.0/8 are left alone (the VM answers those itself), and
 * the same destinations as {@link TcpOutboundBridge#isAllowedDestination} are
 * refused. At most {@link #MAX_IN_FLIGHT} checks run at once.
 */
final class IcmpOutboundBridge {

    static final int MAX_IN_FLIGHT = 16;

    /** the reachability test - a seam so a test can answer without a network */
    @FunctionalInterface
    interface Reachability {
        boolean test(InetAddress address) throws IOException;
    }

    private final Bl0jv2_jVM vm;
    private final Reachability reachability;
    private final Semaphore slots = new Semaphore(MAX_IN_FLIGHT);

    IcmpOutboundBridge(Bl0jv2_jVM vm) {
        this(vm, address -> address.isReachable(2000));
    }

    IcmpOutboundBridge(Bl0jv2_jVM vm, Reachability reachability) {
        this.vm = vm;
        this.reachability = reachability;
    }

    void start() {
        var requests = TxDispatcher.of(vm).subscribe(frame ->
                frame.length >= 28 && ipProto(frame) == IP_PROTO_ICMP && frame[20] == 8);
        Thread t = new Thread(() -> watchLoop(requests), "icmp-outbound-watch");
        t.setDaemon(true);
        t.start();
    }

    private void watchLoop(TxDispatcher.Subscription requests) {
        while (true) {
            byte[] request = requests.poll(100);
            if (request == null)
                continue;
            if (!slots.tryAcquire())
                continue; // too many checks pending - dropped, the ping just times out

            Thread h = new Thread(() -> {
                try {
                    handle(request);
                } finally {
                    slots.release();
                }
            }, "icmp-outbound-check");
            h.setDaemon(true);
            h.start();
        }
    }

    private void handle(byte[] request) {
        try {
            int dest = ipDstAddr(request);
            if ((dest >>> 24) == 127)
                return; // the VM answers its own loopback
            InetAddress address = InetAddress.getByAddress(new byte[]{
                    (byte) (dest >>> 24), (byte) (dest >>> 16), (byte) (dest >>> 8), (byte) dest});
            if (!TcpOutboundBridge.isAllowedDestination(address) || !reachability.test(address))
                return;
            byte[] reply = buildEchoReply(request);
            if (reply != null)
                injectRx(vm, reply);
        } catch (IOException ignored) {
            // unresolvable / unreachable: no reply, the ping times out
        }
    }

    /** the echo reply to an echo request frame: addresses swapped, same id/sequence/payload; null if malformed */
    static byte[] buildEchoReply(byte[] request) {
        int total = ipTotalLen(request);
        if (total < 28 || total > request.length)
            return null;
        int icmpLen = total - 20;

        byte[] icmp = new byte[icmpLen];
        System.arraycopy(request, 20, icmp, 0, icmpLen);
        icmp[0] = 0; // echo reply
        icmp[1] = 0;
        writeU16(icmp, 2, 0);
        writeU16(icmp, 2, ipChecksum(icmp, 0, icmpLen));

        byte[] ip = buildIpHeader(ipDstAddr(request), ipSrcAddr(request), IP_PROTO_ICMP, icmpLen);
        byte[] frame = new byte[20 + icmpLen];
        System.arraycopy(ip, 0, frame, 0, 20);
        System.arraycopy(icmp, 0, frame, 20, icmpLen);
        return frame;
    }
}
