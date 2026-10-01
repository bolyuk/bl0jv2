package bl0.bl0jv2.runtime.device;

/** a disk that lives in a byte array: for tests, and for a VM with no image file */
public final class MemoryDisk implements BlockDevice {
    private final byte[] data;

    public MemoryDisk(int sectors) {
        this.data = new byte[sectors * SECTOR_SIZE];
    }

    @Override
    public long sectorCount() {
        return data.length / SECTOR_SIZE;
    }

    @Override
    public synchronized void read(long sector, byte[] buffer) {
        System.arraycopy(data, (int) sector * SECTOR_SIZE, buffer, 0, SECTOR_SIZE);
    }

    @Override
    public synchronized void write(long sector, byte[] buffer) {
        System.arraycopy(buffer, 0, data, (int) sector * SECTOR_SIZE, SECTOR_SIZE);
    }
}
