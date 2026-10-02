package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** the shell as a language: status, lists, conditions, loops, patterns, substitution */
class ShellScriptTest {

    private static void command(AeonSession s, String line, String expected) throws Exception {
        int before = s.output().length();
        s.type(line + "\r");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(expected))
            Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(expected),
                "after '" + line + "' expected '" + expected + "' in:\n" + s.output().substring(before)
                        + "\nfinished=" + s.finished + " failure=" + s.failure);
    }

    @Test
    void theStatusSaysWhetherTheLastCommandWentWell(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "echo hi", "hi");
        command(s, "echo status $?", "status 0");
        command(s, "cat nosuchfile", "no such file");
        command(s, "echo status $?", "status 1");
        command(s, "nosuchcommand", "command not found");
        command(s, "echo status $?", "status 1");
        command(s, "echo ok", "ok");
        command(s, "echo status $?", "status 0");
    }

    @Test
    void commandsJoinedBySemicolonAndOrAndAnd(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "echo one; echo two", "two");
        command(s, "echo fine && echo then", "then");
        command(s, "cat nosuchfile && echo NOTSHOWN", "no such file");
        command(s, "cat nosuchfile || echo fallback", "fallback");
        command(s, "echo fine || echo NOTSHOWN2", "fine");
        command(s, "cat nosuchfile && echo A || echo B", "B");
        command(s, "echo 'a;b && c' > q.txt; cat q.txt", "a;b && c");        // inside quotes they are text
        command(s, "echo done", "done");
        assertTrue(!s.output().contains("\nNOTSHOWN"), s.output());
    }
}
