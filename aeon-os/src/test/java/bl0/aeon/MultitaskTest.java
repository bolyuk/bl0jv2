package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// several programs at once: background jobs, concurrent pipelines, stopping a program
class MultitaskTest {

    private static final String CTRL_C = "\u0003", ENTER = "\r";

    /** types a line and waits until the shell is back at its prompt; returns what it printed since */
    private static String run(AeonSession s, String line) throws Exception {
        int before = s.output().length();
        s.type(line + ENTER);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String now = s.output().substring(before);
            String prompt = now.substring(Math.max(0, now.lastIndexOf('\n') + 1));
            if (now.contains("\n") && prompt.endsWith("$ ")) return now;
            Thread.sleep(20);
        }
        throw new AssertionError("no prompt after '" + line + "':\n" + s.output().substring(before) + "\nfinished=" + s.finished + " failure=" + s.failure);
    }

    private static void expect(AeonSession s, String line, String expected) throws Exception {
        String out = run(s, line);
        assertTrue(out.contains(expected), "after '" + line + "' expected '" + expected + "' in:\n" + out);
    }

    private static void waitForOutput(AeonSession s, String text, long ms) throws Exception {
        assertTrue(s.waitFor(text, ms), "expected '" + text + "' in:\n" + s.output());
    }

    @Test
    void aBackgroundJobRunsWhileTheShellStaysUsable(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        long t0 = System.currentTimeMillis();
        expect(s, "sleep 2 &", "[2] sleep");
        assertTrue(System.currentTimeMillis() - t0 < 1500, "the prompt came back at once");
        String ps = run(s, "ps");
        assertTrue(ps.contains("running") && ps.contains("sleep"), ps);
        expect(s, "echo still here", "still here");                 // the shell works meanwhile
        waitForOutput(s, "[2] sleep done", 15_000);                   // reported at a later prompt
        s.type(ENTER);
        expect(s, "wait", "$ ");
        assertTrue(run(s, "ps").contains("done"), s.output());
    }

    @Test
    void killStopsABackgroundJob(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        expect(s, "sleep 60 &", "[2] sleep");
        expect(s, "kill 2", "$ ");
        waitForOutput(s, "[2] sleep killed", 15_000);
        s.type(ENTER);
        expect(s, "kill 2", "no such running process");
        expect(s, "kill 99", "no such running process");
    }

    @Test
    void aFailingBackgroundJobIsReported(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        expect(s, "cat nosuchfile.txt > out.txt &", "[2] cat");
        s.type(ENTER);
        expect(s, "wait", "$ ");
        String out = run(s, "ps");
        assertTrue(out.contains("done"), out);                          // cat reports a missing file itself and ends normally
    }

    @Test
    void aBackgroundJobWritesToAFile(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        expect(s, "ls bin > list.txt &", "[2] ls");
        expect(s, "wait", "$ ");
        expect(s, "wc list.txt", "lines,");
        expect(s, "grep sleep list.txt", "sleep.bl0c");
    }

    @Test
    void thereAreAsManyBackgroundJobsAsWorkerCores(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 3);                    // two workers
        expect(s, "sleep 30 &", "[2] sleep");
        expect(s, "sleep 30 &", "[3] sleep");
        expect(s, "sleep 30 &", "no free core");
        expect(s, "kill 2", "$ ");
        expect(s, "kill 3", "$ ");
        s.type(ENTER);
        expect(s, "wait", "$ ");
        expect(s, "sleep 1 &", "[4] sleep");                           // cores are free again
        expect(s, "wait", "$ ");
    }

    @Test
    void withOneCoreThereIsNoBackgroundButPipelinesStillRun(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 1);
        expect(s, "sleep 1 &", "no free core");
        expect(s, "echo hello world | wc", "1 lines, 2 words, 12 bytes");
    }

    @Test
    void aPipelineRunsItsStagesOnSeparateCores(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        expect(s, "ls bin | grep wc | wc", "1 lines, 2 words");           // wc.bl0c and wc line... counted
        String ps = run(s, "ps");
        // the first two stages ran as processes on worker cores, the last one on the shell's core
        assertTrue(ps.contains("ls") && ps.contains("grep") && ps.contains("done"), ps);
        assertFalse(ps.contains(" wc\n"), ps);
    }

    @Test
    void aProducerThatIsNeverReadToTheEndIsStoppedByBrokenPipe(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        String out = run(s, "yes hello | head -n 3");
        assertTrue(out.contains("hello\nhello\nhello"), out);
        String ps = run(s, "ps");
        assertTrue(ps.contains("failed") || ps.contains("done"), ps);       // yes ended, it is not still running
        assertFalse(ps.lines().anyMatch(l -> l.contains("running") && !l.contains("shell")), ps);
    }

    @Test
    void aSlowReaderMakesTheWriterWait(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        // thousands of lines through a bounded pipe to a reader that only counts: nothing is lost
        String out = run(s, "yes line | head -n 3000 | wc");
        assertTrue(out.contains("3000 lines"), out);
    }

    @Test
    void ctrlCStopsTheForegroundProgram(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 1);
        s.type("sleep 60" + ENTER);
        Thread.sleep(800);
        long t0 = System.currentTimeMillis();
        s.type(CTRL_C);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !s.screen().lastLine().equals("$")) Thread.sleep(20);
        assertTrue(s.screen().lastLine().equals("$"), s.screen().screenText());
        assertTrue(System.currentTimeMillis() - t0 < 5000);
        assertTrue(s.output().contains("^C"), s.output());
        expect(s, "echo alive", "alive");
    }

    @Test
    void ctrlCStopsAPipelineAndItsOtherStages(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        s.type("yes | wc" + ENTER);                                        // wc waits for the end of input: never comes
        Thread.sleep(1200);
        s.type(CTRL_C);
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !s.screen().lastLine().equals("$")) Thread.sleep(20);
        assertTrue(s.screen().lastLine().equals("$"), s.screen().screenText());
        String ps = run(s, "ps");
        assertFalse(ps.lines().anyMatch(l -> l.contains("running") && !l.contains("shell")), ps);                            // yes was stopped with it
    }

    @Test
    void twoJobsPrintingAtOnceDoNotCutEachOthersLines(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        expect(s, "echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa > a.txt", "$ ");
        expect(s, "echo bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb > b.txt", "$ ");
        expect(s, "cat a.txt &", "[2] cat");
        expect(s, "cat b.txt &", "[3] cat");
        expect(s, "wait", "$ ");
        String screen = s.screen().screenText();
        assertTrue(screen.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") && screen.contains("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), screen);
    }
}
