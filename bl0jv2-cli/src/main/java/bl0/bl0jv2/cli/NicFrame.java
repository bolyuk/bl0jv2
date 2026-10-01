package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

// port/vector layout and IP-level byte helpers shared by every host bridge
// (UdpBridge, TcpBridge) - the port numbers below MUST match
// stdlib/net/nic.bl0's own Nic.host* static fields exactly (see that
// file's own header comment for the layout); there is no way to
// introspect a bl0jv2-level class field from Java, so this is kept in
// sync by hand, the same way the keyboard bridge already hardcodes port
// 0 / vector 2 to match kernel.bl0's own Keyboard.dataPort/vector.
//
// Every host bridge shares this ONE set of NIC ports - there is only one
// "wire" (see nic.bl0's own "single data register" doc), so running a UDP
// bridge and a TCP bridge at the same time would race on it exactly the
// way two concurrent real senders already can on the UDP side. Fine for
// "does this work at all" testing (one bridge at a time); a real
// multiplexed NIC queue is a natural extension, not built here.
final class NicFrame {
    private NicFrame() {}

    static final int MAX_FRAME_BYTES = 1500;

    // RX (host -> VM): data region is 102 .. 102+MAX_FRAME_BYTES-1 (1601) -
    // seq/ack sit safely past that
    static final int HOST_RX_VECTOR = 4;
    static final int HOST_RX_LEN_PORT = 100;
    static final int HOST_RX_DATA_BASE = 102;
    static final int HOST_RX_SEQ_PORT = 1700;
    static final int HOST_RX_ACK_PORT = 1702;

    // TX (VM -> host): data region is 2004 .. 2004+MAX_FRAME_BYTES-1 (3503) -
    // ack sits past THAT (3504 exactly would still be legal, but that's
    // exactly the kind of one-past-the-end value that turns into a real
    // collision the moment MAX_FRAME_BYTES is ever raised even slightly -
    // rounding up to 3600 leaves an actual margin, not size arithmetic
    // someone has to re-verify by hand every time either constant changes)
    static final int HOST_TX_SEQ_PORT = 2000;
    static final int HOST_TX_LEN_PORT = 2002;
    static final int HOST_TX_DATA_BASE = 2004;
    static final int HOST_TX_ACK_PORT = 3600;

    // DNS (VM -> host -> VM): a request/response pair, not a frame at all
    // (no IP/UDP wrapping - see stdlib/net/dns.bl0's own doc on why this
    // is a separate, simpler mechanism than the NIC ports above). Name
    // region is 4004 .. 4004+MAX_DNS_NAME_BYTES-1 (4259)
    static final int MAX_DNS_NAME_BYTES = 255; // longest a real DNS name is ever allowed to be
    static final int HOST_DNS_REQ_SEQ_PORT = 4000;
    static final int HOST_DNS_REQ_LEN_PORT = 4002;
    static final int HOST_DNS_REQ_DATA_BASE = 4004;
    static final int HOST_DNS_RESP_ACK_PORT = 4300;
    static final int HOST_DNS_RESP_IP_PORT = 4302;

    // the host sets this to 1 when it attaches any bridge (TxDispatcher does, as
    // soon as a bridge subscribes), so Nic.initAuto() in stdlib/net/nic.bl0 can tell
    // a bridged run from a plain one without the program knowing how it was launched
    static final int HOST_BRIDGE_PRESENT_PORT = 3602;

    static final int IP_PROTO_ICMP = 1;
    static final int IP_PROTO_UDP = 17;
    static final int IP_PROTO_TCP = 6;

    private static final AtomicInteger rxSeq = new AtomicInteger(0);

    // injects 'frame' as if it just arrived from the real network and
    // BLOCKS until the VM's own onHostNicIRQ() (stdlib/net/nic.bl0) has
    // actually consumed it - see nic.bl0's own doc for why this hand-off
    // has to be synchronous: the RX port window is a single slot, no
    // queue, so returning before the frame is drained risks a second
    // injection (from another thread - UdpBridge's RX loop and a
    // TcpBridge connection's two relay threads can all call this -
    // or just the next real packet/segment) overwriting it first.
    // serialized per VM (not per class): every bridge of ONE VM shares its
    // one RX slot, but a different VM has its own - a class-wide lock let a
    // leftover bridge thread stuck waiting 2 s for an ack from a VM that had
    // already finished hold up every other VM's injections.
    static boolean injectRx(Bl0jv2_jVM vm, byte[] frame) {
        synchronized (vm) {
            return injectRxLocked(vm, frame);
        }
    }

    private static boolean injectRxLocked(Bl0jv2_jVM vm, byte[] frame) {
        if (frame.length > MAX_FRAME_BYTES)
            return false; // toy safeguard - see nicHostSend()'s own doc on the same truncation choice, mirrored here

        for (int i = 0; i < frame.length; i++)
            vm.hostPortWrite(HOST_RX_DATA_BASE + i, 1, frame[i] & 0xFF);
        vm.hostPortWrite(HOST_RX_LEN_PORT, 2, frame.length);
        int seq = rxSeq.incrementAndGet() & 0xFFFF;
        vm.hostPortWrite(HOST_RX_SEQ_PORT, 2, seq);
        vm.raiseInterrupt(HOST_RX_VECTOR);

        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (vm.hostPortRead(HOST_RX_ACK_PORT, 2) == seq)
                return true;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false; // nothing ever consumed it - the VM side isn't bridged, or has stalled
    }

    static final class TxPoll {
        final long seq;
        final byte[] frame;

        TxPoll(long seq, byte[] frame) {
            this.seq = seq;
            this.frame = frame;
        }
    }

    // reads whatever nicHostSend() (stdlib/net/nic.bl0) most recently sent
    // - null if nothing new since lastSeqSeen. PortIO (the port bus both
    // sides read/write through) only ever takes its READ lock, for BOTH
    // reads and writes (see its own doc: that's a deliberate choice, for
    // the JMM happens-before edge alone, not for mutual exclusion) - so
    // two threads can genuinely be mid-read and mid-write on the SAME
    // bytes at once. nicHostSend() blocks until this exact seq is acked
    // before writing a new one, which closes that window in the common
    // case, but doesn't make the byte-by-byte read atomic against a write
    // that starts concurrently right at its edge - re-checking the seq
    // port AFTER finishing the length+data read is what actually catches
    // that: if it changed, nic.bl0 raced ahead and what was just read
    // could be torn (part old frame, part new), so this discards it
    // WITHOUT acking, same as if nothing had been seen at all - the
    // caller's next poll re-reads the (still-waiting, since it was never
    // acked) frame from scratch once the write has actually settled.
    static TxPoll pollTx(Bl0jv2_jVM vm, long lastSeqSeen) {
        long seq = vm.hostPortRead(HOST_TX_SEQ_PORT, 2);
        if (seq == lastSeqSeen)
            return null;

        int len = (int) vm.hostPortRead(HOST_TX_LEN_PORT, 2);
        byte[] frame = new byte[len];
        for (int i = 0; i < len; i++)
            frame[i] = (byte) vm.hostPortRead(HOST_TX_DATA_BASE + i, 1);

        if (vm.hostPortRead(HOST_TX_SEQ_PORT, 2) != seq)
            return null; // torn - see this method's own doc

        // ack as soon as the frame is confirmed non-torn, not after
        // deciding whose it is - nicHostSend() is blocked waiting on
        // exactly this, regardless of whether the caller ends up claiming
        // the frame or not
        vm.hostPortWrite(HOST_TX_ACK_PORT, 2, seq);
        return new TxPoll(seq, frame);
    }

    // builds a 20-byte IP header in this project's own fake format (see
    // stdlib/net/ip.bl0's own header comment) - fixed size, no options,
    // checksum computed over the header alone (the payload isn't covered,
    // matching ip.bl0's own ipBuildHeader() call site)
    static byte[] buildIpHeader(int srcAddr, int dstAddr, int proto, int payloadLen) {
        int totalLen = 20 + payloadLen;
        byte[] h = new byte[20];
        h[0] = 0x45;
        h[1] = 0;
        writeU16(h, 2, totalLen);
        writeU16(h, 4, 0);
        writeU16(h, 6, 0);
        h[8] = 64;
        h[9] = (byte) proto;
        writeU16(h, 10, 0);
        writeU32(h, 12, srcAddr);
        writeU32(h, 16, dstAddr);
        writeU16(h, 10, ipChecksum(h, 0, 20));
        return h;
    }

    // builds a frame in this project's OWN fake IP/UDP format (see
    // stdlib/net/ip.bl0 and udp.bl0's own header comments) - NOT a real
    // Ethernet/IP frame, so this can be built with a plain UDP socket and
    // no raw-socket privileges at all. Shared by UdpBridge (inbound: wraps
    // a real datagram that just arrived) and UdpOutboundBridge (outbound:
    // wraps a real reply on its way back in).
    static byte[] buildUdpFrame(int srcAddr, int srcPort, int dstAddr, int dstPort,
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

    // the reverse of buildUdpFrame(): pulls the UDP payload back out of a
    // frame the VM sent us via nicHostSend() - IP header (20 bytes, fixed,
    // no options - see ip.bl0's own doc) then an 8-byte UDP header then
    // the payload. null if the frame is malformed/truncated.
    static byte[] parseUdpPayload(byte[] frame) {
        int totalLen = ipTotalLen(frame);
        int udpLen = readU16(frame, 24);
        int payloadLen = udpLen - 8;
        if (payloadLen < 0 || 20 + udpLen > totalLen || 28 + payloadLen > frame.length)
            return null;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(frame, 28, payload, 0, payloadLen);
        return payload;
    }

    static int udpSrcPort(byte[] frame) {
        return readU16(frame, 20);
    }

    static int udpDstPort(byte[] frame) {
        return readU16(frame, 22);
    }

    static int ipProto(byte[] frame) {
        return frame[9] & 0xFF;
    }

    static int ipSrcAddr(byte[] frame) {
        return readU32(frame, 12);
    }

    static int ipDstAddr(byte[] frame) {
        return readU32(frame, 16);
    }

    static int ipTotalLen(byte[] frame) {
        return readU16(frame, 2);
    }

    // RFC1071 Internet checksum - the same ones'-complement-sum-of-16-bit-
    // words algorithm as stdlib/net/ip.bl0's own ipChecksum(), just over a
    // real Java byte[] instead of a bl0jv2 array
    static int ipChecksum(byte[] bytes, int offset, int length) {
        long sum = 0;
        for (int i = 0; i < length; i += 2) {
            int hi = bytes[offset + i] & 0xFF;
            int lo = (i + 1 < length) ? (bytes[offset + i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0)
            sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    static void writeU16(byte[] arr, int offset, int value) {
        arr[offset] = (byte) (value >>> 8);
        arr[offset + 1] = (byte) value;
    }

    static void writeU32(byte[] arr, int offset, int value) {
        arr[offset] = (byte) (value >>> 24);
        arr[offset + 1] = (byte) (value >>> 16);
        arr[offset + 2] = (byte) (value >>> 8);
        arr[offset + 3] = (byte) value;
    }

    static int readU16(byte[] arr, int offset) {
        return ((arr[offset] & 0xFF) << 8) | (arr[offset + 1] & 0xFF);
    }

    static int readU32(byte[] arr, int offset) {
        return ((arr[offset] & 0xFF) << 24) | ((arr[offset + 1] & 0xFF) << 16)
                | ((arr[offset + 2] & 0xFF) << 8) | (arr[offset + 3] & 0xFF);
    }

    static int addressToInt(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 4)
            return 0; // IPv6 - this toy stack's addresses are 32-bit only, see ip.bl0's own doc
        return readU32(b, 0);
    }
}
