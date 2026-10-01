package bl0.bl0jv2.runtime.device;

import java.io.IOException;

/**
 * A disk as the VM sees it: a fixed number of 512-byte sectors, read and
 * written a whole sector at a time. Deliberately the smallest thing a real
 * controller (ATA, virtio-blk, an SD card) can also offer, so the guest-side
 * filesystem (stdlib/fs) ports to bare metal by swapping the driver only.
 */
public interface BlockDevice {
    int SECTOR_SIZE = 512;

    long sectorCount();

    void read(long sector, byte[] buffer) throws IOException;

    void write(long sector, byte[] buffer) throws IOException;
}
