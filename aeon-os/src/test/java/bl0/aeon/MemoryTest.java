package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** memory accounting and limits (lib/mem.bl0, lib/limits.bl0, etc/limits) */
class MemoryTest {

    private static void command(AeonSession s, String line, String expected) throws Exception {
        int before = s.output().length();
        s.type(line + "\r");
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(expected))
            Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(expected),
                "after '" + line + "' expected '" + expected + "' in:\n" + s.output().substring(before)
                        + "\nfinished=" + s.finished + " failure=" + s.failure);
    }

    @Test
    void freeAndMeminfoSayWhatIsHeld(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 2);
        command(s, "free", "raw memory");
        command(s, "free", "objects on the heap");
        command(s, "free", "system");
        command(s, "cat proc/meminfo", "heap_used:");
        command(s, "cat proc/meminfo", "raw_total: 1048576");
        command(s, "sleep 60 &", "[2] sleep");
        command(s, "free", "[2] sleep");                                  // what a running process holds
        command(s, "ps", "memory");
        command(s, "cat proc/2/status", "memory: ");
        command(s, "kill 2", "$");
    }

    @Test
    void limitsAreReadFromEtcLimitsPerUser(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "limits", "memory     no limit");                    // root
        command(s, "useradd alice", "added alice");
        command(s, "limits alice", "16.0 MB for each process");           // the * line
        command(s, "echo 'alice memory 512' >> /etc/limits", "$");
        command(s, "limits alice", "512 KB for each process");            // her own line wins
        command(s, "limits alice", "4 at once");
    }

    @Test
    void aProcessOverItsMemoryLimitFailsAndTheShellGoesOn(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "seq 1 20000 > /tmp/big.txt", "$");
        command(s, "chmod 644 /tmp/big.txt", "$");
        command(s, "useradd alice", "added alice");
        command(s, "echo 'alice memory 100' >> /etc/limits", "$");
        command(s, "su alice", "$");
        command(s, "sort /tmp/big.txt > /dev/null", "out of memory");           // the failure is reported on the terminal even when the output goes elsewhere
        command(s, "echo still here", "still here");
        command(s, "exit", "$");
        command(s, "sort /tmp/big.txt > /dev/null; echo root status $?", "root status 0");   // root has no limit
    }

    @Test
    void aUserMayRunOnlyTheNumberOfProcessesTheLimitSays(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "useradd alice", "added alice");
        command(s, "echo 'alice processes 1' >> /etc/limits", "$");
        command(s, "su alice", "$");
        command(s, "sleep 60 &", "[2] sleep");
        command(s, "sleep 60 &", "too many processes");
        command(s, "kill 2", "$");
        command(s, "exit", "$");
        command(s, "sleep 60 &", "[3] sleep");                            // root: no limit
        command(s, "sleep 60 &", "[4] sleep");
        command(s, "kill 3", "$");
        command(s, "kill 4", "$");
    }

    private static int heapUsed(AeonSession s) throws Exception {
        int before = s.output().length();
        s.type("cat proc/meminfo\r");
        long deadline = System.currentTimeMillis() + 20_000;
        java.util.regex.Matcher m = null;
        while (System.currentTimeMillis() < deadline) {
            m = java.util.regex.Pattern.compile("heap_used: (\\d+)").matcher(s.output().substring(before));
            if (m.find()) return Integer.parseInt(m.group(1));
            Thread.sleep(20);
        }
        throw new AssertionError("no heap_used in:\n" + s.output().substring(before));
    }

    @Test
    void whatEndedProcessesLeaveBehindIsCollected(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 2);
        command(s, "seq 1 20000 > /tmp/big.txt", "$");
        command(s, "sort /tmp/big.txt | tail -n 1", "9999");              // one run, to warm everything up
        int baseline = heapUsed(s);
        for (int i = 0; i < 6; i++) command(s, "sort /tmp/big.txt | tail -n 1", "9999");
        int after = heapUsed(s);
        // without a collector each run would leave about 2 MB behind
        assertTrue(after - baseline < 1_500_000, "the heap grew from " + baseline + " to " + after);
    }

    @Test
    void gcFreesWhatNothingReachesAndOnlyRootMayAskForIt(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 2);
        command(s, "gc", "objects freed");
        command(s, "useradd alice", "added alice");
        command(s, "su alice", "$");
        command(s, "gc", "only root may do that");
        command(s, "exit", "$");
        command(s, "free", "collections so far");
    }
}
