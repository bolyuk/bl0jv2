package bl0.bl0jv2.cli;

import java.util.LinkedHashMap;
import java.util.Map;

public class Timer {
    private final Map<String, Long> marks = new LinkedHashMap<>();
    private long origin = System.nanoTime();

    public void mark(String label) {
        marks.put(label, System.nanoTime());
    }

    public void report() {
        long prev = origin;
        for (var e : marks.entrySet()) {
            long ms = (e.getValue() - prev) / 1_000_000;
            System.out.printf("%-20s %d ms%n", e.getKey(), ms);
            prev = e.getValue();
        }
        System.out.printf("%-20s %d ms%n", "TOTAL",
                (prev - origin) / 1_000_000);
    }
}
