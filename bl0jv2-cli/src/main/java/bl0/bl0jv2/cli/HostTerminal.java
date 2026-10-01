package bl0.bl0jv2.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The host's own terminal, for -k: puts it in raw mode so every key reaches the
 * guest as the terminal sends it (arrows are escape sequences, nothing is echoed
 * or line-buffered by the host - the guest's line editor does that), and reports
 * its size. On Unix-like systems that is stty; on Windows the console's input and
 * output modes are changed (virtual-terminal input, so arrows arrive as escape
 * sequences, no line buffering, no echo, Ctrl-C delivered as a key; virtual-terminal
 * output, so escape sequences are drawn; UTF-8 code pages) by a PowerShell child that
 * shares this console, with kernel32 called through Add-Type - nothing to install. If
 * neither works (stdin is not a terminal) the host terminal stays in its normal line
 * mode, which still works but then shows the host's own echo as well as the guest's.
 */
final class HostTerminal implements AutoCloseable {
    private final String saved;

    private HostTerminal(String saved) {
        this.saved = saved;
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static int[] windowsSize;     // columns, rows, as the console reported them when it was set up

    /** raw mode if it can be had, otherwise a terminal object that changes nothing */
    static HostTerminal enterRawMode() {
        if (WINDOWS) return enterWindows();
        String settings = stty("-g");
        if (settings == null || settings.isBlank()) return new HostTerminal(null);
        if (stty("-icanon -echo min 1 time 0") == null) return new HostTerminal(null);
        HostTerminal t = new HostTerminal(settings.trim());
        Runtime.getRuntime().addShutdownHook(new Thread(t::close));
        return t;
    }

    // ---- Windows ----

    // Prints "<input mode> <output mode> <input code page> <output code page> <columns> <rows>" for the
    // console as it was, then switches it: no line input, no echo, no Ctrl-C processing (Ctrl-C becomes the
    // character 3), virtual-terminal input; virtual-terminal output; UTF-8. CONIN$/CONOUT$ name this
    // console whatever the child's own standard streams are redirected to.
    static final String WINDOWS_ENTER = String.join("\n",
            "$sig = @'",
            "using System; using System.Runtime.InteropServices;",
            "public static class K {",
            "  [DllImport(\"kernel32.dll\", CharSet=CharSet.Unicode, SetLastError=true)] public static extern IntPtr CreateFileW(string n, uint a, uint s, IntPtr sec, uint d, uint f, IntPtr t);",
            "  [DllImport(\"kernel32.dll\")] public static extern bool GetConsoleMode(IntPtr h, out uint m);",
            "  [DllImport(\"kernel32.dll\")] public static extern bool SetConsoleMode(IntPtr h, uint m);",
            "  [DllImport(\"kernel32.dll\")] public static extern uint GetConsoleCP();",
            "  [DllImport(\"kernel32.dll\")] public static extern uint GetConsoleOutputCP();",
            "  [DllImport(\"kernel32.dll\")] public static extern bool SetConsoleCP(uint c);",
            "  [DllImport(\"kernel32.dll\")] public static extern bool SetConsoleOutputCP(uint c);",
            "}",
            "'@",
            "Add-Type -TypeDefinition $sig",
            "$in = [K]::CreateFileW('CONIN$', 3221225472, 3, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)",
            "$out = [K]::CreateFileW('CONOUT$', 3221225472, 3, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)",
            "[uint32]$im = 0; [uint32]$om = 0",
            "if (-not [K]::GetConsoleMode($in, [ref]$im)) { exit 1 }",
            "if (-not [K]::GetConsoleMode($out, [ref]$om)) { exit 1 }",
            "$cp = [K]::GetConsoleCP(); $ocp = [K]::GetConsoleOutputCP()",
            "$cols = 80; $rows = 24",
            "try { $cols = [Console]::WindowWidth; $rows = [Console]::WindowHeight } catch { }",
            "Write-Output ('{0} {1} {2} {3} {4} {5}' -f $im, $om, $cp, $ocp, $cols, $rows)",
            "[K]::SetConsoleMode($in, (($im -band (-bnot 7)) -bor 512)) | Out-Null",
            "[K]::SetConsoleMode($out, ($om -bor 5)) | Out-Null",
            "[K]::SetConsoleCP(65001) | Out-Null",
            "[K]::SetConsoleOutputCP(65001) | Out-Null");

    /** the script that puts the console back as WINDOWS_ENTER found it ("input output inputCP outputCP") */
    static String windowsRestore(String state) {
        String[] v = state.trim().split("\\s+");
        return WINDOWS_ENTER.substring(0, WINDOWS_ENTER.indexOf("$cp = ")) +
                "[K]::SetConsoleMode($in, " + v[0] + ") | Out-Null\n" +
                "[K]::SetConsoleMode($out, " + v[1] + ") | Out-Null\n" +
                "[K]::SetConsoleCP(" + v[2] + ") | Out-Null\n" +
                "[K]::SetConsoleOutputCP(" + v[3] + ") | Out-Null\n";
    }

    /** the first six numbers of what WINDOWS_ENTER printed, or null when it is not that */
    static int[] parseWindowsState(String output) {
        if (output == null) return null;
        String[] p = output.trim().split("\\s+");
        if (p.length != 6) return null;
        int[] v = new int[6];
        try {
            for (int i = 0; i < 6; i++) v[i] = (int) Long.parseLong(p[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return v;
    }

    private static HostTerminal enterWindows() {
        String printed = powershell(WINDOWS_ENTER);
        int[] v = parseWindowsState(printed);
        if (v == null) {
            System.err.println("warning: could not switch the console to raw mode (is PowerShell available?); "
                    + "typing will be echoed twice and lines will arrive whole");
            return new HostTerminal(null);
        }
        if (v[4] > 0 && v[5] > 0) windowsSize = new int[]{v[4], v[5]};
        HostTerminal t = new HostTerminal("win:" + v[0] + " " + v[1] + " " + v[2] + " " + v[3]);
        Runtime.getRuntime().addShutdownHook(new Thread(t::close));
        return t;
    }

    // runs a PowerShell script (passed encoded: no quoting to get wrong) in this console; its output, or null
    private static String powershell(String script) {
        try {
            String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-EncodedCommand", encoded)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            return out;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    boolean isRaw() {
        return saved != null;
    }

    /** {columns, rows}: from the terminal, else COLUMNS/LINES, else 80x24 */
    static int[] size() {
        if (windowsSize != null) return windowsSize;
        String s = WINDOWS ? null : stty("size");
        if (s != null) {
            String[] p = s.trim().split("\\s+");
            if (p.length == 2) {
                try {
                    int rows = Integer.parseInt(p[0]), cols = Integer.parseInt(p[1]);
                    if (rows > 0 && cols > 0) return new int[]{cols, rows};
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new int[]{envInt("COLUMNS", 80), envInt("LINES", 24)};
    }

    private static int envInt(String name, int fallback) {
        try {
            return Integer.parseInt(System.getenv(name));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Override
    public void close() {
        if (saved == null) return;
        if (saved.startsWith("win:")) powershell(windowsRestore(saved.substring(4)));
        else stty(saved);
    }

    // runs stty against the controlling terminal; null if that is not possible
    private static String stty(String args) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty 2>/dev/null")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(2, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            return out;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }
}
