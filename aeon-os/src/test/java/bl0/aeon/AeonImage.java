package bl0.aeon;

import bl0.bl0jv2.cli.DiskImport;
import bl0.bl0jv2.cli.FileDisk;
import bl0.bl0jv2.cli.SharedLibs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The OS as the VM sees it: a disk image holding the kernel programs (sbin/) and
 * the command programs (bin/), built from the .bl0 sources with the same
 * --disk-put tooling a user would use. Building compiles about twenty programs,
 * so it happens once per test run and every test gets a copy.
 */
final class AeonImage {
    static final int SECTORS = 4096;
    private static Path template;

    private AeonImage() {}

    /** the shared libraries every program on the disk is built against */
    static SharedLibs shared() throws IOException {
        return SharedLibs.read(Path.of("aeon-os", "libs.txt"));
    }

    private static synchronized Path template() throws IOException {
        if (template == null) {
            Path file = Files.createTempFile("aeon-os-", ".img");
            file.toFile().deleteOnExit();
            Files.deleteIfExists(file);
            Path os = Path.of("aeon-os");
            try (var disk = new FileDisk(file, SECTORS)) {
                DiskImport.put(disk, List.of(
                        DiskImport.Spec.parse(os.resolve("bin") + ":bin"),
                        DiskImport.Spec.parse(os.resolve("etc") + ":etc"),
                        DiskImport.Spec.parse(os.resolve("shell.bl0") + ":sbin/shell.bl0c"),
                        DiskImport.Spec.parse(os.resolve("child_hello.bl0") + ":sbin/child_hello.bl0c"),
                        DiskImport.Spec.parse(os.resolve("child_crash.bl0") + ":sbin/child_crash.bl0c")),
                        List.of(), shared());
            }
            template = file;
        }
        return template;
    }

    private static final List<FileDisk> OPEN = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** an image file opened for a test; DiskCleanup closes it when the test is over (Windows cannot delete an open file) */
    static FileDisk open(Path image, int sectorsIfNew) throws IOException {
        var disk = new FileDisk(image, sectorsIfNew);
        OPEN.add(disk);
        return disk;
    }

    /** closes every image the test opened */
    static void closeAll() {
        for (var disk : OPEN) {
            try {
                disk.close();
            } catch (IOException ignored) {
            }
        }
        OPEN.clear();
    }

    /** a fresh copy of the OS disk at 'target' */
    static FileDisk os(Path target) throws IOException {
        Files.copy(template(), target, StandardCopyOption.REPLACE_EXISTING);
        return open(target, SECTORS);
    }

    /** a blank disk (no filesystem) */
    static FileDisk blank(Path target, int sectors) throws IOException {
        return open(target, sectors);
    }
}
