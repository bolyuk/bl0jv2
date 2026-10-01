package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the cron table syntax (lib/cron.bl0) and crond running jobs by the clock */
class CronTest {

    // t = [year, month, day, hour, minute, second, weekday]; the entry given as a crontab line without a user
    private static String matches(String line, String time) throws Exception {
        String escaped = line.replace("\\", "\\\\").replace("'", "\\'");
        return AeonSession.runSnippet("import 'lib/cron.bl0'; r = Cron.parse('" + escaped + "', false, 'u'); " +
                "if (len(r[1]) > 0) { print 'ERR ' + r[1][0]; } else { print str(Cron.matches(r[0][0], " + time + ")); }");
    }

    @Test
    void fieldsHaveStarsNumbersRangesListsAndSteps() throws Exception {
        // 2026-10-01 03:04 is a Thursday (4)
        String t = "[2026, 10, 1, 3, 4, 0, 4]";
        assertEquals("true", matches("* * * * * x", t));
        assertEquals("true", matches("4 3 * * * x", t));
        assertEquals("false", matches("5 3 * * * x", t));
        assertEquals("true", matches("*/2 * * * * x", t));
        assertEquals("false", matches("*/3 * * * * x", t));
        assertEquals("true", matches("0-10/2 1,2,3 * * * x", t));
        assertEquals("true", matches("1-5 * * 10 * x", t));
        assertEquals("false", matches("* * * 9 * x", t));
        assertEquals("true", matches("* * * * 4 x", t));
        assertEquals("false", matches("* * * * 5 x", t));
        assertEquals("true", matches("* * * * 1-5 x", t));
        assertEquals("true", matches("* * * * 7 x", "[2026, 10, 4, 3, 4, 0, 0]"));      // 7 is Sunday
    }

    @Test
    void dayOfMonthAndDayOfWeekOrWhenBothAreRestricted() throws Exception {
        // the 15th or a Thursday
        assertEquals("true", matches("* * 15 * 4 x", "[2026, 10, 1, 3, 4, 0, 4]"));    // Thursday, not the 15th
        assertEquals("true", matches("* * 15 * 4 x", "[2026, 10, 15, 3, 4, 0, 4]"));   // the 15th
        assertEquals("false", matches("* * 15 * 4 x", "[2026, 10, 2, 3, 4, 0, 5]"));   // neither
        assertEquals("false", matches("* * 15 * * x", "[2026, 10, 1, 3, 4, 0, 4]"));   // only the day of the month: AND with *
    }

    @Test
    void shortcutsAndErrors() throws Exception {
        assertEquals("true", matches("@hourly x", "[2026, 10, 1, 3, 0, 0, 4]"));
        assertEquals("false", matches("@hourly x", "[2026, 10, 1, 3, 1, 0, 4]"));
        assertEquals("true", matches("@daily x", "[2026, 10, 1, 0, 0, 0, 4]"));
        assertEquals("true", matches("@weekly x", "[2026, 10, 4, 0, 0, 0, 0]"));
        assertEquals("true", matches("@monthly x", "[2026, 10, 1, 0, 0, 0, 4]"));
        assertEquals("true", matches("@yearly x", "[2026, 1, 1, 0, 0, 0, 4]"));
        assertEquals("false", matches("@reboot x", "[2026, 10, 1, 3, 4, 0, 4]"));      // only when crond starts
        assertEquals("ERR line 1: bad minute field 61", matches("61 * * * * x", "[2026, 10, 1, 3, 4, 0, 4]"));
        assertEquals("ERR line 1: bad hour field 24", matches("0 24 * * * x", "[2026, 10, 1, 3, 4, 0, 4]"));
        assertEquals("ERR line 1: bad day-of-month field 0", matches("0 0 0 * * x", "[2026, 10, 1, 3, 4, 0, 4]"));
        assertEquals("ERR line 1: bad month field 13", matches("0 0 1 13 * x", "[2026, 10, 1, 3, 4, 0, 4]"));
        assertEquals("ERR line 1: five time fields and a command are needed", matches("0 0 1 1", "[2026, 10, 1, 3, 4, 0, 4]"));
        assertEquals("ERR line 1: there is no command", matches("@daily", "[2026, 10, 1, 3, 4, 0, 4]"));
        assertEquals("ERR line 1: unknown @sometimes", matches("@sometimes x", "[2026, 10, 1, 3, 4, 0, 4]"));
        assertEquals("ERR line 1: bad minute field */0", matches("*/0 * * * * x", "[2026, 10, 1, 3, 4, 0, 4]"));
    }

    @Test
    void commentsAndEmptyLinesAreSkippedAndAUserFieldIsRead() throws Exception {
        assertEquals("2 alice echo hi there", AeonSession.runSnippet("import 'lib/cron.bl0'; " +
                "r = Cron.parse('# a comment\\n\\n* * * * * alice echo hi there\\n@reboot bob true\\n', true, 'x'); " +
                "print str(len(r[0])) + ' ' + r[0][0][1] + ' ' + r[0][0][2];"));
    }

    // ---- crond ----

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

    /** waits until 'cat file' has shown 'text' (the job has run) */
    private static void eventually(AeonSession s, String file, String text) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            int before = s.output().length();
            s.type("cat " + file + "\r");
            Thread.sleep(400);
            last = s.output().substring(before);
            if (last.contains(text)) return;
        }
        throw new AssertionError("'" + text + "' never appeared in " + file + ": " + last);
    }

    @Test
    void crondRunsTheSystemTableOnTheMinute(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "echo '* * * * * root echo tick >> /var/log/ticks' > /etc/crontab", "$");
        command(s, "echo '*/2 * * * * root date >> /var/log/dates' >> /etc/crontab", "$");
        command(s, "echo '@reboot root echo booted >> /var/log/ticks' >> /etc/crontab", "$");
        command(s, "crond &", "[2] crond");
        eventually(s, "/var/log/ticks", "booted");                 // @reboot, at the first look at the clock
        s.clockAt(12, 1, 0);
        eventually(s, "/var/log/ticks", "tick");
        s.clockAt(12, 2, 0);
        eventually(s, "/var/log/dates", "Thu 2026-10-01 12:02:00 UTC");   // every other minute: the 12:02 one
        // a minute that passes with the clock unchanged runs nothing twice
        Thread.sleep(800);
        command(s, "wc /var/log/ticks", "4 lines");                 // booted, then 12:00 (the minute it started in), 12:01, 12:02
        command(s, "cat /var/log/aeon.log", "cron: root: echo tick");
        command(s, "kill 2", "$");
    }

    @Test
    void aUsersOwnTableRunsAsThemInTheirHome(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "useradd alice", "added alice");
        command(s, "echo '* * * * * date' > /tmp/mine", "$");
        command(s, "echo '*/5 * * * * seq 3 | wc > counted.txt' >> /tmp/mine", "$");
        command(s, "crond &", "[2] crond");
        command(s, "su alice", "$");
        command(s, "crontab /tmp/mine", "crontab installed: 2 jobs");
        command(s, "crontab -l", "seq 3 | wc");
        command(s, "ls -l /var/cron", "-rw------- alice");
        command(s, "exit", "$");
        s.clockAt(12, 5, 0);
        eventually(s, "/home/alice/.cron.out", "UTC");             // the date job's output, in alice's home
        eventually(s, "/home/alice/counted.txt", "3 lines");       // a pipeline, redirected, in her home
        command(s, "ls -l /home/alice", "alice    alice");           // made by her, not by root
        command(s, "cat /var/log/aeon.log", "cron: alice: date");
        command(s, "kill 2", "$");
    }

    @Test
    void aBadTableIsRefusedAndAForgedOneIsIgnored(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir, 4);
        command(s, "useradd alice", "added alice");
        command(s, "useradd bob", "added bob");
        command(s, "echo '61 * * * * date' > /tmp/bad", "$");
        command(s, "su alice", "$");
        command(s, "crontab /tmp/bad", "bad minute field 61");
        command(s, "crontab -l", "no crontab for alice");           // nothing was installed
        // alice writes a file called bob into var/cron: it is hers, not bob's
        command(s, "echo '* * * * * echo forged >> /tmp/forged' > /var/cron/bob", "$");
        command(s, "exit", "$");
        command(s, "crond &", "[2] crond");
        s.clockAt(12, 1, 0);
        Thread.sleep(2500);
        command(s, "ls /tmp", "bad");                               // still there ...
        int before = s.output().length();
        command(s, "cat /tmp/forged", "no such file");              // ... and the forged job never ran
        command(s, "cat /var/log/aeon.log", "var/cron/bob ignored");
        command(s, "kill 2", "$");
    }
}
