package bl0.aeon;

import bl0.aeon.AeonSession;
import bl0.bl0jv2.cli.FileDisk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** more than one drive: mount, umount, mkfs, df, etc/fstab */
class ShellMountTest {

    private static void command(AeonSession s, String line, String expected) throws Exception {
        int before = s.output().length();
        s.type(line + "\r");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(expected))
            Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(expected),
                "after '" + line + "' expected '" + expected + "' in:\n" + s.output().substring(before)
                        + "\nfinished=" + s.finished + " failure=" + s.failure);
    }

    private static AeonSession withDrive(Path dir) throws Exception {
        return AeonSession.shellOn(dir.resolve("d.img"), true, null, 1, List.of(AeonImage.blank(dir.resolve("usb.img"), 256)));
    }

    @Test
    void aSecondDriveIsFormattedMountedUsedAndUnmounted(@TempDir Path dir) throws Exception {
        var s = withDrive(dir);
        command(s, "mount", "drive 1: 256 sectors (128 KiB), not mounted");
        command(s, "mkdir mnt", "$");
        command(s, "mkdir mnt/usb", "$");
        command(s, "mount 1 mnt/usb", "holds no file system");
        command(s, "mount 1 mnt/nosuch", "no such folder");
        command(s, "mkfs 1 yes", "drive 1 formatted");
        command(s, "mount 1 mnt/usb", "drive 1 is now mnt/usb");
        command(s, "mount", "drive 1: 256 sectors (128 KiB), /mnt/usb");
        command(s, "echo on the stick > mnt/usb/a.txt", "$");
        command(s, "ls mnt/usb", "a.txt");
        command(s, "cat mnt/usb/a.txt", "on the stick");
        command(s, "cd mnt/usb", "$");
        command(s, "pwd", "/mnt/usb");
        command(s, "mkdir docs", "$");
        command(s, "echo deep > docs/b.txt", "$");
        command(s, "tree", "1 folders, 2 files");
        command(s, "cd /", "$");
        command(s, "df", "/mnt/usb");
        // between drives, in both directions
        command(s, "cp mnt/usb/a.txt here.txt", "$");
        command(s, "cat here.txt", "on the stick");
        command(s, "mv here.txt mnt/usb/moved.txt", "$");
        command(s, "ls mnt", "usb");                                // the root disk still lists the folder
        command(s, "cat mnt/usb/moved.txt", "on the stick");
        command(s, "ls here.txt", "no such folder");
        // a program stored on the drive runs
        command(s, "cp bin/seq.bl0c mnt/usb/seq.bl0c", "$");
        command(s, "mnt/usb/seq 2", "1\n2");
        // formatting a mounted drive is refused; the mount point cannot be moved
        command(s, "mkfs 1 yes", "in use");
        command(s, "mv mnt/usb elsewhere", "is a mount point");
        command(s, "rm -r mnt/usb", "is a mount point");
        command(s, "rm -r mnt/usb/docs", "$");
        command(s, "ls mnt/usb", "moved.txt");
        command(s, "umount mnt/usb", "unmounted");
        command(s, "ls mnt/usb", "(empty)");                        // the folder is the root disk's again
        command(s, "mount 1 mnt/usb", "drive 1 is now");
        command(s, "cat mnt/usb/a.txt", "on the stick");            // and the drive kept what it held
    }

    @Test
    void etcFstabMountsAtStartAndOnlyRootMounts(@TempDir Path dir) throws Exception {
        var first = withDrive(dir);
        command(first, "mkfs 1 yes", "formatted");
        command(first, "mkdir mnt", "$");
        command(first, "mkdir mnt/usb", "$");
        command(first, "mount 1 mnt/usb", "now");
        command(first, "echo kept > mnt/usb/k.txt", "$");
        command(first, "echo '1 mnt/usb' >> etc/fstab", "$");
        command(first, "echo '9 mnt/nowhere' >> etc/fstab", "$");     // a bad line only goes to the log
        first.type("exit\r");
        first.thread.join(10_000);

        var second = AeonSession.shellOn(dir.resolve("d.img"), false, null, 1,
                List.of(AeonImage.blank(dir.resolve("usb.img"), 256)));
        command(second, "cat mnt/usb/k.txt", "kept");
        command(second, "cat var/log/aeon.log", "fstab: 9 mnt/nowhere");
        command(second, "useradd bob", "added bob");
        command(second, "passwd bob", "New password");
        second.type("pw\r");
        assertTrue(second.waitFor("Retype", 20_000), second.output());
        second.type("pw\r");
        assertTrue(second.waitFor("password changed for bob", 20_000), second.output());
        command(second, "su bob", "$");
        command(second, "umount mnt/usb", "permission denied");
        command(second, "mkfs 1 yes", "permission denied");
        command(second, "mount", "drive 1");                         // looking is fine
    }
}
