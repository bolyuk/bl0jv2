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

    @Test
    void ifAndTestOnOneLineAndInAFile(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "echo data > f.txt", "$");
        command(s, "if test -f f.txt; then echo has file; else echo no file; fi", "has file");
        command(s, "if [ -d f.txt ]; then echo dir; else echo not dir; fi", "not dir");
        command(s, "if [ 3 -lt 10 ] && [ b = b ]; then echo numbers; fi", "numbers");
        command(s, "if [ ! -e nothing ]; then echo absent; fi", "absent");
        command(s, "if false; then echo A; elif true; then echo B; else echo C; fi", "B");
        command(s, "echo 'if [ \"$1\" = yes ]' > s.sh", "$");
        command(s, "echo 'then' >> s.sh", "$");
        command(s, "echo '  echo agreed' >> s.sh", "$");
        command(s, "echo 'else' >> s.sh", "$");
        command(s, "echo '  echo refused $#' >> s.sh", "$");
        command(s, "echo 'fi' >> s.sh", "$");
        command(s, "sh s.sh yes", "agreed");
        command(s, "sh s.sh no", "refused 1");
        command(s, "sh s.sh", "refused 0");
    }

    @Test
    void whileForBreakContinueAndExit(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "for x in a b c; do echo item $x; done", "item c");
        command(s, "for x in 1 2 3 4; do if [ $x = 2 ]; then continue; fi; if [ $x = 4 ]; then break; fi; echo n$x; done; echo after", "after");
        command(s, "i=x; while [ $i != xxx ]; do echo round $i; i=${i}x; done", "round xx");
        command(s, "echo 'for f in 1 2 3; do' > l.sh", "$");
        command(s, "echo '  echo L$f' >> l.sh", "$");
        command(s, "echo '  if [ $f = 2 ]; then exit 7; fi' >> l.sh", "$");
        command(s, "echo 'done' >> l.sh", "$");
        command(s, "echo 'echo NEVER' >> l.sh", "$");
        command(s, "sh l.sh; echo exited $?", "exited 1");
        assertTrue(s.output().contains("L2") && !s.output().contains("\nL3") && !s.output().contains("\nNEVER"), s.output());
        command(s, "if true; then", "fi is missing");        // an unfinished block is refused
        command(s, "fi", "unexpected fi");
    }

    @Test
    void ctrlCStopsALoop(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "while true; do sleep 1; done", "while");      // the echo of the line
        Thread.sleep(500);
        s.type("\u0003");
        command(s, "echo back", "back");
        assertTrue(s.output().contains("^C"), s.output());
        command(s, "while true; do echo x > /dev/null; done", "while");
        Thread.sleep(500);
        s.type("\u0003");
        command(s, "echo back2", "back2");
    }

    @Test
    void patternsStandForTheNamesThatFit(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "mkdir w", "$");
        command(s, "cd w", "$");
        command(s, "echo a > one.txt; echo b > two.txt; echo c > three.log; echo d > .hidden.txt", "$");
        command(s, "echo *.txt", "one.txt two.txt");
        command(s, "echo t*", "three.log two.txt");
        command(s, "echo ???.txt", "one.txt two.txt");
        command(s, "echo *.zzz", "*.zzz");                              // nothing fits: the word stays
        command(s, "echo '*.txt'", "*.txt");                            // quoted: no pattern
        command(s, "echo .h*", ".hidden.txt");
        command(s, "cd ..", "$");
        command(s, "echo w/*.log", "w/three.log");
        command(s, "cd w", "$");
        command(s, "cat *.txt", "b");
        command(s, "for f in *.txt; do echo file $f; done", "file two.txt");
        command(s, "rm *.txt; ls", "three.log");
    }

    @Test
    void outputAndSumsGoIntoTheLine(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "echo today is $(echo fine)", "today is fine");
        command(s, "x=$(echo a; echo b)", "$");
        command(s, "echo [$x]", "[a b]");
        command(s, "echo $(echo $(echo nested))", "nested");
        command(s, "echo one two three > w.txt", "$");
        command(s, "echo words: $(cat w.txt | wc)", "1 lines, 3 words");
        command(s, "n=5; echo $((n * 2 + 1))", "11");
        command(s, "i=0; while [ $i -lt 3 ]; do echo round $i; i=$((i + 1)); done", "round 2");
        command(s, "echo $((7 / 0))", "division by zero");
        command(s, "echo $(nosuchcommand)", "command not found");
        command(s, "ls tmp", "$");
        assertTrue(!s.output().contains(" sub"), s.output());                 // nothing is left behind
    }

    @Test
    void readTakesAVariableFromTheKeyboard(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        s.type("read -p 'name? ' who\r");
        Thread.sleep(200);
        s.type("Ada\r");
        command(s, "echo hello $who", "hello Ada");
    }
}
