package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static bl0.bl0jv2.cli.NicFrame.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the bridges' view of the VM's TX window: one reader, frames fanned out
class TxDispatcherTest {

    // what stdlib/net/nic.bl0's Nic.hostSend() does: write the frame and its
    // length, bump the sequence number, then wait for the bridge to ack it
    private static void vmSends(Bl0jv2_jVM vm, int seq, byte[] frame) {
        for (int i = 0; i < frame.length; i++)
            vm.hostPortWrite(HOST_TX_DATA_BASE + i, 1, frame[i] & 0xFF);
        vm.hostPortWrite(HOST_TX_LEN_PORT, 2, frame.length);
        vm.hostPortWrite(HOST_TX_SEQ_PORT, 2, seq);
        long deadline = System.currentTimeMillis() + 3000;
        while (vm.hostPortRead(HOST_TX_ACK_PORT, 2) != seq) {
            if (System.currentTimeMillis() > deadline)
                throw new AssertionError("frame " + seq + " was never consumed");
            TcpRelay.sleep(1);
        }
    }

    private static byte[] tcpFrame(int srcPort, int dstPort, int seq, int flags) {
        return TcpRelay.buildTcpFrame(0x0A000002, srcPort, 0x0A000001, dstPort, seq, 0, flags, new byte[0]);
    }

    @Test
    void everyMatchingSubscriberGetsEveryFrameItWantsInOrder() {
        var vm = new Bl0jv2_jVM();
        var dispatcher = TxDispatcher.of(vm);
        var evens = dispatcher.subscribe(f -> TcpRelay.tcpDstPort(f) % 2 == 0);
        var odds = dispatcher.subscribe(f -> TcpRelay.tcpDstPort(f) % 2 == 1);
        var all = dispatcher.subscribe(f -> true);

        // alternating destinations - two readers polling the window themselves
        // used to take frames meant for each other
        for (int i = 1; i <= 20; i++)
            vmSends(vm, i, tcpFrame(5000, 100 + i, i, TcpRelay.ACK));

        List<Integer> gotEvens = new ArrayList<>(), gotOdds = new ArrayList<>();
        int allCount = 0;
        byte[] f;
        while ((f = evens.poll(500)) != null) gotEvens.add(TcpRelay.tcpDstPort(f));
        while ((f = odds.poll(500)) != null) gotOdds.add(TcpRelay.tcpDstPort(f));
        while (all.poll(100) != null) allCount++;

        assertEquals(List.of(102, 104, 106, 108, 110, 112, 114, 116, 118, 120), gotEvens);
        assertEquals(List.of(101, 103, 105, 107, 109, 111, 113, 115, 117, 119), gotOdds);
        assertEquals(20, allCount);
        evens.close(); odds.close(); all.close();
    }

    @Test
    void aClosedSubscriptionStopsReceiving() {
        var vm = new Bl0jv2_jVM();
        var dispatcher = TxDispatcher.of(vm);
        var keep = dispatcher.subscribe(f -> true);
        var drop = dispatcher.subscribe(f -> true);
        drop.close();

        vmSends(vm, 1, tcpFrame(1, 2, 1, TcpRelay.ACK));

        assertTrue(keep.poll(500) != null);
        assertEquals(null, drop.poll(100));
        keep.close();
    }

    @Test
    void aSynRetransmittedByTheVmOpensOnlyOneRealConnection() throws IOException, InterruptedException {
        var vm = new Bl0jv2_jVM();
        var server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        var accepted = new AtomicInteger();
        Thread acceptor = new Thread(() -> {
            try {
                while (true) {
                    server.accept();
                    accepted.incrementAndGet();
                }
            } catch (IOException ignored) {
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        new TcpOutboundBridge(vm).start();
        byte[] syn = TcpRelay.buildTcpFrame(0x0A000002, 6100, 0x7F000001, server.getLocalPort(), 1000, 0, TcpRelay.SYN, new byte[0]);

        // the same SYN three times (tcp.bl0 resends an unanswered one)
        vmSends(vm, 1, syn);
        vmSends(vm, 2, syn);
        vmSends(vm, 3, syn);
        Thread.sleep(500);

        assertEquals(1, accepted.get());
        server.close();
    }

    @Test
    void synsForDifferentConnectionsEachGetTheirOwn() throws IOException, InterruptedException {
        var vm = new Bl0jv2_jVM();
        var server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        var accepted = new AtomicInteger();
        Thread acceptor = new Thread(() -> {
            try {
                while (true) {
                    server.accept();
                    accepted.incrementAndGet();
                }
            } catch (IOException ignored) {
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        new TcpOutboundBridge(vm).start();
        vmSends(vm, 1, TcpRelay.buildTcpFrame(0x0A000002, 6100, 0x7F000001, server.getLocalPort(), 1000, 0, TcpRelay.SYN, new byte[0]));
        vmSends(vm, 2, TcpRelay.buildTcpFrame(0x0A000002, 6101, 0x7F000001, server.getLocalPort(), 2000, 0, TcpRelay.SYN, new byte[0]));
        Thread.sleep(500);

        assertEquals(2, accepted.get());
        server.close();
    }

    @Test
    void metadataAndWildcardAddressesAreNeverConnectedTo() throws Exception {
        assertEquals(false, TcpOutboundBridge.isAllowedDestination(InetAddress.getByName("169.254.169.254")));
        assertEquals(false, TcpOutboundBridge.isAllowedDestination(InetAddress.getByName("0.0.0.0")));
        assertEquals(false, TcpOutboundBridge.isAllowedDestination(InetAddress.getByName("224.0.0.1")));
        assertEquals(true, TcpOutboundBridge.isAllowedDestination(InetAddress.getByName("127.0.0.1")));
        assertEquals(true, TcpOutboundBridge.isAllowedDestination(InetAddress.getByName("10.1.2.3")));
        assertEquals(true, TcpOutboundBridge.isAllowedDestination(InetAddress.getByName("93.184.216.34")));
    }
}
