package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Net.pump (the IP receive path), Udp queue/recvWait, Icmp ping, address helpers
class Bl0jv2_NetLayerTest {

    private static String run(Path dir, String body) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/net/icmp.bl0'; import 'stdlib/net/tcp.bl0'; import 'stdlib/net/udp.bl0'; " + body);
        return Bl0jv2_TestRunner.runFile(entry);
    }

    @Test
    void parseAndFormatAddresses(@TempDir Path dir) throws IOException {
        assertEquals("10.0.0.2|167772162|true|nil|nil|nil|nil|nil", run(dir,
                "a = Ip.parseAddr('10.0.0.2'); print Ip.formatAddr(a) + '|' + str(a) + '|' + str(Ip.parseAddr('255.255.255.255') == -1) + '|' + " +
                "str(Ip.parseAddr('10.0.0')) + '|' + str(Ip.parseAddr('10.0.0.256')) + '|' + str(Ip.parseAddr('a.b.c.d')) + '|' + " +
                "str(Ip.parseAddr('1..2.3')) + '|' + str(Ip.parseAddr('1.2.3.4.5'));"));
    }

    @Test
    void aDatagramIsDeliveredEvenWhileTcpIsBeingPumped(@TempDir Path dir) throws IOException {
        // TCP's pump used to take every frame off the NIC and drop the non-TCP ones
        assertEquals("hello|0", run(dir,
                "Nic.init(); Udp.send(1, 100, 2, 200, 'hello'); " +
                "i = 0; while (i < 50 && Nic.pending() == 0) { i = i + 1; wait(1); } " +
                "TcpRegistry.pump(); " +
                "p = UdpPacket.receive(); print p.message + '|' + str(Net.dropped);"));
    }

    @Test
    void recvWaitPicksTheRightPortAndLeavesTheOthersQueued(@TempDir Path dir) throws IOException {
        assertEquals("b|a|nil", run(dir,
                "Nic.init(); Udp.send(1, 100, 2, 2000, 'a'); Udp.send(1, 100, 2, 3000, 'b'); " +
                "p3 = Udp.recvWait(3000, 1000); p2 = Udp.recvWait(2000, 1000); none = Udp.recvWait(4000, 50); " +
                "print p3.message + '|' + p2.message + '|' + str(none);"));
    }

    @Test
    void recvWaitTimesOutWithNil(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir,
                "Nic.init(); t = ticks(); p = Udp.recvWait(-1, 80); print str(p == nil && ticks() - t >= 70);"));
    }

    @Test
    void aFrameWithABadHeaderChecksumIsDropped(@TempDir Path dir) throws IOException {
        assertEquals("nothing|1", run(dir,
                "Nic.init(); h = Ip.buildHeader(1, 2, Ip.protoUdp, 8); h[10] = (h[10] + 1) & 0xFF; " +
                "Nic.send(Arr.appendAll(h, [0, 1, 0, 2, 0, 8, 0, 0])); " +
                "i = 0; while (i < 50 && Nic.pending() == 0) { i = i + 1; wait(1); } " +
                "Net.pump(); print (Udp.hasPacket() ? 'got one' : 'nothing') + '|' + str(Net.dropped);"));
    }

    @Test
    void aFrameForAProtocolNobodyRegisteredIsDropped(@TempDir Path dir) throws IOException {
        assertEquals("1", run(dir,
                "Nic.init(); Nic.send(Ip.buildHeader(1, 2, 99, 0)); " +
                "i = 0; while (i < 50 && Nic.pending() == 0) { i = i + 1; wait(1); } " +
                "Net.pump(); print Net.dropped;"));
    }

    @Test
    void aTruncatedFrameIsDropped(@TempDir Path dir) throws IOException {
        assertEquals("1", run(dir,
                "Nic.init(); h = Ip.buildHeader(1, 2, Ip.protoUdp, 100); Nic.send(h); " + // claims 120 bytes, carries 20
                "i = 0; while (i < 50 && Nic.pending() == 0) { i = i + 1; wait(1); } " +
                "Net.pump(); print Net.dropped;"));
    }

    @Test
    void pingingOurOwnAddressGetsAReply(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir,
                "Nic.init(); rtt = Icmp.ping(Net.localIp, Net.localIp, 1, 1000); print rtt >= 0;"));
    }

    @Test
    void pingingTheLoopbackRangeGetsAReply(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir,
                "Nic.init(); rtt = Icmp.ping(Net.localIp, 0x7F000001, 1, 1000); print rtt >= 0;"));
    }

    @Test
    void pingingAnotherAddressTimesOutBecauseNothingAnswers(@TempDir Path dir) throws IOException {
        assertEquals("-1", run(dir,
                "Nic.init(); print Icmp.ping(Net.localIp, 0x0A000063, 1, 100);"));
    }

    @Test
    void severalPingsEachGetTheirOwnReply(@TempDir Path dir) throws IOException {
        assertEquals("true|true|true", run(dir,
                "Nic.init(); a = Icmp.ping(Net.localIp, Net.localIp, 1, 1000); b = Icmp.ping(Net.localIp, Net.localIp, 2, 1000); " +
                "c = Icmp.ping(Net.localIp, Net.localIp, 3, 1000); print str(a >= 0) + '|' + str(b >= 0) + '|' + str(c >= 0);"));
    }

    @Test
    void aCorruptedIcmpMessageIsIgnored(@TempDir Path dir) throws IOException {
        assertEquals("0|0", run(dir,
                "Nic.init(); msg = Icmp.build(8, 5, 1, [1, 2, 3, 4]); msg[8] = msg[8] ^ 0xFF; " + // flip a payload byte after the checksum was set
                "Nic.send(Arr.appendAll(Ip.buildHeader(Net.localIp, Net.localIp, Ip.protoIcmp, len(msg)), msg)); " +
                "i = 0; while (i < 50 && Nic.pending() == 0) { i = i + 1; wait(1); } Net.pump(); Net.pump(); " +
                // a valid request would have been answered, and the reply queued
                "print str(len(Icmp.replies)) + '|' + str(Nic.pending());"));
    }
}
