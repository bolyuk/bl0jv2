package bl0.aeon;

import bl0.bl0jv2.runtime.device.DisplayController.Frame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the terminal emulator in lib/drivers.bl0: ANSI text into the display's cells
class TtyTest {

    /** runs consoleWrite() calls (each argument a bl0 expression) on a 20x6 display */
    private static Frame screen(String... writes) throws Exception {
        StringBuilder src = new StringBuilder("import 'lib/drivers.bl0'; initHeap(4); initConsole(); e = strChar(27); ");
        for (String w : writes) src.append("consoleWrite(").append(w).append("); ");
        return AeonSession.runOnDisplay(src.toString(), 20, 6);
    }

    @Test
    void plainTextCrLfAndTheCursor() throws Exception {
        Frame f = screen("'hello\\nworld'");
        assertEquals("hello", f.line(0));
        assertEquals("world", f.line(1));
        assertEquals(1, f.cursorRow());
        assertEquals(5, f.cursorColumn());
        assertTrue(f.cursorVisible());
        f = screen("'ab\\rX'");
        assertEquals("Xb", f.line(0));
    }

    @Test
    void unicodeIncludingCharactersBeyondTheBasicPlane() throws Exception {
        Frame f = screen("'привет €'", "strChar(0x1F600)");
        assertEquals("привет €😀", f.line(0));
        assertEquals(9, f.cursorColumn());
    }

    @Test
    void aLongLineWrapsAndTheWrapIsDeferredUntilTheNextCharacter() throws Exception {
        Frame f = screen("'12345678901234567890'");              // exactly 20 columns
        assertEquals("12345678901234567890", f.line(0));
        assertEquals("", f.line(1));
        assertEquals(0, f.cursorRow());
        assertEquals(19, f.cursorColumn());                      // still on the last cell, wrap pending
        f = screen("'12345678901234567890X'");
        assertEquals("X", f.line(1));
        assertEquals(1, f.cursorRow());
        assertEquals(1, f.cursorColumn());
        f = screen("'12345678901234567890\\nY'");                 // a line feed after a full row does not leave a blank row
        assertEquals("Y", f.line(1));
    }

    @Test
    void theScreenScrollsAtTheBottom() throws Exception {
        Frame f = screen("'a\\nb\\nc\\nd\\ne\\nf\\ng\\nh'");
        assertEquals("c", f.line(0));
        assertEquals("h", f.line(5));
        assertEquals(5, f.cursorRow());
    }

    @Test
    void backspaceAndTab() throws Exception {
        Frame f = screen("'abc' + strChar(8) + 'X'");
        assertEquals("abX", f.line(0));
        f = screen("'a\\tb'");
        assertEquals("a       b", f.line(0));
    }

    @Test
    void cursorMovementAndPositioning() throws Exception {
        Frame f = screen("'abcdef' + e + '[3D' + 'X'");                       // back 3
        assertEquals("abcXef", f.line(0));
        f = screen("'one\\ntwo' + e + '[2A' + e + '[2C' + 'Z'");              // up 2 (clamped to row 0), forward 2
        assertEquals("oneZ", f.line(0).replace(" ", ""));
        f = screen("e + '[3;5H' + '*'");                                      // row 3, column 5
        assertEquals("    *", f.line(2));
        f = screen("e + '[H' + 'A' + e + '[4B' + 'B'");
        assertEquals("A", f.line(0));
        assertEquals(" B", f.line(4));
        f = screen("'abcdef' + e + '[1G' + 'Q'");
        assertEquals("Qbcdef", f.line(0));
    }

    @Test
    void eraseInLineAndInDisplay() throws Exception {
        Frame f = screen("'abcdef' + e + '[3D' + e + '[K'");                 // from the cursor to the end
        assertEquals("abc", f.line(0));
        f = screen("'abcdef' + e + '[3D' + e + '[1K'");                       // from the start to the cursor
        assertEquals("    ef", f.line(0));
        f = screen("'abcdef' + e + '[2K'");
        assertEquals("", f.line(0));
        f = screen("'one\\ntwo\\nthree' + e + '[2;2H' + e + '[J'");           // cursor to the end of the screen
        assertEquals("one", f.line(0));
        assertEquals("t", f.line(1));
        assertEquals("", f.line(2));
        f = screen("'one\\ntwo' + e + '[2J'");
        assertEquals("", f.text());
    }

    @Test
    void coloursAndAttributes() throws Exception {
        Frame f = screen("e + '[31mR' + e + '[44mB' + e + '[0mN' + e + '[1;32mG' + e + '[7mI' + e + '[27;91;102mH'");
        assertEquals(1, f.foreground(0, 0));                                  // R: red on black
        assertEquals(0, f.background(0, 0));
        assertEquals(4, f.background(0, 1));                                  // B: red on blue
        assertEquals(7, f.foreground(0, 2));                                  // N: back to grey on black
        assertEquals(0, f.background(0, 2));
        assertEquals(10, f.foreground(0, 3));                                 // G: bold green = bright green
        assertEquals(0, f.background(0, 3));
        assertEquals(0, f.foreground(0, 4));                                  // I: inverse swaps bright green and black
        assertEquals(10, f.background(0, 4));
        assertEquals(9, f.foreground(0, 5));                                  // H: bright red on bright green
        assertEquals(10, f.background(0, 5));
    }

    @Test
    void anEraseUsesTheCurrentBackground() throws Exception {
        Frame f = screen("e + '[44m' + e + '[2J'");
        assertEquals(4, f.background(3, 7));
    }

    @Test
    void theCursorCanBeHiddenAndShown() throws Exception {
        assertFalse(screen("e + '[?25l'").cursorVisible());
        assertTrue(screen("e + '[?25l' + e + '[?25h'").cursorVisible());
    }

    @Test
    void theAlternateScreenKeepsTheNormalOneIntact() throws Exception {
        Frame f = screen("'shell text'", "e + '[?1049h' + 'editor'");
        assertEquals("editor", f.line(0));
        assertEquals("", f.line(1));
        f = screen("'shell text'", "e + '[?1049h' + 'editor'", "e + '[?1049l'");
        assertEquals("shell text", f.line(0));
        assertEquals(10, f.cursorColumn());                                   // the cursor is where it was
    }

    @Test
    void anEscapeSequenceMayBeSplitOverSeveralWrites() throws Exception {
        Frame f = screen("'abc' + e + '[2'", "'D' + 'X'");
        assertEquals("aXc", f.line(0));
    }

    @Test
    void unknownSequencesAreSwallowedWithoutDamage() throws Exception {
        Frame f = screen("'a' + e + '[5;1;99z' + 'b' + e + 'c' + 'c'");
        assertEquals("abc", f.line(0));
    }
}
