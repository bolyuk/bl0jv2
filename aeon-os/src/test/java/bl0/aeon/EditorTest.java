package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.function.Predicate;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the full-screen editor (bin/edit), seen through a terminal emulator
class EditorTest {

    private static final String UP = "\u001b[A", DOWN = "\u001b[B", RIGHT = "\u001b[C", LEFT = "\u001b[D";
    private static final String HOME = "\u001b[H", END = "\u001b[F", PGDN = "\u001b[6~", DEL = "\u001b[3~";
    private static final String BACKSPACE = "\u007f", ENTER = "\r";
    private static String ctrl(char c) { return String.valueOf((char) (c - 64)); }

    private static void until(AeonSession s, String what, Predicate<VirtualTerminal> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !ok.test(s.screen())) Thread.sleep(20);
        assertTrue(ok.test(s.screen()), what + " (finished=" + s.finished + " failure=" + s.failure + ")\nscreen:\n" + s.screen().screenText());
    }

    private static void shellLine(AeonSession s, String command, String expected) throws Exception {
        int before = s.output().length();
        s.type(command + ENTER);
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(expected)) Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(expected), s.screen().screenText());
        until(s, "prompt back", t -> t.lastLine().equals("$"));
    }

    private static void openEditor(AeonSession s, String file) throws Exception {
        s.type("edit " + file + ENTER);
        until(s, "the editor is up", t -> t.line(22).contains(file));
    }

    @Test
    void typingSavingAndLeaving(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        openEditor(s, "f.txt");
        s.type("hello" + ENTER + "wörld");
        until(s, "text shown", t -> t.line(0).equals("hello") && t.line(1).equals("wörld"));
        var t = s.screen();
        assertEquals("~", t.line(2));                                  // rows past the end of the file
        assertTrue(t.line(22).startsWith("f.txt [modified]") && t.line(22).endsWith("2:6"), t.line(22));
        assertEquals(1, t.cursorRow());
        assertEquals(5, t.cursorColumn());
        s.type(ctrl('S'));
        until(s, "saved", x -> x.line(23).equals("saved 2 lines") && !x.line(22).contains("modified"));
        s.type(ctrl('X'));
        until(s, "back at the shell", x -> x.lastLine().equals("$") && !x.screenText().contains("[modified]"));
        assertTrue(!s.screen().screenText().contains("~"), s.screen().screenText());   // the alternate screen is gone
        shellLine(s, "cat f.txt", "wörld");
        shellLine(s, "wc f.txt", "2 lines, 2 words, 13 bytes");
    }

    @Test
    void editingAnExistingFile(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        shellLine(s, "write t.txt \"alpha\\nbeta\\ngamma\"", "wrote");
        openEditor(s, "t.txt");
        until(s, "loaded", t -> t.line(0).equals("alpha") && t.line(2).equals("gamma"));
        s.type(DOWN + END + "!");                                      // beta!
        s.type(DOWN + HOME + BACKSPACE);                               // joins gamma onto beta!
        until(s, "joined", t -> t.line(1).equals("beta!gamma") && t.line(2).equals("~"));
        s.type(UP + HOME + DEL + DEL + "A");                           // "alpha" -> "Apha"
        until(s, "edited", t -> t.line(0).equals("Apha"));
        s.type(ctrl('K'));                                             // cut the first line
        until(s, "cut", t -> t.line(0).equals("beta!gamma") && t.line(23).equals("line cut"));
        s.type(DOWN + ctrl('Y'));                                      // paste above the (empty) last position
        s.type(ctrl('S') + ctrl('X'));
        until(s, "left", t -> t.lastLine().equals("$"));
        shellLine(s, "cat t.txt", "beta!gamma");
        assertTrue(s.output().contains("Apha"), s.output());
    }

    @Test
    void leavingWithUnsavedChangesNeedsConfirmation(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        shellLine(s, "echo original > keep.txt", "$");
        openEditor(s, "keep.txt");
        s.type("X");
        s.type(ctrl('X'));
        until(s, "warned", t -> t.line(23).startsWith("unsaved changes"));
        s.type("Y");                                                   // any other key cancels the warning
        s.type(ctrl('X'));
        s.type(ctrl('X'));                                             // the second one leaves
        until(s, "left", t -> t.lastLine().equals("$"));
        shellLine(s, "cat keep.txt", "original");
        assertTrue(!s.output().substring(s.output().lastIndexOf("cat keep.txt")).contains("XY"), s.output());
    }

    @Test
    void searchJumpsToTheNextMatchAndWraps(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        shellLine(s, "write s.txt \"one needle\\ntwo\\nthree needle\"", "wrote");
        openEditor(s, "s.txt");
        s.type(ctrl('F') + "needle" + ENTER);
        until(s, "first match", t -> t.cursorRow() == 0 && t.cursorColumn() == 4);
        s.type(ctrl('F') + "needle" + ENTER);
        until(s, "second match", t -> t.cursorRow() == 2 && t.cursorColumn() == 6);
        s.type(ctrl('F') + "needle" + ENTER);
        until(s, "wrapped around", t -> t.cursorRow() == 0);
        s.type(ctrl('F') + "zzz" + ENTER);
        until(s, "not found", t -> t.line(23).equals("zzz: not found"));
        s.type(ctrl('X'));
        until(s, "left", t -> t.lastLine().equals("$"));
    }

    @Test
    void aLongFileScrollsAndALongLineScrollsSideways(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        openEditor(s, "long.txt");
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 40; i++) text.append("line ").append(i).append(ENTER);
        s.type(text.toString());
        until(s, "typed to the end", t -> t.line(22).endsWith("41:1"));
        until(s, "last lines visible", t -> t.screenText().contains("line 40"));
        assertTrue(!s.screen().screenText().contains("line 1\n"), s.screen().screenText());   // the top has scrolled away
        s.type(ctrl('S'));
        s.type("x".repeat(100));                                       // wider than the screen
        until(s, "sideways", t -> t.line(21).length() <= 80 && t.line(21).endsWith("x"));
        s.type(HOME);
        until(s, "back to the left", t -> t.line(21).startsWith("x"));
        s.type(ctrl('X') + ctrl('X'));
        until(s, "left", t -> t.lastLine().equals("$"));
    }

    @Test
    void thePageKeysMoveByScreens(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        StringBuilder script = new StringBuilder("write p.txt \"");
        for (int i = 1; i <= 60; i++) script.append("n").append(i).append(i < 60 ? "\\n" : "");
        shellLine(s, script + "\"", "wrote");
        openEditor(s, "p.txt");
        s.type(PGDN);
        until(s, "a page down", t -> t.screenText().contains("n23") && !t.screenText().startsWith("n1\n"));
        s.type(ctrl('X'));
        until(s, "left", t -> t.lastLine().equals("$"));
    }

    @Test
    void tabsBecomeSpacesAndUnicodeIsKept(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        openEditor(s, "u.txt");
        s.type("\tпривет 😀");
        until(s, "typed", t -> t.line(0).contains("привет"));
        s.type(ctrl('S') + ctrl('X'));
        until(s, "left", t -> t.lastLine().equals("$"));
        shellLine(s, "cat u.txt", "привет 😀");
    }
}
