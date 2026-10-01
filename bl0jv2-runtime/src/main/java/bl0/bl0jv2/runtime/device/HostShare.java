package bl0.bl0jv2.runtime.device;

import java.io.IOException;

/**
 * A folder the host chose to show the guest (--bridge-fs). Paths are relative,
 * '/'-separated, and the implementation must keep every access inside the
 * folder. Nothing here is reachable unless the host attaches one.
 */
public interface HostShare {
    /** size in bytes; -1 if missing, -2 if it is a folder */
    long size(String path) throws IOException;

    /** up to buffer.length bytes from 'offset'; the count read (0 at the end) */
    int read(String path, long offset, byte[] buffer, int length) throws IOException;

    /** writes at 'offset'; offset 0 creates the file or empties it first */
    void write(String path, long offset, byte[] data, int length) throws IOException;

    /** names inside a folder, one per entry, folders marked with a trailing '/' */
    java.util.List<String> list(String path) throws IOException;

    void delete(String path) throws IOException;

    void mkdir(String path) throws IOException;
}
