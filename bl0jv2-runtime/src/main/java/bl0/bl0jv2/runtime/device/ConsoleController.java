package bl0.bl0jv2.runtime.device;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The terminal's transmit side: a serial line. The guest writes bytes - UTF-8,
 * with ANSI escape sequences for cursor and screen control, the way every
 * terminal expects them - to one port; the host side below turns the byte
 * stream into text for whatever displays it. A character may arrive split over
 * several bytes; the decoder keeps the unfinished part until the rest comes.
 * Also tells the guest how big the screen is.
 *
 * <pre>
 *   0x0F40  out8   one byte of output
 *   0x0F44  in16   screen columns
 *   0x0F46  in16   screen rows
 * </pre>
 */
public final class ConsoleController implements PortDevice {
    public static final int TX_PORT = 0x0F40;
    public static final int COLUMNS_PORT = 0x0F44;
    public static final int ROWS_PORT = 0x0F46;

    private final Consumer<String> sink;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final ByteBuffer pending = ByteBuffer.allocate(8);
    private volatile int columns = 80;
    private volatile int rows = 24;

    public ConsoleController(Consumer<String> sink) {
        this.sink = sink;
    }

    public void setSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
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
    public synchronized void onWrite(int port, long value) {
        if (port != TX_PORT) return;
        pending.put((byte) value);
        pending.flip();
        CharBuffer out = CharBuffer.allocate(8);
        decoder.decode(pending, out, false);
        pending.compact();
        out.flip();
        if (out.hasRemaining()) sink.accept(out.toString());
    }
}
