package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// quoting, pipes and redirection in the shell
class ShellPipeTest {

    /** types a line and waits until the shell is back at its prompt; returns what that command printed */
    private static String run(AeonSession s, String line) throws Exception {
        int before = s.output().length();
        s.type(line + "\r");
        long deadline = System.currentTimeMillis() + 20_000;
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

    @Test
    void aPipeCarriesOneCommandsOutputToTheNext(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        expect(s, "echo hello world | wc", "1 lines, 2 words, 12 bytes");
        expect(s, "write t.txt \"one\\ntwo apple\\nthree apple\"", "wrote");
        expect(s, "cat t.txt | grep apple | wc", "2 lines, 4 words, 22 bytes");
        expect(s, "cat t.txt | head -n 2 | tail -n 1", "two apple");
        expect(s, "ls bin | grep wc", "wc.bl0c");
        expect(s, "echo \"a  b\" | hexdump", "61 20 20 62 0a");
    }

    @Test
    void redirectionWritesAndAppendsAndReads(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        run(s, "echo \"keeps  spaces\" > f.txt");
        expect(s, "cat f.txt", "keeps  spaces");
        run(s, "echo second >> f.txt");
        expect(s, "wc < f.txt", "2 lines, 3 words, 21 bytes");
        run(s, "echo replaced > f.txt");
        String out = run(s, "cat f.txt");
        assertTrue(out.contains("replaced") && !out.contains("second"), out);
        run(s, "cat f.txt | grep repl > g.txt");
        expect(s, "cat g.txt", "replaced");
        expect(s, "grep repl < f.txt", "replaced");
    }

    @Test
    void messagesAboutTheCommandStayOnTheTerminalNotInThePipe(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        String out = run(s, "cat nofile | wc");
        assertTrue(out.contains("nofile: no such file"), out);
        assertTrue(out.contains("0 lines, 0 words, 0 bytes"), out);
        out = run(s, "cat nofile > out.txt");
        assertTrue(out.contains("nofile: no such file"), out);
        expect(s, "wc out.txt", "0 lines, 0 words, 0 bytes");
        expect(s, "cat < missing", "missing: no such file");
    }

    @Test
    void syntaxErrorsAreReportedAndTheShellCarriesOn(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        expect(s, "echo |", "syntax error: nothing after |");
        expect(s, "echo \"oops", "syntax error: unterminated quote");
        expect(s, "> f", "syntax error: a redirection needs a command");
        expect(s, "whoami", "user");
    }

    @Test
    void thePipesTemporaryFilesAreRemoved(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        run(s, "echo x | cat | cat | wc");
        expect(s, "ls var", "log/");
        assertFalse(run(s, "ls var").contains("tmp/"), s.output());
        run(s, "cat nofile | cat");                 // a failing stage cleans up too
        assertFalse(run(s, "ls var").contains("tmp/"), s.output());
    }

    @Test
    void aProgramReadsTheKeyboardWhenNothingIsRedirected(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        s.type("cat > typed.txt\r");
        Thread.sleep(500);
        s.type("first línea\r");
        s.type("second\r");
        Thread.sleep(300);
        s.type("\u0004");                                // Ctrl-D ends the input
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.screen().lastLine().equals("$")) Thread.sleep(20);
        String out = run(s, "cat typed.txt");
        assertTrue(out.contains("first línea\nsecond"), out);
        expect(s, "wc typed.txt", "2 lines, 3 words");
    }

    @Test
    void builtinsRedirectToo(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        run(s, "pwd > where.txt");
        expect(s, "cat where.txt", "/");
        run(s, "help | grep network");
        expect(s, "help | grep network | wc", "1 lines");
    }
}
