package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** the sticky bit and groups */
class ShellGroupsTest {

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

    @Test
    void theStickyBitProtectsFilesInASharedFolder(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "ls -l /", "drwxrwxrwt root");                    // tmp/ is 1777
        command(s, "useradd alice", "added alice");
        command(s, "useradd bob", "added bob");
        command(s, "mkdir pub", "$");
        command(s, "chmod 777 pub", "$");
        command(s, "su alice", "$");
        command(s, "echo a > /tmp/alice.txt", "$");
        command(s, "echo a > /pub/alice.txt", "$");
        command(s, "exit", "$");
        command(s, "su bob", "$");
        command(s, "rm /tmp/alice.txt", "sticky folder");
        command(s, "mv /tmp/alice.txt /tmp/stolen.txt", "sticky folder");
        command(s, "cat /tmp/alice.txt", "a");                      // still there, still readable
        command(s, "rm /pub/alice.txt", "$");                       // pub is not sticky: Bob may remove it
        command(s, "ls /pub", "(empty)");
        command(s, "echo b > /tmp/bob.txt", "$");
        command(s, "rm /tmp/bob.txt", "$");                         // his own: fine
        command(s, "exit", "$");
        command(s, "chmod +t pub", "$");
        command(s, "ls -l /", "drwxrwxrwt root");
        command(s, "su alice", "$");
        command(s, "echo a > /pub/again.txt", "$");
        command(s, "exit", "$");
        command(s, "su bob", "$");
        command(s, "rm /pub/again.txt", "sticky folder");           // now it is
        command(s, "exit", "$");
        command(s, "rm /tmp/alice.txt", "$");                       // root may
        command(s, "chmod 1777 pub", "$");
        command(s, "chmod 12777 pub", "not a mode");
        command(s, "ls -l /", "rwxrwxrwt");
        command(s, "chmod -t pub", "$");
        command(s, "ls -l /", "drwxrwxrwx root");
    }

    @Test
    void groupsShareAccessAndAreManagedByRoot(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        command(s, "useradd alice", "added alice");
        command(s, "useradd bob", "added bob");
        command(s, "groupadd dev", "added group dev (gid 1002)");
        command(s, "groupadd dev", "that group exists");
        command(s, "usermod -aG dev alice", "alice is now in dev");
        command(s, "mkdir proj", "$");
        command(s, "chgrp dev proj", "$");
        command(s, "chmod 770 proj", "$");
        command(s, "ls -l /", "drwxrwx--- root     dev");
        command(s, "groups alice", "alice dev");
        command(s, "groups bob", "bob");
        command(s, "su alice", "$");
        command(s, "id", "groups=1000(alice),1002(dev)");
        command(s, "groups", "alice dev");
        command(s, "echo shared > /proj/a.txt", "$");
        command(s, "cat /proj/a.txt", "shared");
        command(s, "ls -l /proj", "alice    alice");                // a new file gets its maker's primary group
        command(s, "chgrp dev /proj/a.txt", "$");                   // a group of theirs: allowed
        command(s, "ls -l /proj", "alice    dev");
        command(s, "chgrp root /proj/a.txt", "only root may change an owner");   // a group they are not in
        command(s, "exit", "$");
        command(s, "su bob", "$");
        command(s, "cat /proj/a.txt", "permission denied");          // not in dev
        command(s, "ls /proj", "permission denied");
        command(s, "chgrp dev /proj/a.txt", "permission denied");
        command(s, "exit", "$");
        // a group counts from the next login: bob joins, and reads (the file is 644, group dev can read)
        command(s, "usermod -aG dev bob", "bob is now in dev");
        command(s, "su bob", "$");
        command(s, "cat /proj/a.txt", "shared");
        command(s, "echo more >> /proj/a.txt", "permission denied");   // group has only r on the file
        command(s, "exit", "$");
        command(s, "su alice", "$");
        command(s, "chmod 664 /proj/a.txt", "$");
        command(s, "exit", "$");
        command(s, "su bob", "$");
        command(s, "echo more >> /proj/a.txt", "$");
        command(s, "cat /proj/a.txt", "more");
        command(s, "exit", "$");
        // primary groups, and what removing does
        command(s, "groupdel alice", "primary group of alice");
        command(s, "groupdel dev", "removed group dev");
        command(s, "usermod -g dev bob", "no such group");
        command(s, "userdel alice", "removed alice");
        command(s, "cat etc/group", "bob:1001:");
        command(s, "id", "uid=0(root) gid=0(root) groups=0(root)");
    }
}
