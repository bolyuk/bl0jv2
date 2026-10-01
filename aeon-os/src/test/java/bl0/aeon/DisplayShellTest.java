package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the shell and the editor with a text-mode display as the console: nothing is written to the serial
// line, the screen is whatever the guest's terminal emulator put in the frame buffer
class DisplayShellTest {

    private static final String UP = "\u001b[A", LEFT = "\u001b[D", HOME = "\u001b[H", ENTER = "\r";
    private static String ctrl(char c) { return String.valueOf((char) (c - 64)); }

    private static void until(AeonSession s, String what, Predicate<VirtualTerminal> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !ok.test(s.screen())) Thread.sleep(20);
        assertTrue(ok.test(s.screen()), what + "\\nscreen:\\n" + s.screen().screenText());
    }

    @Test
    void theShellDrawsItsPromptAndOutputOnTheScreenNotTheSerialLine(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnDisplay(dir);
        assertEquals("", s.output());                                    // nothing went out of the serial port
        until(s, "prompt", t -> t.lastLine().equals("$"));
        s.type("echo привет, мир" + ENTER);
        until(s, "output and a new prompt", t -> t.screenText().contains("\nпривет, мир\n$"));
        assertEquals("", s.output());
        s.type("ls bin | grep wc" + ENTER);
        until(s, "pipe output", t -> t.screenText().contains("wc.bl0c"));
    }

    @Test
    void lineEditingAndHistoryWorkOnTheScreen(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnDisplay(dir);
        s.type("echo helo" + LEFT + "l");
        until(s, "edited", t -> t.lastLine().equals("$ echo hello"));
        s.type(ENTER);
        until(s, "ran", t -> t.screenText().contains("\nhello\n$"));
        s.type(UP);
        until(s, "history", t -> t.lastLine().equals("$ echo hello"));
        s.type(ctrl('U') + "x".repeat(100));                              // wraps over two rows
        until(s, "wrapped", t -> t.lastLine().length() == 22);            // 102 cells on an 80-column screen
    }

    @Test
    void outputLongerThanTheScreenScrolls(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnDisplay(dir);
        for (int i = 0; i < 3; i++) {                                      // three listings of 18 lines: well over 24 rows
            s.type("ls bin" + ENTER);
            Thread.sleep(300);
        }
        until(s, "listed and back at the prompt", t -> t.lastLine().equals("$") && t.screenText().contains("wc.bl0c"));
        assertTrue(!s.screen().screenText().contains("aeon-shell ready"), s.screen().screenText());   // scrolled off
    }

    @Test
    void theEditorUsesTheAlternateScreenAndInverseVideo(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnDisplay(dir);
        s.type("echo kept > k.txt" + ENTER);
        until(s, "done", t -> t.lastLine().equals("$"));
        s.type("edit k.txt" + ENTER);
        until(s, "editor", t -> t.line(0).equals("kept") && t.line(22).startsWith("k.txt"));
        var frame = s.vm.display_frame();
        assertEquals(0, frame.foreground(22, 0));                         // the status bar is inverse: black on grey
        assertEquals(7, frame.background(22, 0));
        assertEquals(7, frame.foreground(0, 0));
        s.type("Z" + ctrl('S') + ctrl('X'));
        until(s, "back at the shell", t -> t.lastLine().equals("$") && t.screenText().contains("echo kept > k.txt"));
        s.type("cat k.txt" + ENTER);
        until(s, "saved text", t -> t.screenText().contains("Zkept"));
    }
}
