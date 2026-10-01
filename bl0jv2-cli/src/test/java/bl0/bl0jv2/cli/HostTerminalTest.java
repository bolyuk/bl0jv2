package bl0.bl0jv2.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// the parts of the Windows console setup that can be checked anywhere: the state it prints, and the script
// that puts the console back
class HostTerminalTest {

    @Test
    void theStatePrintedByTheEnterScriptIsParsed() {
        assertArrayEquals(new int[]{503, 7, 437, 437, 120, 30}, HostTerminal.parseWindowsState("503 7 437 437 120 30\r\n"));
        assertNull(HostTerminal.parseWindowsState("not numbers at all here x y"));
        assertNull(HostTerminal.parseWindowsState("1 2 3"));
        assertNull(HostTerminal.parseWindowsState(null));
    }

    @Test
    void theRestoreScriptSetsTheSavedModesAndPageBack() {
        String script = HostTerminal.windowsRestore("503 7 437 850");
        assertTrue(script.contains("SetConsoleMode($in, 503)"), script);
        assertTrue(script.contains("SetConsoleMode($out, 7)"), script);
        assertTrue(script.contains("SetConsoleCP(437)"), script);
        assertTrue(script.contains("SetConsoleOutputCP(850)"), script);
        assertTrue(script.contains("CreateFileW('CONIN$'"), "it opens the console itself");
        assertFalse(script.contains("Write-Output"), "it only restores");
    }

    @Test
    void theEnterScriptAsksForRawVirtualTerminalInputAndUtf8() {
        String s = HostTerminal.WINDOWS_ENTER;
        assertTrue(s.contains("-bnot 7"), "no line input, echo or Ctrl-C processing");
        assertTrue(s.contains("-bor 512"), "virtual-terminal input");
        assertTrue(s.contains("($om -bor 5)"), "virtual-terminal output and processed output");
        assertTrue(s.contains("SetConsoleCP(65001)") && s.contains("SetConsoleOutputCP(65001)"));
    }
}
