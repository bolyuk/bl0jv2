package bl0.bl0jv2.cli;

import bl0.bl0jv2.Bl0jv2_TestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// --bridge-fs: the shared folder, and that nothing outside it is reachable
class DirShareTest {

    private static String run(Path share, String body) throws IOException {
        Path entry = share.getParent().resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/fs/hostfs.bl0'; Hfs.init(8192, 9000); " + body);
        return Bl0jv2_TestRunner.runFile(entry, vm -> {
            try {
                vm.attach_share(new DirShare(share));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void theGuestListsReadsAndWritesInsideTheFolder(@TempDir Path dir) throws IOException {
        Path share = Files.createDirectories(dir.resolve("share"));
        Files.writeString(share.resolve("a.txt"), "привет");
        Files.createDirectory(share.resolve("sub"));
        String out = run(share,
                "print str(Hfs.list('')) + '|' + Utf8.decode(Hfs.read('a.txt')) + '|' + str(Hfs.size('a.txt')) + '|' + str(Hfs.size('sub')) + '|' + str(Hfs.size('zz')); " +
                "Hfs.write('sub/b.bin', Utf8.encode('written')); Hfs.mkdir('made'); Hfs.remove('a.txt');");
        assertEquals("[a.txt, sub/]|привет|12|-2|-1", out);
        assertEquals("written", Files.readString(share.resolve("sub/b.bin")));
        assertEquals(true, Files.isDirectory(share.resolve("made")));
        assertEquals(false, Files.exists(share.resolve("a.txt")));
    }

    @Test
    void aBigFileCrossesTheChunkSizeIntact(@TempDir Path dir) throws IOException {
        Path share = Files.createDirectories(dir.resolve("share"));
        byte[] data = new byte[10_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
        Files.write(share.resolve("big"), data);
        String out = run(share,
                "Hfs.chunk = 4096; b = Hfs.read('big'); Hfs.write('copy', b); print len(b);");
        assertEquals("10000", out);
        assertEquals(java.util.Arrays.hashCode(data), java.util.Arrays.hashCode(Files.readAllBytes(share.resolve("copy"))));
    }

    @Test
    void pathsThatLeaveTheFolderAreRefused(@TempDir Path dir) throws IOException {
        Path share = Files.createDirectories(dir.resolve("share"));
        Files.writeString(dir.resolve("secret.txt"), "top secret");
        for (String path : new String[]{"../secret.txt", "/etc/passwd", "sub/../../secret.txt", "C:/x"}) {
            String out = run(share, "try { Hfs.read('" + path + "'); } catch (e) { print e; }");
            assertEquals("hostfs: " + path + ": refused by the host", out, path);
        }
    }

    @Test
    void aSymlinkPointingOutIsRefused(@TempDir Path dir) throws IOException {
        Path share = Files.createDirectories(dir.resolve("share"));
        Files.writeString(dir.resolve("secret.txt"), "top secret");
        try {
            Files.createSymbolicLink(share.resolve("link"), dir.resolve("secret.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return; // the platform has no symlinks: nothing to test
        }
        assertEquals("hostfs: link: refused by the host", run(share, "try { Hfs.read('link'); } catch (e) { print e; }"));
    }

    @Test
    void withoutAFolderAttachedTheGuestSaysSo(@TempDir Path dir) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/fs/hostfs.bl0'; Hfs.init(8192, 9000); try { Hfs.list(''); } catch (e) { print e; }");
        assertEquals("hostfs: no host folder attached (start with --bridge-fs <folder>)", Bl0jv2_TestRunner.runFile(entry));
    }

    @Test
    void theHostCanRefuseToDeleteTheSharedFolderItself(@TempDir Path dir) throws IOException {
        Path share = Files.createDirectories(dir.resolve("share"));
        assertThrows(IOException.class, () -> new DirShare(share).delete(""));
    }
}
