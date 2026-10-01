package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** users, passwords and file permissions */
class ShellUsersTest {

    private static final String ENTER = "\r";

    private static void command(AeonSession s, String line, String expected) throws Exception {
        int before = s.output().length();
        s.type(line + ENTER);
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(expected))
            Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(expected),
                "after '" + line + "' expected '" + expected + "' in:\n" + s.output().substring(before)
                        + "\nfinished=" + s.finished + " failure=" + s.failure);
    }

    /** types a secret (not echoed) after the prompt 'prompt' has appeared, then waits for 'then' */
    private static void secret(AeonSession s, String prompt, String text, String then) throws Exception {
        int before = s.output().length();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(prompt)) Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(prompt), "no '" + prompt + "' in:\n" + s.output().substring(before));
        int mark = s.output().length();
        s.type(text + ENTER);
        deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(mark).contains(then)) Thread.sleep(20);
        assertTrue(s.output().substring(mark).contains(then), "after the secret expected '" + then + "' in:\n" + s.output().substring(mark));
        assertTrue(!s.output().substring(mark).contains(text + "\n"), "the secret was echoed");
    }

    /** types a visible line after 'prompt' has appeared, then waits for 'then' */
    private static void answer(AeonSession s, String prompt, String text, String then) throws Exception {
        int before = s.output().length();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(before).contains(prompt)) Thread.sleep(20);
        assertTrue(s.output().substring(before).contains(prompt), "no '" + prompt + "' in:\n" + s.output().substring(before));
        int mark = s.output().length();
        s.type(text + ENTER);
        deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(mark).contains(then)) Thread.sleep(20);
        assertTrue(s.output().substring(mark).contains(then), "after '" + text + "' expected '" + then + "' in:\n" + s.output().substring(mark));
    }

    private static void waitAfter(AeonSession s, int from, String text) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !s.output().substring(from).contains(text)) Thread.sleep(20);
        assertTrue(s.output().substring(from).contains(text), "expected '" + text + "' after " + from + " in:\n" + s.output().substring(from));
    }

    private static void asRoot(AeonSession s) throws Exception {
        command(s, "id", "uid=0(root)");
        command(s, "useradd alice", "added alice (uid 1000)");
        command(s, "passwd alice", "New password");
        secret(s, "", "wonderland", "Retype");
        secret(s, "", "wonderland", "password changed for alice");
    }

    @Test
    void aUserOwnsWhatTheyMakeAndCannotTouchWhatIsNotTheirs(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        asRoot(s);
        command(s, "su alice", "$");                              // root needs no password
        command(s, "id", "uid=1000(alice)");
        command(s, "whoami", "alice (user mode)");
        // the root folder is not alice's
        command(s, "echo x > f.txt", "permission denied");
        command(s, "cd home/alice", "$");
        command(s, "echo hello > f.txt", "$");
        command(s, "cat f.txt", "hello");
        command(s, "ls -l", "-rw-r--r-- alice");
        command(s, "chmod 600 f.txt", "$");
        command(s, "ls -l", "-rw------- alice");
        command(s, "stat f.txt", "owner alice (1000)");
        // the system's files
        command(s, "cat /etc/passwd", "alice:1000:1000:home/alice");
        command(s, "cat /etc/shadow", "permission denied");
        command(s, "echo x >> /etc/passwd", "permission denied");
        command(s, "rm /bin/ls.bl0c", "permission denied");
        command(s, "useradd bob", "permission denied");
        command(s, "chown alice /etc/passwd", "permission denied");
        command(s, "ls /root", "permission denied");
        // a pipeline works: its temporary files are in tmp/
        command(s, "echo one two three | wc", "1 lines, 3 words");
        // back to root, who sees alice's file
        command(s, "exit", "$");
        command(s, "id", "uid=0(root)");
        command(s, "cat /home/alice/f.txt", "hello");
        command(s, "ls -l /home/alice", "-rw------- alice");
        command(s, "chown root /home/alice/f.txt", "$");
        command(s, "stat /home/alice/f.txt", "owner root (0)");
    }

    @Test
    void suAsksForAPasswordAndTheShadowFileHoldsNoPlainText(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        asRoot(s);
        command(s, "su alice", "$");
        command(s, "su root", "Password:");
        secret(s, "", "guess", "su: wrong password");              // root has no password: locked for su
        command(s, "exit", "$");
        command(s, "passwd", "New password");                      // root sets its own: no old password asked
        secret(s, "", "r00t", "Retype");
        secret(s, "", "r00t", "password changed for root");
        command(s, "cat etc/shadow", "alice:");
        int before = s.output().length();
        command(s, "cat etc/shadow", "root:");
        assertTrue(!s.output().substring(before).contains("wonderland") && !s.output().substring(before).contains("r00t"), s.output());
        command(s, "su alice", "$");
        command(s, "su root", "Password:");
        secret(s, "", "r00t", "$");
        command(s, "id", "uid=0(root)");
        command(s, "exit", "$");
        command(s, "id", "uid=1000(alice)");
        command(s, "passwd", "Current password");
        secret(s, "", "wrong", "New password");
        secret(s, "", "x", "Retype");
        secret(s, "", "x", "passwd: wrong password");
    }

    @Test
    void theLoginPromptAppearsOnceRootHasAPassword(@TempDir Path dir) throws Exception {
        var first = AeonSession.shellOnOsDisk(dir);
        asRoot(first);
        command(first, "passwd", "New password");
        secret(first, "", "r00t", "Retype");
        secret(first, "", "r00t", "password changed for root");
        first.type("exit" + ENTER);
        first.thread.join(10_000);

        var second = new AeonSession();
        second.start(AeonSession.compile("init.bl0"), 1, vm -> {
            try {
                vm.attach_disk(new bl0.bl0jv2.cli.FileDisk(dir.resolve("d.img"), AeonImage.SECTORS));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        // one conversation with the login prompt: wait for each prompt in the text that follows the last answer
        assertTrue(second.waitFor("login:", 20_000), second.output());
        int at = second.output().length();
        second.type("alice" + ENTER);
        waitAfter(second, at, "Password:");
        second.type("nope" + ENTER);
        waitAfter(second, at, "login incorrect");
        at = second.output().length();
        waitAfter(second, 0, "login:");
        second.type("alice" + ENTER);
        waitAfter(second, at, "Password:");
        second.type("wonderland" + ENTER);
        waitAfter(second, at, "alice");
        command(second, "id", "uid=1000(alice)");
        command(second, "pwd", "/home/alice");                       // a user starts in their own folder
        command(second, "echo mine > note", "$");
        command(second, "cat note", "mine");
        command(second, "exit", "bye");
    }
}
