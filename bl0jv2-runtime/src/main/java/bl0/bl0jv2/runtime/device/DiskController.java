package bl0.bl0jv2.runtime.device;

import bl0.bl0jv2.runtime.memory.PortIO;
import bl0.bl0jv2.runtime.memory.RawMemory;

import java.io.IOException;

/**
 * The guest-visible side of the attached {@link BlockDevice}s: seven ports and DMA into
 * raw memory, the way a simple disk controller works. The guest picks a drive (unit 0 is
 * selected at the start), writes the sector number and the address of a 512-byte buffer,
 * then a command; the transfer is complete (and the status port valid) when that out8
 * returns. Drives are numbered in the order they were attached.
 *
 * <pre>
 *   0x0F00  in32   sector count of the selected drive (0 = none)
 *   0x0F04  out32  sector number
 *   0x0F08  out32  raw-memory address of the 512-byte buffer
 *   0x0F0C  out8   command: 1 = read sector into buffer, 2 = write buffer to sector
 *   0x0F0D  in8    status of the last command: 0 = ok, 1 = failed
 *   0x0F0E  out8   select a drive; 0x0F00 then shows its sector count
 *   0x0F0F  in8    how many drives are attached
 * </pre>
 */
public final class DiskController implements PortDevice {
    public static final int SECTORS_PORT = 0x0F00;
    public static final int SECTOR_PORT = 0x0F04;
    public static final int ADDRESS_PORT = 0x0F08;
    public static final int COMMAND_PORT = 0x0F0C;
    public static final int STATUS_PORT = 0x0F0D;
    public static final int SELECT_PORT = 0x0F0E;
    public static final int UNITS_PORT = 0x0F0F;

    public static final int CMD_READ = 1;
    public static final int CMD_WRITE = 2;

    private final PortIO ports;
    private final RawMemory memory;
    private final java.util.List<BlockDevice> devices = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile int selected;

    public DiskController(PortIO ports, RawMemory memory) {
        this.ports = ports;
        this.memory = memory;
    }

    /** adds a drive after the ones already attached; null detaches them all */
    public void attach(BlockDevice device) {
        if (device == null) devices.clear(); else devices.add(device);
        select(0);
        ports.write(UNITS_PORT, 1, devices.size());
        ports.write(STATUS_PORT, 1, 0);
    }

    private void select(int unit) {
        selected = unit;
        ports.write(SECTORS_PORT, 4, unit >= 0 && unit < devices.size() ? devices.get(unit).sectorCount() : 0);
    }

    /** called by the VM after every port write; the command and select ports do something */
    @Override
    public void onWrite(int port, long value) {
        if (port == COMMAND_PORT) command((int) value & 0xFF);
        else if (port == SELECT_PORT) select((int) value & 0xFF);
    }

    private void command(int cmd) {
        int status = 1;
        int unit = selected;
        BlockDevice d = unit < devices.size() ? devices.get(unit) : null;
        if (d != null && (cmd == CMD_READ || cmd == CMD_WRITE)) {
            long sector = ports.read(SECTOR_PORT, 4);
            int addr = (int) ports.read(ADDRESS_PORT, 4);
            if (sector < d.sectorCount()) {
                byte[] buf = new byte[BlockDevice.SECTOR_SIZE];
                try {
                    if (cmd == CMD_READ) {
                        d.read(sector, buf);
                        memory.writeBytes(addr, buf);
                    } else {
                        memory.readBytes(addr, buf);
                        d.write(sector, buf);
                    }
                    status = 0;
                } catch (IOException | RuntimeException e) {
                    status = 1;
                }
            }
        }
        ports.write(STATUS_PORT, 1, status);
    }
}
