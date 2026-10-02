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

    @Test
    void aNewFileBelongsToWhoMadeItWithTheUmaskApplied(@TempDir Path dir) throws IOException {
        // [owner, group, mode, size, kind]; 420 = 0644, 493 = 0755
        assertEquals("1000,100,420,2,0|1000,100,493,0,1|0,0,493,0,1", run(dir, 64,
                "Fs.format(); Fs.runAs(1000, 100, 1, 'a.txt', 'hi'); Fs.runAs(1000, 100, 1, 'd/', ''); Fs.write('d/x', 'y'); " +
                "s1 = Fs.stat('a.txt'); s2 = Fs.stat('d'); s3 = Fs.stat('');" +
                "print str(s1[0]) + ',' + str(s1[1]) + ',' + str(s1[2]) + ',' + str(s1[3]) + ',' + str(s1[4]) + '|' + " +
                "str(s2[0]) + ',' + str(s2[1]) + ',' + str(s2[2]) + ',' + str(s2[3]) + ',' + str(s2[4]) + '|' + " +
                "str(s3[0]) + ',' + str(s3[1]) + ',' + str(s3[2]) + ',' + str(s3[3]) + ',' + str(s3[4]);"));
    }

    @Test
    void chmodAndChownChangeTheEntryAndSurviveRewritesAndRenames(@TempDir Path dir) throws IOException {
        assertEquals("384|7|8|8|true|false|nil", run(dir, 64,
                "Fs.format(); Fs.write('a', 'one'); Fs.chmod('a', 384); Fs.chown('a', 7, 8); " +
                "s = Fs.stat('a'); print str(s[2]) + '|' + str(s[0]) + '|' + str(s[1]); " +
                "Fs.write('a', 'longer text'); Fs.append('a', '!'); Fs.rename('a', 'b'); " +
                "t = Fs.stat('b'); print '|' + str(t[1]) + '|' + str(Fs.chmod('b', 0)) + '|' + str(Fs.chmod('nosuch', 1)) + '|' + str(Fs.stat('a'));"));
    }

    @Test
    void aFolderWithoutAMarkerCanStillBeGivenAnOwner(@TempDir Path dir) throws IOException {
        assertEquals("493,0|448,5|true", run(dir, 64,
                "Fs.format(); Fs.write('docs/a.txt', 'x'); s = Fs.stat('docs'); print str(s[2]) + ',' + str(s[0]); " +
                "Fs.chown('docs', 5, 5); Fs.chmod('docs', 448); t = Fs.stat('docs'); print '|' + str(t[2]) + ',' + str(t[0]) + '|' + str(Fs.exists('docs/a.txt'));"));
    }

    @Test
    void anEntryFromBeforeModesExistedCountsAsRootOwned(@TempDir Path dir) throws IOException {
        // entries written by the old code have zeros where the owner and mode now live: 0644 file, 0755 folder
        assertEquals("420,0|493,0", run(dir, 64,
                "Fs.format(); Fs.write('old', 'x'); Fs.write('d/', ''); " +
                "e = Fs.lookup(Fs.encodeName('old')); m = Fs.entryMeta(e); Fs.writeMeta(e, [0, 0, 0]); " +
                "f = Fs.lookup(Fs.encodeName('d/')); Fs.writeMeta(f, [0, 0, 0]); " +
                "a = Fs.stat('old'); b = Fs.stat('d'); print str(a[2]) + ',' + str(a[0]) + '|' + str(b[2]) + ',' + str(b[0]);"));
    }

    @Test
    void theStickyBitIsPartOfTheMode(@TempDir Path dir) throws IOException {
        // 1777 = 0x3FF; a mask of 0777 would lose it; the umask only takes rwx bits away
        assertEquals("1023|493|1023", run(dir, 64,
                "Fs.format(); Fs.write('t/', ''); Fs.chmod('t', 1023); print str(Fs.stat('t')[2]) + '|'; " +
                "Fs.write('d/', ''); print str(Fs.stat('d')[2]) + '|'; Fs.chmod('d', 0x3FF + 0x400); print str(Fs.stat('d')[2]);"));
    }

    // a virtual folder 'v': v/hello (read gives text), v/echo (write is remembered, read gives it back),
    // v/sub/deep, v/gone (removable); the provider sees who asks
    private static final String TOY =
            "def class Toy { static field state; static field gone; } Toy.state = ''; Toy.gone = true; " +
            "def has(list, x) { i = 0; while (i < len(list)) { if (list[i] == x) { return true; } i += 1; } return false; } " +
            "def toy(op, rel, arg, uid, gid) { " +
            "  if (op == 2) { if (rel == 'hello') { return 'hi from ' + str(uid); } if (rel == 'echo') { return Toy.state; } " +
            "                 if (rel == 'sub/deep') { return 'deep'; } if (rel == 'gone' && Toy.gone) { return 'x'; } return nil; } " +
            "  if (op == 1 || op == 10) { if (rel != 'echo') { throw('fs: no such device'); } if (arg != '') { Toy.state = (op == 10 ? Toy.state : '') + arg; } return nil; } " +
            "  if (op == 3) { if (rel == 'gone' && Toy.gone) { Toy.gone = false; return true; } return false; } " +
            "  if (op == 13) { if (rel == '') { return [0, 0, 0x16D, 0, 1]; } if (rel == 'sub') { return [0, 0, 0x16D, 0, 1]; } " +
            "                  if (rel == 'hello' || rel == 'echo' || rel == 'sub/deep' || (rel == 'gone' && Toy.gone)) { return [7, 8, 0x1A4, 5, 0]; } return nil; } " +
            "  if (op == 4) { out = []; names = ['echo', 'gone', 'hello', 'sub/deep']; i = 0; " +
            "                 while (i < len(names)) { if (strFind(names[i], rel, 0) == 0 && !(names[i] == 'gone' && !Toy.gone)) { push(out, [names[i], 5]); } i += 1; } return out; } " +
            "  return nil; } " +
            "Fs.provide('v', toy); ";

    @Test
    void aVirtualFolderIsAnsweredByCode(@TempDir Path dir) throws IOException {
        assertEquals("hi from 0|5|5|true|false|true|nil", run(dir, 64, TOY +
                "Fs.format(); print Fs.read('v/hello') + '|' + str(Fs.size('v/hello')) + '|' + str(Fs.stat('v/hello')[3]) + '|' + " +
                "str(Fs.exists('v/hello')) + '|' + str(Fs.exists('v/nosuch')) + '|' + str(Fs.stat('v')[4] == 1) + '|' + str(Fs.read('v/nosuch'));"));
    }

    @Test
    void writingActsAndTheNameIsTheProvidersToRefuse(@TempDir Path dir) throws IOException {
        assertEquals("abc|ab|fs: no such device|true", run(dir, 64, TOY +
                "Fs.format(); Fs.write('v/echo', ''); Fs.append('v/echo', 'abc'); print Fs.read('v/echo') + '|'; " +
                "Fs.write('v/echo', 'ab'); print Fs.read('v/echo') + '|'; " +
                "try { Fs.write('v/other', 'x'); } catch (e) { print e; } print '|' + str(Fs.remove('v/gone'));"));
    }

    @Test
    void theFolderShowsUpInListingsAndKeepsItsOwnTree(@TempDir Path dir) throws IOException {
        // the root listing has the virtual files below it, 'v/' itself is a folder, a list inside it is the provider's alone
        assertEquals("true|echo,gone,hello,sub/deep|deep", run(dir, 64, TOY +
                "Fs.format(); Fs.write('disk.txt', 'on disk'); " +
                "all = Fs.list(''); names = []; i = 0; while (i < len(all)) { push(names, all[i][0]); i += 1; } " +
                "print str(has(names, 'disk.txt') && has(names, 'v/hello') && has(names, 'v/')) + '|'; " +
                "inside = Fs.list('v/'); n = []; i = 0; while (i < len(inside)) { if (inside[i][0] != 'v/') { push(n, strSub(inside[i][0], 2, len(inside[i][0]))); } i += 1; } " +
                "print strJoin(n, ',') + '|' + Fs.read('v/sub/deep');"));
    }

    @Test
    void aVirtualFileCannotBeRenamedOrGivenAnOwnerAndOnlyTheKernelProvides(@TempDir Path dir) throws IOException {
        String out = run(dir, 64, TOY + "Fs.format(); " +
                "def tryIt(f) { try { f(); return 'ok'; } catch (e) { return str(e); } } " +
                "print tryIt(() -> Fs.rename('v/hello', 'x')) + '|' + tryIt(() -> Fs.rename('a', 'v/b')) + '|' + tryIt(() -> Fs.chmod('v/hello', 1)) + '|' + " +
                "tryIt(() -> Fs.chown('v/hello', 1, 1));");
        for (String part : new String[]{"cannot be renamed", "fixed owner and mode"})
            org.junit.jupiter.api.Assertions.assertTrue(out.contains(part), part + " in: " + out);
    }

    private static String twoDisks(Path dir, MemoryDisk first, MemoryDisk second, String body) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/fs/fs.bl0'; Disk.init(8192); " + body);
        return Bl0jv2_TestRunner.runFile(entry, vm -> { vm.attach_disk(first); vm.attach_disk(second); });
    }

    @Test
    void aSecondDriveIsMountedAsAFolder(@TempDir Path dir) throws IOException {
        var second = new MemoryDisk(48);
        assertEquals("on usb|6|true|mnt/usb/,mnt/usb/a.txt|root only|false", twoDisks(dir, new MemoryDisk(64), second,
                "Fs.format(); Fs.formatUnit(1); Fs.write('mnt/usb/', ''); Fs.mountDrive(1, 'mnt/usb'); " +
                "Fs.write('mnt/usb/a.txt', 'on usb'); Fs.write('b.txt', 'root only'); " +
                "print Fs.read('mnt/usb/a.txt') + '|' + str(Fs.size('mnt/usb/a.txt')) + '|' + str(Fs.exists('mnt/usb/a.txt')) + '|'; " +
                "names = []; e = Fs.list('mnt/usb/'); i = 0; while (i < len(e)) { push(names, e[i][0]); i += 1; } " +
                "print strJoin(names, ',') + '|' + Fs.read('b.txt') + '|' + str(Fs.exists('a.txt'));"));
    }

    @Test
    void whatWasWrittenToTheDriveStaysOnItAndComesBackWhenItIsMountedAgain(@TempDir Path dir) throws IOException {
        var second = new MemoryDisk(48);
        assertEquals("false|true|usb data", twoDisks(dir, new MemoryDisk(64), second,
                "Fs.format(); Fs.formatUnit(1); Fs.write('m/', ''); Fs.mountDrive(1, 'm'); Fs.write('m/x', 'usb data'); " +
                "Fs.unmountDrive('m'); print str(Fs.exists('m/x')) + '|'; " +
                "Fs.mountDrive(1, 'm'); print str(Fs.exists('m/x')) + '|' + Fs.read('m/x');"));
    }

    @Test
    void theRootListingShowsTheDriveAndEachDriveHasItsOwnSpace(@TempDir Path dir) throws IOException {
        var second = new MemoryDisk(48);
        // total sectors of the second drive, and the root's listing holding the mounted file too
        assertEquals("48|64|true|2", twoDisks(dir, new MemoryDisk(64), second,
                "Fs.format(); Fs.formatUnit(1); Fs.write('m/', ''); Fs.mountDrive(1, 'm'); Fs.write('m/x', 'data'); " +
                "print str(Fs.infoOf('m/x')[0]) + '|' + str(Fs.info()[0]) + '|'; " +
                "all = Fs.list(''); found = false; i = 0; while (i < len(all)) { if (all[i][0] == 'm/x') { found = true; } i += 1; } " +
                "print str(found) + '|' + str(len(Fs.mountList()) + len(Fs.driveList()) - 1);"));
    }

    @Test
    void filesMoveBetweenDrivesWithRename(@TempDir Path dir) throws IOException {
        var second = new MemoryDisk(48);
        assertEquals("moved|false|true|back|true", twoDisks(dir, new MemoryDisk(64), second,
                "Fs.format(); Fs.formatUnit(1); Fs.write('m/', ''); Fs.mountDrive(1, 'm'); Fs.write('f', 'moved'); " +
                "Fs.rename('f', 'm/f'); print Fs.read('m/f') + '|' + str(Fs.exists('f')) + '|'; " +
                "Fs.write('m/g', 'back'); Fs.rename('m/g', 'g'); print str(Fs.exists('m/f')) + '|' + Fs.read('g') + '|' + str(Fs.exists('g'));"));
    }

    @Test
    void mountMistakesAreErrors(@TempDir Path dir) throws IOException {
        var second = new MemoryDisk(48);
        String out = twoDisks(dir, new MemoryDisk(64), second,
                "Fs.format(); Fs.write('m/', ''); Fs.write('file', 'x'); " +
                "def tryIt(f) { try { f(); return 'ok'; } catch (e) { return str(e); } } " +
                "print tryIt(() -> Fs.mountDrive(1, 'm')) + '|';" +                      // drive 1 has no file system yet
                "Fs.formatUnit(1); " +
                "print tryIt(() -> Fs.mountDrive(1, 'file')) + '|' + tryIt(() -> Fs.mountDrive(1, 'nosuch')) + '|' + tryIt(() -> Fs.mountDrive(0, 'm')) + '|' + " +
                "tryIt(() -> Fs.mountDrive(5, 'm')) + '|'; " +
                "Fs.mountDrive(1, 'm'); " +
                "print tryIt(() -> Fs.mountDrive(1, 'm')) + '|' + tryIt(() -> Fs.formatUnit(1)) + '|' + tryIt(() -> Fs.rename('m', 'z')) + '|' + tryIt(() -> Fs.unmountDrive('nope'));");
        for (String part : new String[]{"holds no file system", "no such folder", "no drive 0", "no drive 5", "already mounted", "in use", "is a mount point", "not a mount point"})
            org.junit.jupiter.api.Assertions.assertTrue(out.contains(part), part + " in: " + out);
    }
}
