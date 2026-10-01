package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.device.HostShare;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * --bridge-fs DIR: one host folder shown to the guest. Every path the guest
 * names is resolved against the folder's real path and refused when it leaves
 * it ('..', an absolute path, a symlink pointing out); the guest never sees
 * anything else of the host.
 */
public final class DirShare implements HostShare {
    private final Path root;

    public DirShare(Path folder) throws IOException {
        this.root = folder.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException(folder + " is not a folder");
    }

    private Path resolve(String path) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\") || path.indexOf('\0') >= 0 || path.matches("^[A-Za-z]:.*"))
            throw new IOException("path must be relative");
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) throw new IOException("path leaves the shared folder");
        // a symlink inside the folder must not lead out of it either
        Path existing = target;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing != null && !existing.toRealPath().startsWith(root)) throw new IOException("path leaves the shared folder");
        return target;
    }

    @Override
    public long size(String path) throws IOException {
        Path p = resolve(path);
        if (!Files.exists(p)) return -1;
        return Files.isDirectory(p) ? -2 : Files.size(p);
    }

    @Override
    public int read(String path, long offset, byte[] buffer, int length) throws IOException {
        try (var f = new RandomAccessFile(resolve(path).toFile(), "r")) {
            f.seek(offset);
            int n = f.read(buffer, 0, length);
            return Math.max(n, 0);
        }
    }

    @Override
    public void write(String path, long offset, byte[] data, int length) throws IOException {
        Path p = resolve(path);
        if (offset == 0 && Files.isDirectory(p)) throw new IOException("is a folder");
        try (var f = new RandomAccessFile(p.toFile(), "rw")) {
            if (offset == 0) f.setLength(0);
            f.seek(offset);
            f.write(data, 0, length);
        }
    }

    @Override
    public List<String> list(String path) throws IOException {
        Path p = resolve(path);
        List<String> names = new ArrayList<>();
        try (var s = Files.list(p)) {
            s.forEach(e -> names.add(e.getFileName() + (Files.isDirectory(e) ? "/" : "")));
        }
        Collections.sort(names);
        return names;
    }

    @Override
    public void delete(String path) throws IOException {
        Path p = resolve(path);
        if (p.equals(root)) throw new IOException("cannot delete the shared folder itself");
        Files.delete(p); // a non-empty folder refuses
    }

    @Override
    public void mkdir(String path) throws IOException {
        Files.createDirectory(resolve(path));
    }
}
