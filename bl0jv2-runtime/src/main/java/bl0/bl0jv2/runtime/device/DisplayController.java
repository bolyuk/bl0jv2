package bl0.bl0jv2.runtime.device;

import bl0.bl0jv2.runtime.memory.PortIO;
import bl0.bl0jv2.runtime.memory.RawMemory;

/**
 * A text-mode display, in the manner of VGA's text mode: the screen is a grid of
 * cells that live in the guest's own memory (the frame buffer, whose address the guest
 * chooses), and the display shows whatever is there. The host only looks - the CLI's
 * renderer reads the frame through {@link #frame} and draws it on a real terminal.
 *
 * <p>A cell is 32 bits, big-endian like all of this VM's memory: bits 0-20 the Unicode
 * code point (0 shows as a blank), bits 24-27 the foreground and 28-31 the background
 * colour, from the 16-colour palette (0 black, 1 red, 2 green, 3 brown, 4 blue,
 * 5 magenta, 6 cyan, 7 light grey, then the same eight bright). The grid is
 * columns x rows cells, row by row.
 *
 * <pre>
 *   0x0F50  in16   columns            0x0F52  in16   rows
 *   0x0F54  in8    1 when a display is attached
 *   0x0F58  out32  frame buffer address (the guest may switch between buffers at will)
 *   0x0F5C  out32  cursor position, as a cell index (row * columns + column)
 *   0x0F60  out8   cursor visible (1) or hidden (0)
 *   0x0F64  out8   command: 1 = scroll the frame up by (0x0F68) rows, the rows that
 *                  open up at the bottom filled with the cell (0x0F6C);
 *                  2 = fill the whole frame with the cell (0x0F6C)
 *   0x0F68  out32  argument: rows to scroll
 *   0x0F6C  out32  the fill cell
 * </pre>
 *
 * The command ports are what a 2-D engine would offer; moving a screenful of cells one
 * 32-bit word at a time from the guest would otherwise make every line feed expensive.
 */
public final class DisplayController implements PortDevice {
    public static final int COLUMNS_PORT = 0x0F50;
    public static final int ROWS_PORT = 0x0F52;
    public static final int PRESENT_PORT = 0x0F54;
    public static final int FRAME_PORT = 0x0F58;
    public static final int CURSOR_PORT = 0x0F5C;
    public static final int CURSOR_VISIBLE_PORT = 0x0F60;
    public static final int COMMAND_PORT = 0x0F64;
    public static final int ARGUMENT_PORT = 0x0F68;
    public static final int FILL_PORT = 0x0F6C;

    /** what the screen shows at one instant */
    public record Frame(int columns, int rows, int[] cells, int cursorRow, int cursorColumn, boolean cursorVisible) {
        public int codePoint(int row, int col) {
            int cp = cells[row * columns + col] & 0x1FFFFF;
            if (cp == 0) return ' ';
            // a cell the guest filled with something that is not a character shows as the replacement
            return cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) ? 0xFFFD : cp;
        }

        public int foreground(int row, int col) {
            return (cells[row * columns + col] >>> 24) & 0xF;
        }

        public int background(int row, int col) {
            return (cells[row * columns + col] >>> 28) & 0xF;
        }

        /** the text of one row, trailing blanks removed */
        public String line(int row) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < columns; c++) sb.appendCodePoint(codePoint(row, c));
            return sb.toString().stripTrailing();
        }

        /** every row from the first to the last one holding text, one per line */
        public String text() {
            int first = 0, last = rows - 1;
            while (first < rows && line(first).isEmpty()) first++;
            while (last >= first && line(last).isEmpty()) last--;
            StringBuilder sb = new StringBuilder();
            for (int r = first; r <= last; r++) sb.append(line(r)).append('\n');
            return sb.toString();
        }
    }

    private final PortIO ports;
    private final RawMemory memory;
    private volatile int columns = 80;
    private volatile int rows = 24;
    private volatile boolean attached;

    public DisplayController(PortIO ports, RawMemory memory) {
        this.ports = ports;
        this.memory = memory;
    }

    public void setSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    /** a display is attached: the guest can find out by reading the present port */
    public void attach() {
        attached = true;
        ports.write(PRESENT_PORT, 1, 1);
    }

    public boolean isAttached() {
        return attached;
    }

    @Override
    public boolean claimsRead(int port) {
        return port == COLUMNS_PORT || port == ROWS_PORT;
    }

    @Override
    public long read(int port, int widthBytes) {
        return port == COLUMNS_PORT ? columns : rows;
    }

    @Override
    public void onWrite(int port, long value) {
        if (port != COMMAND_PORT) return;
        int base = (int) ports.read(FRAME_PORT, 4);
        int cells = columns * rows;
        int fill = (int) ports.read(FILL_PORT, 4);
        try {
            if ((value & 0xFF) == 1) {
                int n = (int) Math.min(Math.max(ports.read(ARGUMENT_PORT, 4), 0), rows);
                int move = (rows - n) * columns;
                byte[] frame = new byte[cells * 4];
                memory.readBytes(base, frame);
                System.arraycopy(frame, n * columns * 4, frame, 0, move * 4);
                for (int i = move; i < cells; i++) putInt(frame, i, fill);
                memory.writeBytes(base, frame);
            } else if ((value & 0xFF) == 2) {
                byte[] frame = new byte[cells * 4];
                for (int i = 0; i < cells; i++) putInt(frame, i, fill);
                memory.writeBytes(base, frame);
            }
        } catch (RuntimeException e) {
            // a frame buffer address outside memory: nothing to draw into
        }
    }

    private static void putInt(byte[] frame, int cell, int value) {
        frame[cell * 4] = (byte) (value >>> 24);
        frame[cell * 4 + 1] = (byte) (value >>> 16);
        frame[cell * 4 + 2] = (byte) (value >>> 8);
        frame[cell * 4 + 3] = (byte) value;
    }

    /** what the screen shows now, or null if no display is attached or the frame buffer is not in memory */
    public Frame frame() {
        if (!attached) return null;
        int base = (int) ports.read(FRAME_PORT, 4);
        int cols = columns, rws = rows;
        byte[] raw = new byte[cols * rws * 4];
        try {
            memory.readBytes(base, raw);
        } catch (RuntimeException e) {
            return null;
        }
        int[] cells = new int[cols * rws];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = ((raw[i * 4] & 0xFF) << 24) | ((raw[i * 4 + 1] & 0xFF) << 16)
                    | ((raw[i * 4 + 2] & 0xFF) << 8) | (raw[i * 4 + 3] & 0xFF);
        }
        int cursor = (int) ports.read(CURSOR_PORT, 4);
        boolean visible = ports.read(CURSOR_VISIBLE_PORT, 1) != 0;
        return new Frame(cols, rws, cells, Math.min(cursor / cols, rws - 1), cursor % cols, visible);
    }
}
