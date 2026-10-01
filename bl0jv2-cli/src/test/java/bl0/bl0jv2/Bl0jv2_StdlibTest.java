package bl0.bl0jv2;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static bl0.bl0jv2.Bl0jv2_TestRunner.runFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

// exercises the real files under stdlib/ (not a copy embedded in the test),
// so this also catches a stdlib file that's syntactically broken or whose
// behavior drifted from what these tests expect. Each entry script imports
// the actual library by absolute path (forward slashes, to sidestep string
// escaping on a Windows-style backslash path) since import resolution is
// relative to the *importing* file, which here lives under @TempDir, not
// next to the library.
class Bl0jv2_StdlibTest {

    private static String libPath(String fileName) {
        return Path.of("stdlib", fileName).toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String run(Path dir, String libFile, String body) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import '" + libPath(libFile) + "'; " + body);
        return runFile(entry);
    }

    // needed for anything that dispatch()es a second core (see
    // Bl0jv2_TestRunner.runFile's own doc on why) - stdlib/net/http.bl0's
    // client+server demo in particular, since httpServe() blocks the whole
    // way through accepting a connection and has nothing else to run
    // against on a single core
    private static String run(Path dir, String libFile, String body, Consumer<Bl0jv2_jVM> configure) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import '" + libPath(libFile) + "'; " + body);
        return runFile(entry, configure);
    }

    // --- mathlib ---

    @Test
    void mathAbsMinMax(@TempDir Path dir) throws IOException {
        assertEquals("5|3|3|7", run(dir, "mathlib.bl0",
                "print Math.abs(-5) + '|' + Math.abs(3) + '|' + Math.min(3, 7) + '|' + Math.max(3, 7);"));
    }

    @Test
    void mathFloorCeilRound(@TempDir Path dir) throws IOException {
        assertEquals("2|-3|3|-2|3|2", run(dir, "mathlib.bl0",
                "print Math.floor(2.5) + '|' + Math.floor(-2.5) + '|' + Math.ceil(2.5) + '|' + Math.ceil(-2.5) + '|' + Math.round(2.5) + '|' + Math.round(2.4);"));
    }

    @Test
    void mathSqrtAndClamp(@TempDir Path dir) throws IOException {
        assertEquals("2.0|5|1|10", run(dir, "mathlib.bl0",
                "print Math.sqrt(4.0) + '|' + Math.clamp(5, 1, 10) + '|' + Math.clamp(-3, 1, 10) + '|' + Math.clamp(30, 1, 10);"));
    }

    @Test
    void mathToHex(@TempDir Path dir) throws IOException {
        assertEquals("ff|10|0", run(dir, "mathlib.bl0",
                "print Math.toHex(255) + '|' + Math.toHex(16) + '|' + Math.toHex(0);"));
    }

    // the actual point of toHex: a negative int's real 32-bit bit pattern,
    // not '-' plus a decimal magnitude - matches what 0xDEADBEEF itself
    // parses to (see Bl0jv2_ArithmeticTest's hex-literal tests)
    @Test
    void mathToHexOfANegativeIntShowsTheFullBitPatternNotASignedDecimal(@TempDir Path dir) throws IOException {
        assertEquals("ffffffff|deadbeef", run(dir, "mathlib.bl0",
                "print Math.toHex(-1) + '|' + Math.toHex(0xDEADBEEF);"));
    }

    // --- arrlib ---

    @Test
    void arrContainsAndIndexOf(@TempDir Path dir) throws IOException {
        assertEquals("true|1|-1", run(dir, "arrlib.bl0",
                "arr = [10, 20, 30]; print Arr.contains(arr, 20) + '|' + Arr.indexOf(arr, 20) + '|' + Arr.indexOf(arr, 99);"));
    }

    @Test
    void arrReverseAndSlice(@TempDir Path dir) throws IOException {
        assertEquals("[3, 2, 1]|[2, 3]", run(dir, "arrlib.bl0",
                "arr = [1, 2, 3]; print Arr.reverse(arr) + '|' + Arr.slice(arr, 1, 3);"));
    }

    @Test
    void arrJoin(@TempDir Path dir) throws IOException {
        assertEquals("1-2-3", run(dir, "arrlib.bl0", "print Arr.join([1, 2, 3], '-');"));
    }

    @Test
    void arrMapFilterReduceWithNamedFunctions(@TempDir Path dir) throws IOException {
        assertEquals("[2, 4, 6]|[2]|6", run(dir, "arrlib.bl0",
                "def double(x) { return x * 2; } " +
                "def isEven(x) { return x % 2 == 0; } " +
                "def add(a, b) { return a + b; } " +
                "arr = [1, 2, 3]; " +
                "print Arr.map(arr, double) + '|' + Arr.filter(arr, isEven) + '|' + Arr.reduce(arr, add, 0);"));
    }

    @Test
    void arrRemoveAtSwapsWithLastAndPops(@TempDir Path dir) throws IOException {
        assertEquals("[1, 4, 3]|[1, 2]", run(dir, "arrlib.bl0",
                "arr = [1, 2, 3, 4]; Arr.removeAt(arr, 1); print arr + '|'; " +
                "arr2 = [1, 2, 3, 4]; Arr.removeAt(arr2, 2); Arr.removeAt(arr2, 2); print arr2;"));
    }

    @Test
    void arrConcatPreservesOrderOfBothArrays(@TempDir Path dir) throws IOException {
        assertEquals("[1, 2, 3, 4]", run(dir, "arrlib.bl0", "print Arr.concat([1, 2], [3, 4]);"));
    }

    // --- str/* ---
    // split further than mathlib/arrlib: a single strlib.bl0 with all of
    // these functions together overflowed the per-program instruction
    // ceiling on its own when it was still 255 (it landed around 260 just
    // from the function bodies, before any user code), so each piece is
    // its own file and shares small dependencies (substr.bl0) via 'import'
    // instead of duplicating them. The ceiling has since been widened, but
    // the split is still a reasonable way to keep each import cheap.

    @Test
    void strTrim(@TempDir Path dir) throws IOException {
        assertEquals("hi", run(dir, "str/trim.bl0", "print Trim.trim('  hi  ');"));
    }

    @Test
    void strUpperLower(@TempDir Path dir) throws IOException {
        assertEquals("HELLO-world", run(dir, "str/case.bl0",
                "print Case.upper('Hello') + '-' + Case.lower('World');"));
    }

    @Test
    void strStartsEndsWith(@TempDir Path dir) throws IOException {
        assertEquals("true|true|false", run(dir, "str/affix.bl0",
                "print Affix.startsWith('hello', 'he') + '|' + Affix.endsWith('hello', 'lo') + '|' + Affix.startsWith('hi', 'hello');"));
    }

    @Test
    void strFind(@TempDir Path dir) throws IOException {
        assertEquals("6|-1", run(dir, "str/find.bl0",
                "print Find.find('hello world', 'world') + '|' + Find.find('hello world', 'xyz');"));
    }

    @Test
    void strSplit(@TempDir Path dir) throws IOException {
        assertEquals("[a, b, c]", run(dir, "str/split.bl0", "print Split.split('a,b,c', ',');"));
    }

    @Test
    void strChar(@TempDir Path dir) throws IOException {
        assertEquals("A|?", run(dir, "str/char.bl0", "print Char.char(65) + '|' + Char.char(200);"));
    }

    @Test
    void strToArr(@TempDir Path dir) throws IOException {
        assertEquals("[h, i]", run(dir, "str/toArr.bl0", "print ToArr.toArr('hi');"));
    }

    // --- net/* --- a toy IP/UDP stack over a virtual loopback NIC (see
    // stdlib/net/nic.bl0's own doc for why loopback, not a real socket).
    // initNic() must run before anything else in this group - same
    // "not automatically done at import time" rule kernel.bl0's own
    // initKeyboard()/initConsole() follow elsewhere in this project.
    // raiseInterrupt() only queues delivery for the next cooperative poll
    // (see its own doc), so every test below busy-waits a few iterations
    // after a send before checking the receive side - the loop is a safety
    // margin, not a real timing dependency (poll interval defaults to 5
    // instructions, and there's always far more than that between a send
    // and the following check).

    @Test
    void nicLoopbackRoundTrips(@TempDir Path dir) throws IOException {
        assertEquals("true|[1, 2, 3]|false", run(dir, "net/nic.bl0",
                "Nic.init(); " +
                "Nic.send([1, 2, 3]); " +
                "i = 0; while (i < 20 && !Nic.hasFrame()) { i = i + 1; } " +
                "print Nic.hasFrame() + '|' + Nic.recv() + '|' + Nic.hasFrame();"));
    }

    @Test
    void nicRecvIsFifoNotLifo(@TempDir Path dir) throws IOException {
        assertEquals("[1, 1]|[2, 2]", run(dir, "net/nic.bl0",
                "Nic.init(); " +
                "Nic.send([1, 1]); " +
                "i = 0; while (i < 20 && !Nic.hasFrame()) { i = i + 1; } " +
                "Nic.send([2, 2]); " +
                "i = 0; while (i < 20 && len(Nic.rxQueue) < 2) { i = i + 1; } " +
                "print Nic.recv() + '|' + Nic.recv();"));
    }

    @Test
    void ipChecksumIsZeroOverAnAlreadyCorrectHeaderAndNonZeroIfCorrupted(@TempDir Path dir) throws IOException {
        assertEquals("0|true", run(dir, "net/ip.bl0",
                "h = Ip.buildHeader(0x01020304, 0x05060708, Ip.protoUdp, 3); " +
                "before = Ip.checksum(h); " +
                "h[0] = 0xFF; " +
                "print before + '|' + (Ip.checksum(h) != 0);"));
    }

    @Test
    void ipParseHeaderRecoversWhatWasBuilt(@TempDir Path dir) throws IOException {
        assertEquals("16909060|84281096|17|3", run(dir, "net/ip.bl0",
                "h = Ip.buildHeader(0x01020304, 0x05060708, Ip.protoUdp, 3); " +
                "parsed = IpHeader.parse(h); " +
                "print parsed.srcAddr + '|' + parsed.dstAddr + '|' + parsed.proto + '|' + parsed.payloadLen;"));
    }

    @Test
    void udpSendAndReceiveRoundTripsThroughTheLoopbackNic(@TempDir Path dir) throws IOException {
        assertEquals("true|true|5000|6000|hello|false", run(dir, "net/udp.bl0",
                "Nic.init(); " +
                "srcIp = 0x0A000001; dstIp = 0x0A000002; " +
                "Udp.send(srcIp, 5000, dstIp, 6000, 'hello'); " +
                "i = 0; while (i < 20 && !Udp.hasPacket()) { i = i + 1; } " +
                "pkt = UdpPacket.receive(); " +
                "print (pkt.srcIp == srcIp) + '|' + (pkt.dstIp == dstIp) + '|' + pkt.srcPort + '|' + pkt.dstPort + '|' + pkt.message + '|' + Udp.hasPacket();"));
    }

    @Test
    void udpPreservesArrivalOrderAcrossMultiplePackets(@TempDir Path dir) throws IOException {
        assertEquals("first|second", run(dir, "net/udp.bl0",
                "Nic.init(); " +
                "Udp.send(1, 100, 2, 200, 'first'); " +
                "i = 0; while (i < 20 && !Udp.hasPacket()) { i = i + 1; } " +
                "Udp.send(1, 100, 2, 200, 'second'); " +
                "i = 0; while (i < 20 && len(Nic.rxQueue) < 2) { i = i + 1; } " +
                "p1 = UdpPacket.receive(); p2 = UdpPacket.receive(); " +
                "print p1.message + '|' + p2.message;"));
    }

    // UdpPacket.receive()/TcpSegment.parse() used to rebuild a payload
    // string with Char.char() (stdlib/str/char.bl0) - fine for a keyboard
    // byte, wrong here: Char.char() maps anything outside printable ASCII
    // to '?', silently eating \r/\n on the way through. Ip.netByteToChar()
    // is what both layers actually use now - this is the regression test
    // for that.
    @Test
    void udpPreservesCrlfInThePayload(@TempDir Path dir) throws IOException {
        assertEquals("line1\r\nline2", run(dir, "net/udp.bl0",
                "Nic.init(); " +
                "Udp.send(1, 100, 2, 200, 'line1\\r\\nline2'); " +
                "i = 0; while (i < 20 && !Udp.hasPacket()) { i = i + 1; } " +
                "print UdpPacket.receive().message;"));
    }

    // --- tcp.bl0 --- a real three-way handshake, real seq/ack numbers, a
    // real four-way close - see tcp.bl0's own header comment for exactly
    // what's simplified away (no retransmission, no MSS segmentation) and
    // why a lossless loopback link has no need for it. All of these run on
    // one core: tcpConnect()'s own internal pump loop services BOTH sides
    // (the registry it searches is shared, not per-connection - see
    // tcpPump()'s own doc), so nothing here needs dispatch().

    @Test
    void tcpConnectToNoListenerTimesOutWithNil(@TempDir Path dir) throws IOException {
        assertEquals("nil|CLOSED", run(dir, "net/tcp.bl0",
                "Nic.init(); " +
                "TcpConn.connectTimeoutMs = 50; " +
                "c = TcpConn.connect(0x0A000002, 5000, 0x0A000001, 9); " +
                "print str(c) + '|'; " +
                "print TcpRegistry.conns[0].state;"));
    }

    // --- tcp.bl0 retransmission --- Nic.dropNext = N silently discards the
    // next N transmitted frames (a lossy link); a short TcpConn.rtoMs keeps
    // these fast. All single-core: whoever is waiting pumps the shared
    // registry, which is also what retransmits.

    @Test
    void tcpLostSynIsRetransmittedAndTheHandshakeStillCompletes(@TempDir Path dir) throws IOException {
        assertEquals("ESTABLISHED|ESTABLISHED", run(dir, "net/tcp.bl0",
                "Nic.init(); TcpConn.rtoMs = 20; " +
                "server = TcpConn.listen(0x0A000001, 8080); " +
                "Nic.dropNext = 1; " +
                "client = TcpConn.connect(0x0A000002, 5000, 0x0A000001, 8080); " +
                "server.accept(); " +
                "print server.state + '|' + client.state;"));
    }

    @Test
    void tcpLostDataSegmentIsRetransmittedAndDeliveredExactlyOnce(@TempDir Path dir) throws IOException {
        assertEquals("true|hello|0", run(dir, "net/tcp.bl0",
                "Nic.init(); TcpConn.rtoMs = 20; " +
                "server = TcpConn.listen(0x0A000001, 8080); " +
                "client = TcpConn.connect(0x0A000002, 5000, 0x0A000001, 8080); " +
                "server.accept(); " +
                "Nic.dropNext = 1; client.send('hello'); " +
                "ok = server.waitData(3000); " +
                "got = server.receive(); " +
                "i = 0; while (i < 50 && len(client.unacked) > 0) { i = i + 1; client.waitData(10); } " +
                "print str(ok) + '|' + got + '|' + str(len(client.unacked));"));
    }

    @Test
    void tcpLostAckMakesTheSenderResendButTheReceiverDeduplicates(@TempDir Path dir) throws IOException {
        // the server's ACK for 'a' is lost, so the client resends 'a'; the
        // server must re-ack it without delivering it a second time
        assertEquals("a||0", run(dir, "net/tcp.bl0",
                "Nic.init(); TcpConn.rtoMs = 20; " +
                "server = TcpConn.listen(0x0A000001, 8080); " +
                "client = TcpConn.connect(0x0A000002, 5000, 0x0A000001, 8080); " +
                "server.accept(); " +
                "client.send('a'); " +
                "Nic.dropNext = 1; " +
                "server.waitData(1000); first = server.receive(); " +
                "i = 0; while (i < 60 && len(client.unacked) > 0) { i = i + 1; client.waitData(10); server.waitData(10); } " +
                "print first + '|' + server.receive() + '|' + str(len(client.unacked));"));
    }

    @Test
    void tcpGivesUpAfterMaxRetriesAndMarksTheConnectionClosed(@TempDir Path dir) throws IOException {
        assertEquals("CLOSED", run(dir, "net/tcp.bl0",
                "Nic.init(); TcpConn.rtoMs = 5; TcpConn.maxRetries = 2; " +
                "server = TcpConn.listen(0x0A000001, 8080); " +
                "client = TcpConn.connect(0x0A000002, 5000, 0x0A000001, 8080); " +
                "server.accept(); " +
                "Nic.dropNext = 1000; client.send('lost'); " +
                "i = 0; while (i < 200 && client.state != 'CLOSED') { i = i + 1; client.waitData(10); } " +
                "print client.state;"));
    }

    @Test
    void tcpHandshakeDataExchangeAndClose(@TempDir Path dir) throws IOException {
        assertEquals("ESTABLISHED|ESTABLISHED|GET / HTTP/1.0|HTTP/1.0 200 OK|CLOSED|CLOSED", run(dir, "net/tcp.bl0",
                "Nic.init(); " +
                "server = TcpConn.listen(0x0A000001, 8080); " +
                "client = TcpConn.connect(0x0A000002, 5000, 0x0A000001, 8080); " +
                "server.accept(); " +
                "print server.state + '|' + client.state + '|'; " +
                "client.send('GET / HTTP/1.0'); " +
                "i = 0; while (i < 40 && !server.hasData()) { i = i + 1; wait(1); } " +
                "print server.receive() + '|'; " +
                "server.send('HTTP/1.0 200 OK'); " +
                "i = 0; while (i < 40 && !client.hasData()) { i = i + 1; wait(1); } " +
                "print client.receive() + '|'; " +
                "client.close(); server.close(); " +
                "client.waitClosed(); server.waitClosed(); " +
                "print client.state + '|' + server.state;"));
    }

    // proves the seq/rcvNext bookkeeping is actually correct across MULTIPLE
    // sends on the same connection, not just a single one - the exact bug
    // this stack shipped with initially (SYN_SENT double-incremented
    // sndNext by one, so every byte sent after the handshake carried the
    // wrong sequence number and got silently dropped as "out of order")
    @Test
    void tcpDeliversMultipleSendsInOrder(@TempDir Path dir) throws IOException {
        assertEquals("onetwothree", run(dir, "net/tcp.bl0",
                "Nic.init(); " +
                "server = TcpConn.listen(1, 8080); " +
                "client = TcpConn.connect(2, 5000, 1, 8080); " +
                "server.accept(); " +
                "client.send('one'); " +
                "client.send('two'); " +
                "client.send('three'); " +
                "received = ''; parts = 0; " +
                "i = 0; while (parts < 3 && i < 60) { " +
                "  if (server.hasData()) { received = received + server.receive(); parts = parts + 1; } " +
                "  else { wait(1); } " +
                "  i = i + 1; " +
                "} " +
                "print received;"));
    }

    // --- http.bl0 --- one request per connection, GET only, framed by
    // connection close (see http.bl0's own doc on why that's still
    // "HTTP/1.0", not just text-over-TCP). httpServe() blocks the whole
    // way through accepting a connection, so - unlike every tcp.bl0 test
    // above - this genuinely needs a second core: dispatch()'d there via
    // vm.set_core_count(2), the same real-worker-thread mechanism
    // aeon-os/smp_boot.bl0 demonstrates. serverIp lives on a static field,
    // not a top-level variable, because serverTask is a plain 'def'
    // function - see kernel.bl0's own doc (elsewhere in this project) on
    // why a top-level variable wouldn't be visible there at all.
    // no SYN retransmission in this stack (see tcp.bl0's own doc - a
    // lossless loopback link has no need for it, PROVIDED both ends are
    // already up before anyone sends anything): a fixed wait() here would
    // be racing the server's own tcpListen() registering itself, and a
    // client SYN that arrives before that registration is just silently
    // dropped, forever, with nothing to retry it - so this polls
    // TcpRegistry.conns directly for the server's own LISTEN entry to
    // actually confirm it before httpGet() ever sends anything
    private static final String WAIT_FOR_LISTENER =
            "wi = 0; while (wi < 200 && len(TcpRegistry.conns) < 1) { wi = wi + 1; wait(1); } ";

    @Test
    void httpClientServerRoundTrip(@TempDir Path dir) throws IOException {
        assertEquals("you asked for /hello", run(dir, "net/http.bl0",
                "Nic.init(); " +
                "def class Config { static field serverIp; } " +
                "Config.serverIp = 0x0A000001; " +
                "def handler(path) { return 'you asked for ' + path; } " +
                "def serverTask(arg) { Http.serve(Config.serverIp, 8080, handler); } " +
                "dispatch(serverTask, 1, 0); " +
                WAIT_FOR_LISTENER +
                "response = Http.get(0x0A000002, 5000, Config.serverIp, 8080, '/hello'); " +
                "print HttpResponse.parse(response).body;",
                vm -> vm.set_core_count(2)));
    }

    @Test
    void httpResponseStatusLineIsParsedSeparatelyFromTheBody(@TempDir Path dir) throws IOException {
        assertEquals("HTTP/1.0 200 OK|you asked for /x", run(dir, "net/http.bl0",
                "Nic.init(); " +
                "def class Config { static field serverIp; } " +
                "Config.serverIp = 0x0A000001; " +
                "def handler(path) { return 'you asked for ' + path; } " +
                "def serverTask(arg) { Http.serve(Config.serverIp, 8080, handler); } " +
                "dispatch(serverTask, 1, 0); " +
                WAIT_FOR_LISTENER +
                "response = Http.get(0x0A000002, 5000, Config.serverIp, 8080, '/x'); " +
                "r = HttpResponse.parse(response); " +
                "print r.statusLine + '|' + r.body;",
                vm -> vm.set_core_count(2)));
    }

    // --- dns.bl0 --- pure wire-format logic (RFC1035 query building,
    // response parsing including name compression) - no network needed,
    // so these run the same everywhere regardless of whether the
    // environment actually has outbound internet access (see
    // UdpOutboundBridge's own doc: reaching a REAL resolver needs
    // --bridge-outbound, which these tests deliberately don't exercise).

    @Test
    void dnsBuildQueryEncodesTheHeaderAndQuestionCorrectly(@TempDir Path dir) throws IOException {
        // 12-byte header + 'a.bc' as [1]'a'[2]'bc'[0] (6 bytes) + QTYPE/QCLASS (4 bytes) = 22
        assertEquals("22|18,52|1,0|1,97,2,98,99,0", run(dir, "net/dns.bl0",
                "q = Dns.buildQuery('a.bc', 0x1234); " +
                "print len(q) + '|' + q[0] + ',' + q[1] + '|' + q[2] + ',' + q[3] + '|' " +
                "+ q[12] + ',' + q[13] + ',' + q[14] + ',' + q[15] + ',' + q[16] + ',' + q[17];"));
    }

    @Test
    void dnsParseResponseFollowsANameCompressionPointerToFindTheAnswer(@TempDir Path dir) throws IOException {
        // header(12) + question 'a.bc'(10) + one A answer whose own NAME is
        // a compression pointer (0xC0 0x0C) back to the question's own
        // qname at offset 12 - real authoritative servers do this
        // constantly (RFC1035 4.1.4) rather than repeating the name
        assertEquals("16909060", run(dir, "net/dns.bl0",
                "resp = [" +
                "0x12,0x34, 0x81,0x80, 0x00,0x01, 0x00,0x01, 0x00,0x00, 0x00,0x00, " +
                "1,97,2,98,99,0, 0x00,0x01,0x00,0x01, " +
                "0xC0,0x0C, 0x00,0x01, 0x00,0x01, 0x00,0x00,0x00,0x3C, 0x00,0x04, " +
                "1,2,3,4" +
                "]; " +
                "print Dns.parseResponse(resp);"));
    }

    @Test
    void dnsParseResponseSkipsAPrecedingCnameToFindTheARecord(@TempDir Path dir) throws IOException {
        // two answers: first a CNAME (type 5), then the A record - proves
        // Dns.parseResponse() doesn't just look at the FIRST answer, it
        // scans for the first type-1 one, since a CNAME's own RDATA isn't
        // an address at all
        assertEquals("84281096", run(dir, "net/dns.bl0",
                "resp = [" +
                "0x00,0x01, 0x81,0x80, 0x00,0x01, 0x00,0x02, 0x00,0x00, 0x00,0x00, " +
                "1,97,0, 0x00,0x01,0x00,0x01, " +
                // answer 1: CNAME, pointer name, rdlength=2 (a nonsense 2-byte payload - content doesn't matter, only its length, to correctly skip past it)
                "0xC0,0x0C, 0x00,0x05, 0x00,0x01, 0x00,0x00,0x00,0x3C, 0x00,0x02, 0xAA,0xBB, " +
                // answer 2: A record, pointer name, rdata = 5.6.7.8
                "0xC0,0x0C, 0x00,0x01, 0x00,0x01, 0x00,0x00,0x00,0x3C, 0x00,0x04, 5,6,7,8" +
                "]; " +
                "print Dns.parseResponse(resp);"));
    }

    @Test
    void dnsResolveReturnsZeroWhenNothingEverAnswers(@TempDir Path dir) throws IOException {
        // no --bridge-outbound here, so nothing is listening on the
        // DNS request ports at all - Dns.resolve() has to give up and
        // return the documented failure sentinel rather than hang forever
        assertEquals("0", run(dir, "net/dns.bl0", "print Dns.resolve('example.com');"));
    }
}
