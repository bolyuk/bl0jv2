package bl0.bl0jv2.runtime.device;

import bl0.bl0jv2.runtime.memory.PortIO;
import bl0.bl0jv2.runtime.memory.RawMemory;

import java.nio.charset.StandardCharsets;

/**
 * The guest side of a {@link HostShare}: ports and DMA, like {@link DiskController}.
 * The guest puts a zero-terminated UTF-8 path in memory, sets the addresses, a
 * length and an offset, then writes a command; the result is in the result port
 * when that out8 returns.
 *
 * <pre>
 *   0x0F10  in8    1 when a share is attached
 *   0x0F14  out32  address of the path (zero-terminated, at most 255 bytes)
 *   0x0F18  out32  address of the data buffer
 *   0x0F1C  out32  length: buffer size for read/list, byte count for write
 *   0x0F20  out32  file offset
 *   0x0F24  out8   command: 1 size, 2 read, 3 write, 4 list, 5 delete, 6 mkdir
 *   0x0F25  in8    status: 0 ok, 1 failed
 *   0x0F28  in32   result: size / bytes read / bytes written / bytes of listing
 * </pre>
 */
public final class ShareController {
    public static final int PRESENT_PORT = 0x0F10;
    public static final int PATH_PORT = 0x0F14;
    public static final int DATA_PORT = 0x0F18;
    public static final int LENGTH_PORT = 0x0F1C;
    public static final int OFFSET_PORT = 0x0F20;
    public static final int COMMAND_PORT = 0x0F24;
    public static final int STATUS_PORT = 0x0F25;
    public static final int RESULT_PORT = 0x0F28;
    private static final int MAX_CHUNK = 1 << 16;

    private final PortIO ports;
    private final RawMemory memory;
    private volatile HostShare share;

    public ShareController(PortIO ports, RawMemory memory) {
        this.ports = ports;
        this.memory = memory;
    }

    public void attach(HostShare share) {
        this.share = share;
        ports.write(PRESENT_PORT, 1, share == null ? 0 : 1);
    }

    public void onPortWrite(int port, long value) {
        if (port == COMMAND_PORT) command((int) value & 0xFF);
    }

    private String readPath() {
        int addr = (int) ports.read(PATH_PORT, 4);
        byte[] raw = new byte[255];
        int n = 0;
        while (n < raw.length) {
            byte b = (byte) memory.peek(addr + n, 1);
            if (b == 0) break;
            raw[n++] = b;
        }
        return new String(raw, 0, n, StandardCharsets.UTF_8);
    }

    private void command(int cmd) {
        long result = 0;
        int status = 1;
        HostShare s = share;
        if (s != null) {
            try {
                String path = readPath();
                int dataAddr = (int) ports.read(DATA_PORT, 4);
                int length = (int) ports.read(LENGTH_PORT, 4);
                long offset = ports.read(OFFSET_PORT, 4);
                if (length < 0 || length > MAX_CHUNK) throw new java.io.IOException("bad length");
                switch (cmd) {
                    case 1 -> result = s.size(path);
                    case 2 -> {
                        byte[] buf = new byte[length];
                        int n = s.read(path, offset, buf, length);
                        memory.writeBytes(dataAddr, java.util.Arrays.copyOf(buf, n));
                        result = n;
                    }
                    case 3 -> {
                        byte[] buf = new byte[length];
                        memory.readBytes(dataAddr, buf);
                        s.write(path, offset, buf, length);
                        result = length;
                    }
                    case 4 -> {
                        byte[] text = String.join("\n", s.list(path)).getBytes(StandardCharsets.UTF_8);
                        if (text.length > length) throw new java.io.IOException("listing does not fit");
                        memory.writeBytes(dataAddr, text);
                        result = text.length;
                    }
                    case 5 -> s.delete(path);
                    case 6 -> s.mkdir(path);
                    default -> throw new java.io.IOException("unknown command");
                }
                status = 0;
            } catch (java.io.IOException | RuntimeException e) {
                status = 1;
                result = 0;
            }
        }
        ports.write(RESULT_PORT, 4, result);
        ports.write(STATUS_PORT, 1, status);
    }
}
