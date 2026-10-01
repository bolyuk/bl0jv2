package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the shell's line editor and UTF-8 console, seen through a terminal emulator
class LineEditTest {

    private static final String UP = "\u001b[A", DOWN = "\u001b[B", RIGHT = "\u001b[C", LEFT = "\u001b[D";
    private static final String HOME = "\u001b[H", END = "\u001b[F", DEL = "\u001b[3~";
    private static final String BACKSPACE = "\u007f", ENTER = "\r", TAB = "\t";
    private static String ctrl(char c) { return String.valueOf((char) (c - 64)); }

    private static AeonSession shell(Path dir) throws Exception {
        return AeonSession.shellOnOsDisk(dir);
    }

    /** waits until the screen's last line is 'expected' (the editor has finished drawing) */
    private static void expectLine(AeonSession s, String shown) throws Exception {
        String expected = shown.stripTrailing();          // a terminal cannot show trailing blanks
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !s.screen().lastLine().equals(expected)) Thread.sleep(20);
        assertEquals(expected, s.screen().lastLine(), "screen:\n" + s.screen().screenText());
    }

    private static void run(AeonSession s, String command, String expectedOutput) throws Exception {
        s.type(command + ENTER);
        assertTrue(s.waitFor(expectedOutput, 10_000), s.screen().screenText());
        expectLine(s, "$");
    }

    @Test
    void unicodeGoesInAndOutAsUtf8(@TempDir Path dir) throws Exception {
        var s = shell(dir);
        s.type("echo привет, мир € 😀");
        expectLine(s, "$ echo привет, мир € 😀");
        s.type(ENTER);
        assertTrue(s.waitFor("привет, мир € 😀\n", 10_000), s.output());
    }

    @Test
    void editingInTheMiddleOfALine(@TempDir Path dir) throws Exception {
        var s = shell(dir);
        s.type("echo helo");
        s.type(LEFT);
        s.type("l");
        expectLine(s, "$ echo hello");
        s.type(HOME + DEL + DEL + DEL + DEL + DEL);               // delete "echo "
        expectLine(s, "$ hello");
        s.type(END + "!" + LEFT + LEFT + BACKSPACE);              // delete the second l
        expectLine(s, "$ helo!");
        assertEquals(5, s.screen().cursorColumn());
    }

    @Test
    void killAndWordKeys(@TempDir Path dir) throws Exception {
        var s = shell(dir);
        s.type("echo one two three");
        s.type(ctrl('W'));
        expectLine(s, "$ echo one two ");
        s.type(ctrl('A') + RIGHT + RIGHT + RIGHT + RIGHT + RIGHT + ctrl('K'));
        expectLine(s, "$ echo ");
        s.type("again" + ctrl('U'));
        expectLine(s, "$");
        s.type("x" + ctrl('C'));
        assertTrue(s.waitFor("^C", 10_000), s.output());
        expectLine(s, "$");
    }

    @Test
    void historyWithUpAndDownKeepsTheLineBeingTyped(@TempDir Path dir) throws Exception {
        var s = shell(dir);
        run(s, "echo first", "first");
        run(s, "echo second", "second");
        s.type("draft");
        s.type(UP);
        expectLine(s, "$ echo second");
        s.type(UP);
        expectLine(s, "$ echo first");
        s.type(UP);
        expectLine(s, "$ echo first");           // the oldest stays
        s.type(DOWN + DOWN);
        expectLine(s, "$ draft");                // back to what was being typed
        s.type(UP + UP + ENTER);
        assertTrue(s.waitFor("first\n", 10_000), s.output());
    }

    @Test
    void tabCompletesCommandsAndPaths(@TempDir Path dir) throws Exception {
        var s = shell(dir);
        s.type("ec" + TAB);
        expectLine(s, "$ echo ");
        s.type(ctrl('U') + "hexd" + TAB);
        expectLine(s, "$ hexdump ");
        s.type(ctrl('U') + "mkdir projects" + ENTER);
        expectLine(s, "$");
        s.type("mkdir projects/docs" + ENTER);
        expectLine(s, "$");
        s.type("ls proj" + TAB);
        expectLine(s, "$ ls projects/");
        s.type("d" + TAB);
        expectLine(s, "$ ls projects/docs/");
        s.type(ctrl('U') + "c" + TAB);               // several commands start with c: nothing more to add
        expectLine(s, "$ c");
        s.type(TAB);                                 // the second Tab lists them
        assertTrue(s.waitFor("cat", 10_000) && s.screen().screenText().contains("cp"), s.screen().screenText());
        expectLine(s, "$ c");
    }

    @Test
    void aLineLongerThanTheScreenWrapsAndStillEdits(@TempDir Path dir) throws Exception {
        var s = shell(dir);
        String text = "echo " + "abcdefghij".repeat(10);          // 105 characters, 107 cells with the prompt: two rows
        String full = "$ " + text;
        s.type(text);
        Thread.sleep(300);
        assertEquals(full.substring(80), s.screen().lastLine());
        assertEquals(full.substring(0, 80), lastTwo(s)[0]);
        s.type(HOME + "X");                                        // an edit on the first row redraws both
        Thread.sleep(300);
        String edited = "$ X" + text;
        assertEquals(edited.substring(80), s.screen().lastLine());
        assertEquals(edited.substring(0, 80), lastTwo(s)[0]);
        assertEquals(3, s.screen().cursorColumn());
        s.type(END + BACKSPACE);                                   // and one on the second
        Thread.sleep(300);
        assertEquals(edited.substring(80, edited.length() - 1), s.screen().lastLine());
    }

    private static String[] lastTwo(AeonSession s) {
        String[] lines = s.screen().screenText().split("\n");
        return new String[]{lines[lines.length - 2], lines[lines.length - 1]};
    }

    @Test
    void historySurvivesARestart(@TempDir Path dir) throws Exception {
        var first = shell(dir);
        run(first, "echo remembered", "remembered");
        first.type("exit" + ENTER);
        first.thread.join(10_000);
        var second = AeonSession.shellOn(dir.resolve("d.img"), false);
        second.type(UP);
        expectLine(second, "$ exit");
        second.type(UP);
        expectLine(second, "$ echo remembered");
    }

    @Test
    void ctrlDOnAnEmptyLineEndsTheShell(@TempDir Path dir) throws Exception {
        var s = shell(dir);
        s.type(ctrl('D'));
        s.thread.join(10_000);
        assertTrue(s.finished && s.output().contains("aeon-shell exiting"), s.output());
    }

    // at a real line speed the transmit FIFO is full most of the time: the driver has to look at the
    // line status before writing, or characters would vanish
    @Test
    void theConsoleSurvivesALineSpeedSlowerThanTheGuest(@TempDir Path dir) throws Exception {
        var s = new AeonSession();
        s.start(AeonSession.compile("init.bl0"), 1, vm -> {
            try {
                vm.set_uart_baud(115_200);
                vm.attach_disk(AeonImage.os(dir.resolve("d.img")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(s.waitFor("aeon-shell ready", 30_000), s.output());
        s.type("echo привет, мир" + ENTER);
        assertTrue(s.waitFor("привет, мир\n", 20_000), s.output());
        s.type("ls bin | wc" + ENTER);
        assertTrue(s.waitFor("lines,", 20_000), s.output());
    }
}
