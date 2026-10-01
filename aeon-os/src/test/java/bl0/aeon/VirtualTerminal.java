package bl0.aeon;

import java.util.ArrayList;
import java.util.List;

/**
 * A small terminal emulator for the tests: feed it what the OS wrote to its
 * console (UTF-8 text and ANSI sequences) and read back what a screen would
 * show. It handles what a line editor and a full-screen editor use: printable
 * characters with xterm-style deferred wrapping, CR, LF (as CR+LF, the way a
 * terminal in the usual output mode does), backspace, and the CSI sequences
 * A B C D (cursor), H (position), J and K (erase), m (ignored). The screen
 * scrolls when the cursor passes the last row.
 */
final class VirtualTerminal {
    private final int columns;
    private final int rows;
    private final List<StringBuilder> screen = new ArrayList<>();
    private int row;
    private int col;
    private boolean pendingWrap;

    VirtualTerminal(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        for (int i = 0; i < rows; i++) screen.add(new StringBuilder());
    }

    static VirtualTerminal render(String output) {
        return render(output, 80, 24);
    }

    static VirtualTerminal render(String output, int columns, int rows) {
        VirtualTerminal t = new VirtualTerminal(columns, rows);
        t.feed(output);
        return t;
    }

    void feed(String text) {
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 27) {
                if (i < text.length() && text.charAt(i) == '[') {
                    i++;
                    StringBuilder params = new StringBuilder();
                    while (i < text.length() && (text.charAt(i) < 0x40 || text.charAt(i) > 0x7E)) params.append(text.charAt(i++));
                    if (i < text.length()) csi(text.charAt(i++), params.toString());
                }
                continue;
            }
            switch (cp) {
                case '\r' -> { col = 0; pendingWrap = false; }
                case '\n' -> { col = 0; lineFeed(); pendingWrap = false; }
                case '\b' -> { if (col > 0) col--; pendingWrap = false; }
                default -> { if (cp >= 32) put(cp); }
            }
        }
    }

    private void put(int cp) {
        if (pendingWrap) { col = 0; lineFeed(); pendingWrap = false; }
        StringBuilder line = screen.get(row);
        while (line.length() < col) line.append(' ');
        if (col < line.length()) line.replace(col, col + 1, new String(Character.toChars(cp)));
        else line.append(Character.toChars(cp));
        if (col == columns - 1) pendingWrap = true; else col++;
    }

    private void lineFeed() {
        if (row == rows - 1) {
            screen.remove(0);
            screen.add(new StringBuilder());
        } else {
            row++;
        }
    }

    private void csi(char fin, String params) {
        String[] parts = params.isEmpty() ? new String[0] : params.split(";", -1);
        int n = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : -1;
        pendingWrap = false;
        switch (fin) {
            case 'A' -> row = Math.max(0, row - Math.max(n, 1));
            case 'B' -> row = Math.min(rows - 1, row + Math.max(n, 1));
            case 'C' -> col = Math.min(columns - 1, col + Math.max(n, 1));
            case 'D' -> col = Math.max(0, col - Math.max(n, 1));
            case 'H' -> {
                int r = n < 1 ? 1 : n;
                int c = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 1;
                row = Math.min(rows, r) - 1;
                col = Math.min(columns, c) - 1;
            }
            case 'K' -> {
                StringBuilder line = screen.get(row);
                int mode = Math.max(n, 0);
                if (mode == 0 && col < line.length()) line.setLength(col);
                else if (mode == 2) line.setLength(0);
            }
            case 'J' -> {
                int mode = Math.max(n, 0);
                if (mode == 0) {
                    StringBuilder line = screen.get(row);
                    if (col < line.length()) line.setLength(col);
                    for (int r = row + 1; r < rows; r++) screen.get(r).setLength(0);
                } else if (mode == 2) {
                    for (StringBuilder l : screen) l.setLength(0);
                }
            }
            default -> { } // m and the rest: no effect on the text
        }
    }

    /** the text of screen row r (0-based), without trailing blanks */
    String line(int r) {
        return screen.get(r).toString().stripTrailing();
    }

    /** every non-empty row from the first to the last one holding text */
    String screenText() {
        int first = 0, last = rows - 1;
        while (first < rows && line(first).isEmpty()) first++;
        while (last >= first && line(last).isEmpty()) last--;
        StringBuilder sb = new StringBuilder();
        for (int r = first; r <= last; r++) sb.append(line(r)).append('\n');
        return sb.toString();
    }

    /** the last row that has text (the prompt line while the shell waits) */
    String lastLine() {
        for (int r = rows - 1; r >= 0; r--) if (!line(r).isEmpty()) return line(r);
        return "";
    }

    int cursorRow() { return row; }
    int cursorColumn() { return col; }
}
