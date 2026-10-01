package bl0.bl0jv2.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The host's own terminal, for -k: puts it in raw mode so every key reaches the
 * guest as the terminal sends it (arrows are escape sequences, nothing is echoed
 * or line-buffered by the host - the guest's line editor does that), and reports
 * its size. Raw mode needs stty, i.e. a Unix-like terminal; without it (Windows,
 * or stdin that is not a terminal) the host terminal stays in its normal line
 * mode, which still works but then shows the host's own echo as well as the guest's.
 */
final class HostTerminal implements AutoCloseable {
    private final String saved;

    private HostTerminal(String saved) {
        this.saved = saved;
    }

    /** raw mode if it can be had, otherwise a terminal object that changes nothing */
    static HostTerminal enterRawMode() {
        String settings = stty("-g");
        if (settings == null || settings.isBlank()) return new HostTerminal(null);
        if (stty("-icanon -echo min 1 time 0") == null) return new HostTerminal(null);
        HostTerminal t = new HostTerminal(settings.trim());
        Runtime.getRuntime().addShutdownHook(new Thread(t::close));
        return t;
    }

    boolean isRaw() {
        return saved != null;
    }

    /** {columns, rows}: from the terminal, else COLUMNS/LINES, else 80x24 */
    static int[] size() {
        String s = stty("size");
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
        if (saved != null) stty(saved);
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
