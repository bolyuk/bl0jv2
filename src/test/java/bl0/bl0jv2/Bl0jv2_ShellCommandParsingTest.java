package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// aeon-os/shell.bl0's own command-line parsing (startsWith()/substringFrom()
// and the 'echo <text>' dispatch built on them) - the one genuinely new
// piece of logic that file adds on top of already-tested primitives
// (privilege rings: Bl0jv2_PrivilegeTest, syscalls: same, the keyboard
// ring buffer: Bl0jv2_KeyboardTest). Tested here as plain synchronous
// logic (println, not the syscall-gated console) since none of this
// depends on the keyboard/privilege machinery at all - shell.bl0 itself
// stays the full, manually-verified end-to-end demo.
class Bl0jv2_ShellCommandParsingTest {

    private static final String HELPERS =
            "def startsWith(s, prefix) { " +
            "  if (len(s) < len(prefix)) { return false; } " +
            "  i = 0; " +
            "  while (i < len(prefix)) { " +
            "    if (s[i] != prefix[i]) { return false; } " +
            "    i = i + 1; " +
            "  } " +
            "  return true; " +
            "} " +
            "def substringFrom(s, startIdx) { " +
            "  result = ''; " +
            "  i = startIdx; " +
            "  while (i < len(s)) { result = result + str(s[i]); i = i + 1; } " +
            "  return result; " +
            "} " +
            "def runCommand(line) { " +
            "  if (line == 'help') { print 'HELP'; return nil; } " +
            "  if (startsWith(line, 'echo ')) { print substringFrom(line, 5); return nil; } " +
            "  if (line == '') { return nil; } " +
            "  print line + ': command not found'; " +
            "} ";

    @Test
    void recognizesAnExactBuiltin() {
        assertEquals("HELP", run(HELPERS + "runCommand('help');"));
    }

    @Test
    void extractsTheArgumentAfterEcho() {
        assertEquals("hello world", run(HELPERS + "runCommand('echo hello world');"));
    }

    @Test
    void echoWithNoArgumentGivesAnEmptyString() {
        // 'echo ' itself is 5 chars - substringFrom(line, 5) on a line that
        // is exactly 'echo ' must return '', not throw or wrap around
        assertEquals("", run(HELPERS + "runCommand('echo ');"));
    }

    @Test
    void unknownCommandReportsNotFound() {
        assertEquals("bogus: command not found", run(HELPERS + "runCommand('bogus');"));
    }

    @Test
    void startsWithRejectsAPrefixLongerThanTheString() {
        // the len() check must short-circuit before any indexing happens -
        // an off-by-one here would index past the end of a short string
        assertEquals("nope: command not found", run(HELPERS + "runCommand('nope');"));
    }

    @Test
    void emptyLineDoesNothing() {
        assertEquals("", run(HELPERS + "runCommand('');"));
    }
}
