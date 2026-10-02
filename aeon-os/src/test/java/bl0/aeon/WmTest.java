package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

// the window manager (bin/wm), seen through a terminal emulator
class WmTest {

    private static final String UP = "\u001b[A", DOWN = "\u001b[B", RIGHT = "\u001b[C", LEFT = "\u001b[D";

    private static void until(AeonSession s, String what, Predicate<VirtualTerminal> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !ok.test(s.screen())) Thread.sleep(20);
        assertTrue(ok.test(s.screen()), what + " (finished=" + s.finished + " failure=" + s.failure + ")\nscreen:\n" + s.screen().screenText());
    }

    @Test
    void windowsShowFilesAndTheFocusMoves(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        s.type("wm\r");
        until(s, "the default windows", t -> t.screenText().contains("processes") && t.screenText().contains("dev/cpu")
                && t.screenText().contains("dev/time") && t.screenText().contains("core 0:"));
        assertTrue(s.screen().screenText().contains("┌"), s.screen().screenText());
        until(s, "the status line names the top window", t -> t.lastLine().contains("dev/time") && t.lastLine().contains("[move]"));
        s.type("\t");
        until(s, "the next window is on top", t -> t.lastLine().contains("processes"));
        s.type("r");
        until(s, "sizing", t -> t.lastLine().contains("[size]"));
        s.type(RIGHT + DOWN + "r" + LEFT);
        s.type("z");
        until(s, "maximised", t -> t.line(0).startsWith("┌") && t.line(22).startsWith("└"));
        s.type("z");
        s.type("x");
        until(s, "a window is closed", t -> !t.screenText().contains("processes") || !t.lastLine().contains("processes"));
        s.type("q");
        until(s, "back at the shell", t -> t.lastLine().equals("$") && !t.screenText().contains("┌"));
    }

    @Test
    void anyFileCanBeOpenedInAWindow(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        s.type("echo window text > note.txt\r");
        until(s, "prompt", t -> t.lastLine().equals("$"));
        s.type("wm\r");
        until(s, "up", t -> t.screenText().contains("processes"));
        s.type("n");
        s.type("note.txt\r");
        until(s, "the file in a window", t -> t.screenText().contains("window text") && t.lastLine().contains("note.txt"));
        s.type("n");
        s.type("net/tcp/clone\r");
        until(s, "a file that does something is not shown", t -> t.screenText().contains("not shown"));
        s.type("q");
        until(s, "back at the shell", t -> t.lastLine().equals("$"));
    }
}
