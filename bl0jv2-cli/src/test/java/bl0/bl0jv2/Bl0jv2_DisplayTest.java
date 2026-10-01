package bl0.bl0jv2;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import bl0.bl0jv2.runtime.device.DisplayController;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the text-mode display: cells in guest memory, a cursor, scroll and fill commands
class Bl0jv2_DisplayTest {

    // runs the program with a display attached and returns what the screen shows afterwards
    private static DisplayController.Frame run(String program) {
        AtomicReference<Bl0jv2_jVM> holder = new AtomicReference<>();
        Bl0jv2_TestRunner.run("reserve(20000, 80 * 24 * 4); " +
                "def cell(r, c, ch, attr) { poke32(20000 + (r * 80 + c) * 4, (attr << 24) | ch); } " +
                "out32(0x0F58, 20000); " + program, vm -> { vm.attach_display(); holder.set(vm); });
        return holder.get().display_frame();
    }

    @Test
    void noDisplayIsAttachedUntilTheHostSaysSo() {
        assertEquals("0|1", Bl0jv2_TestRunner.run("print str(in8(0x0F54)) + '|';", vm -> { }) + Bl0jv2_TestRunner.run("print in8(0x0F54);", vm -> vm.attach_display()));
        AtomicReference<Bl0jv2_jVM> h = new AtomicReference<>();
        Bl0jv2_TestRunner.run("", vm -> h.set(vm));
        assertNull(h.get().display_frame());
    }

    @Test
    void cellsInGuestMemoryAreWhatTheScreenShows() {
        var f = run("cell(0, 0, 72, 0x07); cell(0, 1, 0x438, 0x07); cell(0, 2, 0x1F600, 0x07); cell(2, 5, 33, 0x1F);");
        assertEquals(80, f.columns());
        assertEquals(24, f.rows());
        assertEquals("Hи😀", f.line(0));
        assertEquals("     !", f.line(2));
        assertEquals("Hи😀\n\n     !\n", f.text());
        assertEquals(7, f.foreground(0, 0));
        assertEquals(0, f.background(0, 0));
        assertEquals(0xF, f.foreground(2, 5));
        assertEquals(1, f.background(2, 5));
    }

    @Test
    void theCursorIsAPositionAndVisibleOrNot() {
        var f = run("out32(0x0F5C, 3 * 80 + 7); out8(0x0F60, 1);");
        assertEquals(3, f.cursorRow());
        assertEquals(7, f.cursorColumn());
        assertTrue(f.cursorVisible());
        assertEquals(false, run("out32(0x0F5C, 0); out8(0x0F60, 0);").cursorVisible());
    }

    @Test
    void scrollMovesTheRowsUpAndFillsTheBottom() {
        var f = run("cell(0, 0, 65, 7); cell(1, 0, 66, 7); cell(23, 0, 90, 7); " +
                "out32(0x0F6C, (0x1F << 24) | 46); out32(0x0F68, 1); out8(0x0F64, 1);");
        assertEquals("B", f.line(0));
        assertEquals("Z", f.line(22));
        assertEquals(".", f.line(23).substring(0, 1));        // the new row is filled with the cell given
        assertEquals(0xF, f.foreground(23, 0));
        assertEquals(1, f.background(23, 5));
        f = run("cell(5, 0, 65, 7); out32(0x0F6C, 0); out32(0x0F68, 3); out8(0x0F64, 1);");
        assertEquals("", f.line(5));
        assertEquals("A", run("cell(5, 0, 65, 7); out32(0x0F6C, 0); out32(0x0F68, 3); out8(0x0F64, 1); cell(2, 0, 65, 7);").line(2));
        // scrolling by more than the screen clears it
        assertEquals("", run("cell(5, 0, 65, 7); out32(0x0F6C, 0); out32(0x0F68, 99); out8(0x0F64, 1);").text());
    }

    @Test
    void fillClearsTheWholeFrame() {
        var f = run("cell(5, 5, 65, 7); out32(0x0F6C, (0x70 << 24) | 32); out8(0x0F64, 2);");
        assertEquals("", f.text());
        assertEquals(7, f.background(5, 5));
    }

    @Test
    void theGuestCanSwitchBetweenFrameBuffers() {
        var f = run("reserve(40000, 80 * 24 * 4); poke32(40000, 66); cell(0, 0, 65, 7); out32(0x0F58, 40000);");
        assertEquals("B", f.line(0));
        assertEquals("A", run("reserve(40000, 80 * 24 * 4); poke32(40000, 66); cell(0, 0, 65, 7);").line(0));
    }

    @Test
    void aFrameBufferOutsideMemoryShowsNothingInsteadOfFailing() {
        assertNull(run("out32(0x0F58, 0x7FFFFFF0);"));
    }
}
