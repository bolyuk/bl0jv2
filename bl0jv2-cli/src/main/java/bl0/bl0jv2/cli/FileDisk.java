package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.device.BlockDevice;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A disk image on the host: one flat file of 512-byte sectors, read and
 * written in place. A missing image is created zero-filled at the requested
 * size; an existing one keeps the size it has (rounded down to whole sectors).
 */
public final class FileDisk implements BlockDevice, AutoCloseable {
    private final RandomAccessFile file;
    private final long sectors;

    public FileDisk(Path image, int sectorsIfNew) throws IOException {
        boolean fresh = !Files.exists(image);
        this.file = new RandomAccessFile(image.toFile(), "rw");
        if (fresh) file.setLength((long) sectorsIfNew * SECTOR_SIZE);
        this.sectors = file.length() / SECTOR_SIZE;
    }

    @Override
    public long sectorCount() {
        return sectors;
    }

    @Override
    public synchronized void read(long sector, byte[] buffer) throws IOException {
        file.seek(sector * SECTOR_SIZE);
        file.readFully(buffer, 0, SECTOR_SIZE);
    }

    @Override
    public synchronized void write(long sector, byte[] buffer) throws IOException {
        file.seek(sector * SECTOR_SIZE);
        file.write(buffer, 0, SECTOR_SIZE);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
