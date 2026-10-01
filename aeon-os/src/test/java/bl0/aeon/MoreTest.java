package bl0.aeon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** the pager: a screenful, then --More-- until a key is pressed */
class MoreTest {

    private static void until(AeonSession s, String text) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !s.screen().screenText().contains(text)) Thread.sleep(20);
        assertTrue(s.screen().screenText().contains(text), "expected '" + text + "' on:\n" + s.screen().screenText());
    }

    @Test
    void pagesThroughALongFileAndQuits(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        s.type("seq 100 > n.txt\r");
        s.type("more n.txt\r");
        until(s, "--More--");
        String first = s.screen().screenText();
        assertTrue(first.contains("\n10\n") && first.contains("\n23\n"), first);
        assertTrue(!first.contains("\n60\n"), "only a screenful is shown:\n" + first);
        s.type(" ");
        until(s, "(46%)");
        assertTrue(s.screen().screenText().contains("\n46\n"), s.screen().screenText());
        s.type("\r");                                  // one more line
        until(s, "(47%)");
        s.type("q");
        until(s, "$");
        assertTrue(!s.screen().screenText().contains("--More--"), s.screen().screenText());
        // still usable
        s.type("echo back\r");
        until(s, "back");
    }

    @Test
    void justCopiesWhenTheOutputIsAPipe(@TempDir Path dir) throws Exception {
        var s = AeonSession.shellOnOsDisk(dir);
        s.type("seq 100 | more | wc\r");
        until(s, "100 lines");
    }
}
