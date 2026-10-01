package bl0.bl0jv2.cli;

import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// the VM side of host bridging: Nic.initAuto(), sending from user mode through
// the kernel's syscall, and the ICMP bridge
class NetBridgeTest {

    private static String run(Path dir, String body, Consumer<Bl0jv2_jVM> attachBridges) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/net/icmp.bl0'; " + body);
        String source = Files.readString(entry);
        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        byte[] bytecode = new Bl0jv2_Compiler().compile(Bl0jv2_Linker.resolveImports(ast, entry));

        var vm = new Bl0jv2_jVM();
        vm.set_interrupt_poll_interval(1);
        StringWriter sw = new StringWriter();
        vm.set_out_writer(new PrintWriter(sw));
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode)); // bridges attach AFTER feeding: loading a program resets the port space
        attachBridges.accept(vm);
        vm.run_instructions();
        return sw.toString();
    }

    @Test
    void initAutoStaysLoopbackOnlyWithoutABridge(@TempDir Path dir) throws IOException {
        assertEquals("false", run(dir, "Nic.initAuto(); print Nic.hostBridgeEnabled;", vm -> { }));
    }

    @Test
    void initAutoEnablesTheHostPathWhenABridgeIsAttached(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir, "Nic.initAuto(); print Nic.hostBridgeEnabled;",
                vm -> new IcmpOutboundBridge(vm, a -> true).start()));
    }

    @Test
    void aPingForARealHostIsAnsweredWhenTheHostSaysItIsUp(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir,
                "Nic.initAuto(); print Icmp.ping(Net.localIp, 0x0A010203, 1, 3000) >= 0;",
                vm -> new IcmpOutboundBridge(vm, a -> true).start()));
    }

    @Test
    void aPingForAHostThatDoesNotAnswerTimesOut(@TempDir Path dir) throws IOException {
        assertEquals("-1", run(dir,
                "Nic.initAuto(); print Icmp.ping(Net.localIp, 0x0A010203, 1, 300);",
                vm -> new IcmpOutboundBridge(vm, a -> false).start()));
    }

    @Test
    void aPingFromUserModeGoesOutThroughTheKernelsSyscall(@TempDir Path dir) throws IOException {
        // out8/out16 (the host TX ports) are privileged: from user mode the frame
        // reaches the bridge only because Nic.send() asks the kernel to write it
        assertEquals("false|true", run(dir,
                "Nic.initAuto(); dropToUserMode(); " +
                "print str(isPrivileged()) + '|' + str(Icmp.ping(Net.localIp, 0x0A010203, 1, 3000) >= 0);",
                vm -> new IcmpOutboundBridge(vm, a -> true).start()));
    }

    @Test
    void theLoopbackRangeIsAnsweredByTheVmNotTheBridge(@TempDir Path dir) throws IOException {
        // the bridge says "down" for everything; 127.0.0.1 still answers
        assertEquals("true", run(dir,
                "Nic.initAuto(); print Icmp.ping(Net.localIp, 0x7F000001, 1, 1000) >= 0;",
                vm -> new IcmpOutboundBridge(vm, a -> false).start()));
    }

    @Test
    void theEchoReplyEchoesIdSequenceAndPayloadWithAddressesSwapped() {
        byte[] icmp = {8, 0, 0, 0, 0x12, 0x34, 0x00, 0x07, 'h', 'i'};
        int csum = NicFrame.ipChecksum(icmp, 0, icmp.length);
        NicFrame.writeU16(icmp, 2, csum);
        byte[] ip = NicFrame.buildIpHeader(0x0A000002, 0x0A010203, NicFrame.IP_PROTO_ICMP, icmp.length);
        byte[] request = new byte[20 + icmp.length];
        System.arraycopy(ip, 0, request, 0, 20);
        System.arraycopy(icmp, 0, request, 20, icmp.length);

        byte[] reply = IcmpOutboundBridge.buildEchoReply(request);

        assertNotNull(reply);
        assertEquals(0x0A010203, NicFrame.ipSrcAddr(reply));
        assertEquals(0x0A000002, NicFrame.ipDstAddr(reply));
        assertEquals(0, reply[20]);                          // echo reply
        assertEquals(0x1234, NicFrame.readU16(reply, 24));   // id
        assertEquals(7, NicFrame.readU16(reply, 26));        // sequence
        assertEquals('h', reply[28]);
        assertEquals(0, NicFrame.ipChecksum(reply, 20, reply.length - 20)); // valid checksum sums to 0
        assertEquals(0, NicFrame.ipChecksum(reply, 0, 20));
    }

    @Test
    void aMalformedRequestGetsNoReply() {
        assertEquals(null, IcmpOutboundBridge.buildEchoReply(new byte[10]));
    }
}
