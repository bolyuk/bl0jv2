package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import bl0.bl0jv2.runtime.device.DisplayController.Frame;

import java.io.PrintStream;

/**
 * --display: draws the guest's text-mode display on the host terminal. A thread looks
 * at the frame buffer about forty times a second and sends the terminal only the
 * cells that changed, with their colours (the guest's 16-colour palette is the ANSI
 * one: 0-7 normal, 8-15 bright), and puts the terminal's cursor where the guest's is.
 * It uses the terminal's alternate screen, so the shell's own screen comes back when
 * the program ends.
 */
final class HostScreen implements AutoCloseable {
    private final Bl0jv2_jVM vm;
    private final PrintStream out;
    private final Thread thread;
    private volatile boolean running = true;
    private int[] shown;          // what the terminal currently shows, cell by cell (-1 = unknown)
    private int shownCursor = -1;
    private boolean shownVisible = true;

    HostScreen(Bl0jv2_jVM vm, PrintStream out) {
        this.vm = vm;
        this.out = out;
        out.print("\u001b[?1049h\u001b[2J\u001b[H");
        out.flush();
        this.thread = new Thread(this::loop, "host-screen");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                draw();
            } catch (RuntimeException e) {
                // a frame caught mid-update: draw again next time
                shown = null;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private synchronized void draw() {
        Frame f = vm.display_frame();
        if (f == null) return;
        if (shown == null || shown.length != f.cells().length) {
            shown = new int[f.cells().length];
            java.util.Arrays.fill(shown, -1);
            out.print("\u001b[2J");
        }
        StringBuilder sb = new StringBuilder();
        int lastAttr = -1;
        for (int r = 0; r < f.rows(); r++) {
            int c = 0;
            while (c < f.columns()) {
                int i = r * f.columns() + c;
                if (f.cells()[i] == shown[i]) { c++; continue; }
                // a run of changed cells: one cursor move, then the characters
                sb.append("\u001b[").append(r + 1).append(';').append(c + 1).append('H');
                while (c < f.columns() && f.cells()[r * f.columns() + c] != shown[r * f.columns() + c]) {
                    int idx = r * f.columns() + c;
                    int cell = f.cells()[idx];
                    int attr = cell >>> 24;
                    if (attr != lastAttr) {
                        sb.append(sgr(attr));
                        lastAttr = attr;
                    }
                    sb.appendCodePoint(f.codePoint(r, c));
                    shown[idx] = cell;
                    c++;
                }
            }
        }
        if (lastAttr >= 0) sb.append("\u001b[0m");
        int cursor = f.cursorRow() * f.columns() + f.cursorColumn();
        sb.append("\u001b[").append(f.cursorRow() + 1).append(';').append(f.cursorColumn() + 1).append('H');
        if (f.cursorVisible() != shownVisible) sb.append(f.cursorVisible() ? "\u001b[?25h" : "\u001b[?25l");
        shownVisible = f.cursorVisible();
        shownCursor = cursor;
        out.print(sb);
        out.flush();
    }

    private static String sgr(int attr) {
        int fg = attr & 0xF, bg = (attr >> 4) & 0xF;
        return "\u001b[" + (fg < 8 ? 30 + fg : 90 + fg - 8) + ";" + (bg < 8 ? 40 + bg : 100 + bg - 8) + "m";
    }

    @Override
    public void close() {
        running = false;
        draw();                                         // the last state of the screen
        out.print("\u001b[0m\u001b[?25h\u001b[?1049l");
        out.flush();
    }
}
