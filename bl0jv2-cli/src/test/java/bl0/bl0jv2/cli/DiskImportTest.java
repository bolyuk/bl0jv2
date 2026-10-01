package bl0.bl0jv2.cli;

import bl0.bl0jv2.Bl0jv2_TestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// --disk-put: host files end up on the image in the format the guest's Fs reads
class DiskImportTest {

    @Test
    void putFilesAreReadableByTheGuestFilesystem(@TempDir Path dir) throws Exception {
        Path text = dir.resolve("note.txt");
        Files.writeString(text, "привет");
        Path lib = Files.createDirectories(dir.resolve("lib"));
        Files.writeString(lib.resolve("util.bl0"), "def twice(x) { return x * 2; }");
        Path prog = dir.resolve("prog.bl0");
        Files.writeString(prog, "import 'util.bl0'; println twice(21);");

        try (var disk = new FileDisk(dir.resolve("d.img"), 128)) {
            DiskImport.put(disk, List.of(DiskImport.Spec.parse(text + ":docs/note.txt"), DiskImport.Spec.parse(prog.toString())), List.of(lib));

            Path entry = dir.resolve("entry.bl0");
            Files.writeString(entry, "import 'stdlib/fs/fs.bl0'; Disk.init(8192); Fs.mount(); " +
                    "print Fs.read('docs/note.txt') + '|' + str(Fs.size('prog.bl0c') > 0) + '|' + str(Fs.readData('prog.bl0c')[0]);");
            // the compiled program starts with the bytecode magic's first byte
            String out = Bl0jv2_TestRunner.runFile(entry, vm -> vm.attach_disk(disk));
            assertEquals("привет|true|" + (bl0.bl0jv2.data.C.MAGIC >>> 24), out);
        }
    }

    @Test
    void aWindowsDriveLetterIsNotANameSeparator() {
        assertEquals(null, DiskImport.Spec.parse("C:\\x\\a.txt").name());
        assertEquals("n", DiskImport.Spec.parse("C:\\x\\a.txt:n").name());
        assertEquals("n", DiskImport.Spec.parse("a.txt:n").name());
    }
}
