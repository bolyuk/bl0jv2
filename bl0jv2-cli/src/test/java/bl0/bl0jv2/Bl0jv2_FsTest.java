package bl0.bl0jv2;

import bl0.bl0jv2.runtime.device.MemoryDisk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// stdlib/fs: the filesystem on the disk driver, over an in-memory disk
class Bl0jv2_FsTest {

    private static String run(Path dir, int sectors, String body) throws IOException {
        return run(dir, new MemoryDisk(sectors), body);
    }

    private static String run(Path dir, MemoryDisk disk, String body) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/fs/fs.bl0'; Disk.init(8192); " + body);
        return Bl0jv2_TestRunner.runFile(entry, vm -> vm.attach_disk(disk));
    }

    @Test
    void aBlankDiskHasNoFilesystemUntilItIsFormatted(@TempDir Path dir) throws IOException {
        assertEquals("false|true|0", run(dir, 64,
                "print str(Fs.mount()) + '|'; Fs.format(); print str(Fs.mounted) + '|' + str(Fs.info()[3]);"));
    }

    @Test
    void writeThenReadReturnsTheSameText(@TempDir Path dir) throws IOException {
        assertEquals("hello world|11|true|false", run(dir, 64,
                "Fs.format(); Fs.write('a.txt', 'hello world'); " +
                "print Fs.read('a.txt') + '|' + str(Fs.size('a.txt')) + '|' + str(Fs.exists('a.txt')) + '|' + str(Fs.exists('b.txt'));"));
    }

    @Test
    void textSurvivesUtf8AndSpansSeveralSectors(@TempDir Path dir) throws IOException {
        // 'привет, мир ' is 21 bytes; 100 repeats cross four sector boundaries
        assertEquals("true|2100", run(dir, 64,
                "Fs.format(); t = ''; i = 0; while (i < 100) { t = t + 'привет, мир '; i += 1; } " +
                "Fs.write('ru.txt', t); print str(Fs.read('ru.txt') == t) + '|' + str(Fs.size('ru.txt'));"));
    }

    @Test
    void anEmptyFileHasNoSectors(@TempDir Path dir) throws IOException {
        assertEquals("|0|true|" , run(dir, 64,
                "Fs.format(); free0 = Fs.info()[2]; Fs.write('e', ''); " +
                "print Fs.read('e') + '|' + str(Fs.size('e')) + '|' + str(Fs.info()[2] == free0) + '|';"));
    }

    @Test
    void rewritingReplacesTheContentAndGivesBackTheOldSectors(@TempDir Path dir) throws IOException {
        assertEquals("small|true", run(dir, 64,
                "Fs.format(); free0 = Fs.info()[2]; t = ''; i = 0; while (i < 2000) { t = t + 'x'; i += 1; } " +
                "Fs.write('f', t); Fs.write('f', 'small'); " +
                "print Fs.read('f') + '|' + str(Fs.info()[2] == free0 - 1);"));
    }

    @Test
    void appendAddsToTheEndAndCreatesAMissingFile(@TempDir Path dir) throws IOException {
        assertEquals("ab|c", run(dir, 64,
                "Fs.format(); Fs.append('f', 'a'); Fs.append('f', 'b'); Fs.append('g', 'c'); print Fs.read('f') + '|' + Fs.read('g');"));
    }

    @Test
    void removeFreesTheSpaceAndTheName(@TempDir Path dir) throws IOException {
        assertEquals("true|false|nil|true", run(dir, 64,
                "Fs.format(); free0 = Fs.info()[2]; Fs.write('f', 'data'); " +
                "print str(Fs.remove('f')) + '|' + str(Fs.remove('f')) + '|' + str(Fs.read('f')) + '|' + str(Fs.info()[2] == free0);"));
    }

    @Test
    void renameKeepsTheContentAndRefusesAnExistingName(@TempDir Path dir) throws IOException {
        assertEquals("true|data|false|fs: b already exists", run(dir, 64,
                "Fs.format(); Fs.write('a', 'data'); Fs.write('b', 'other'); " +
                "print str(Fs.rename('a', 'c')) + '|' + Fs.read('c') + '|' + str(Fs.rename('zz', 'y')) + '|'; " +
                "try { Fs.rename('c', 'b'); } catch (e) { print e; }"));
    }

    @Test
    void listIsSortedAndTakesAPrefix(@TempDir Path dir) throws IOException {
        assertEquals("a.txt:1,docs/b:2,docs/c:3|docs/b,docs/c", run(dir, 64,
                "Fs.format(); Fs.write('docs/c', '123'); Fs.write('a.txt', '1'); Fs.write('docs/b', '12'); " +
                "all = Fs.list(''); s = ''; i = 0; while (i < len(all)) { if (i > 0) { s = s + ','; } s = s + all[i][0] + ':' + str(all[i][1]); i += 1; } " +
                "d = Fs.list('docs/'); print s + '|' + d[0][0] + ',' + d[1][0];"));
    }

    @Test
    void aFullDiskRefusesAndKeepsTheOldContent(@TempDir Path dir) throws IOException {
        // 16 sectors: 1 super + 1 FAT + 1 dir + 13 data = 13 * 512 bytes of space
        assertEquals("keep|fs: no space left on device|keep", run(dir, 16,
                "Fs.format(); Fs.write('f', 'keep'); big = ''; i = 0; while (i < 9000) { big = big + 'y'; i += 1; } " +
                "print Fs.read('f') + '|'; try { Fs.write('f', big); } catch (e) { print e; } print '|' + Fs.read('f');"));
    }

    @Test
    void theDirectoryFillsUp(@TempDir Path dir) throws IOException {
        // 16 sectors: one directory sector = 8 files
        assertEquals("fs: the directory is full", run(dir, 16,
                "Fs.format(); i = 0; try { while (i < 9) { Fs.write('f' + str(i), 'x'); i += 1; } } catch (e) { print e; }"));
    }

    @Test
    void badNamesAreRejected(@TempDir Path dir) throws IOException {
        assertEquals("fs: a file name must be 1 to 47 bytes|fs: a file name must be 1 to 47 bytes", run(dir, 64,
                "Fs.format(); try { Fs.write('', 'x'); } catch (e) { print e; } print '|'; " +
                "try { Fs.write('0123456789012345678901234567890123456789012345678', 'x'); } catch (e) { print e; }"));
    }

    @Test
    void withoutAFilesystemOperationsSayWhy(@TempDir Path dir) throws IOException {
        assertEquals("fs: no filesystem on the disk (format it first)", run(dir, 64,
                "try { Fs.read('a'); } catch (e) { print e; }"));
    }

    @Test
    void aFormattedDiskIsFoundAgainByAnotherProgram(@TempDir Path dir) throws IOException {
        var disk = new MemoryDisk(64);
        run(dir, disk, "Fs.format(); Fs.write('keep.txt', 'still here');");
        assertEquals("true|still here", run(dir, disk, "print str(Fs.mount()) + '|' + Fs.read('keep.txt');"));
    }

    @Test
    void theFilesystemWorksFromUserModeThroughTheSyscall(@TempDir Path dir) throws IOException {
        assertEquals("false|user mode data", run(dir, 64,
                "Fs.format(); dropToUserMode(); " +
                "Fs.write('u', 'user mode data'); print str(isPrivileged()) + '|' + Fs.read('u');"));
    }

    @Test
    void manyFilesAndSectorsStayConsistent(@TempDir Path dir) throws IOException {
        assertEquals("true", run(dir, 256,
                "Fs.format(); ok = true; i = 0; " +
                "while (i < 30) { Fs.write('file' + str(i), 'content ' + str(i * 7)); i += 1; } " +
                "i = 0; while (i < 30) { if (Fs.read('file' + str(i)) != 'content ' + str(i * 7)) { ok = false; } i += 1; } " +
                "i = 0; while (i < 30) { Fs.remove('file' + str(i)); i += 2; } " +
                "i = 1; while (i < 30) { if (Fs.read('file' + str(i)) != 'content ' + str(i * 7)) { ok = false; } i += 2; } " +
                "print ok;"));
    }

    @Test
    void pathsAreFoldedRelativeToTheCurrentFolder(@TempDir Path dir) throws IOException {
        assertEquals("a/b/c|x|a|a/b|b|c|", run(dir, 64,
                "import 'stdlib/fs/path.bl0'; " +
                "print FsPath.resolve('a', 'b/./c') + '|' + FsPath.resolve('a/b', '/x') + '|' + FsPath.resolve('a/b', '..') + '|' + " +
                "FsPath.resolve('', '../../a/b') + '|' + FsPath.base('a/b') + '|' + FsPath.base('c') + '|' + FsPath.parent('c');"));
    }

    @Test
    void foldersExistByPrefixAndEmptyOnesNeedAMarker(@TempDir Path dir) throws IOException {
        assertEquals("true|true|false|a.txt:false,docs:true,e:true|true|fs: docs is not empty|false", run(dir, 64,
                "import 'stdlib/fs/dirs.bl0'; Fs.format(); Fs.write('docs/x', '1'); Fs.write('a.txt', '1'); Dirs.mkdir('e'); " +
                "print str(Dirs.isDir('docs')) + '|' + str(Dirs.isDir('e')) + '|' + str(Dirs.isDir('a.txt')) + '|'; " +
                "l = Dirs.list(''); s = ''; i = 0; while (i < len(l)) { if (i > 0) { s = s + ','; } s = s + l[i][0] + ':' + str(l[i][2]); i += 1; } " +
                "print s + '|' + str(Dirs.rmdir('e')) + '|'; try { Dirs.rmdir('docs'); } catch (x) { print x; } print '|' + str(Dirs.isDir('e'));"));
    }

    @Test
    void appendingManyLinesStaysCorrectAcrossSectorBoundaries(@TempDir Path dir) throws IOException {
        assertEquals("true|2890", run(dir, 64,
                "Fs.format(); expect = ''; i = 0; " +
                "while (i < 300) { line = 'entry ' + str(i) + ';'; Fs.append('log', line); expect = expect + line; i += 1; } " +
                "print str(Fs.read('log') == expect) + '|' + str(Fs.size('log'));"));
    }

    @Test
    void anAppendBiggerThanASectorFillsTheTailThenChains(@TempDir Path dir) throws IOException {
        assertEquals("true|1500", run(dir, 64,
                "Fs.format(); a = ''; i = 0; while (i < 300) { a = a + 'a'; i += 1; } " +
                "b = ''; i = 0; while (i < 1200) { b = b + 'b'; i += 1; } " +
                "Fs.append('f', a); Fs.append('f', b); print str(Fs.read('f') == a + b) + '|' + str(Fs.size('f'));"));
    }

    @Test
    void appendingToAFullDiskFailsAndKeepsTheFile(@TempDir Path dir) throws IOException {
        assertEquals("fs: no space left on device|10", run(dir, 16,
                "Fs.format(); Fs.write('f', 'xxxxxxxxxx'); big = ''; i = 0; while (i < 9000) { big = big + 'y'; i += 1; } " +
                "try { Fs.append('f', big); } catch (e) { print e; } print '|' + str(Fs.size('f'));"));
    }

    @Test
    void utf8StreamDecodesBytesOneAtATime(@TempDir Path dir) throws IOException {
        assertEquals("a|й|€|\uD83D\uDE00|\uFFFDb|\uFFFD", run(dir, 64,
                "s = new Utf8Stream(); " +
                "print s.feed(97) + '|' + s.feed(0xD0) + s.feed(0xB9) + '|' + s.feed(0xE2) + s.feed(0x82) + s.feed(0xAC) + '|' + " +
                "s.feed(0xF0) + s.feed(0x9F) + s.feed(0x98) + s.feed(0x80) + '|' + " +
                "s.feed(0xE2) + s.feed(98) + '|' + s.feed(0xFF);"));
    }
}
