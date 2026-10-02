package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// a payload of any bytes goes through the network stack exactly (SSH and other binary protocols need it)
class Bl0jv2_NetBinaryTest {

    private static String run(Path dir, String code) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/net/tcp.bl0'; " + code);
        return Bl0jv2_TestRunner.runFile(entry);
    }

    @Test
    void everyByteSurvivesAStringAndBack(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir, "all = []; i = 0; while (i < 256) { push(all, i); i += 1; } " +
                "back = Ip.bytesOf(Ip.stringOf(all)); ok = len(back) == 256; i = 0; " +
                "while (i < 256) { if (back[i] != i) { ok = false; } i += 1; } print ok;"));
    }

    @Test
    void aTcpStreamCarriesAllBytes(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir, "Nic.init(); " +
                "server = TcpConn.listen(0x0A000001, 8080); client = TcpConn.connect(0x0A000002, 5000, 0x0A000001, 8080); server.accept(); " +
                "all = []; i = 0; while (i < 256) { push(all, i); i += 1; } " +
                "client.send(Ip.stringOf(all)); server.waitData(2000); got = Ip.bytesOf(server.receive()); " +
                "ok = len(got) == 256; i = 0; while (i < 256) { if (got[i] != i) { ok = false; } i += 1; } print ok;"));
    }

    @Test
    void aLongMessageGoesOutInSegmentsAndArrivesWhole(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir, "Nic.init(); " +
                "server = TcpConn.listen(0x0A000001, 8080); client = TcpConn.connect(0x0A000002, 5000, 0x0A000001, 8080); server.accept(); " +
                "all = []; i = 0; while (i < 5000) { push(all, (i * 7) & 255); i += 1; } " +
                "client.send(Ip.stringOf(all)); got = []; " +
                "while (len(got) < 5000 && server.waitData(2000)) { got = Arr.concat(got, Ip.bytesOf(server.receive())); } " +
                "ok = len(got) == 5000; i = 0; while (i < 5000) { if (got[i] != all[i]) { ok = false; } i += 1; } print ok;"));
    }
}
