package bl0.bl0jv2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Bl0jv2_ReadTest {

    private final InputStream originalStdin = System.in;

    @AfterEach
    void restoreStdin() {
        System.setIn(originalStdin);
    }

    private void stdin(String content) {
        System.setIn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readReturnsALineFromStdin() {
        stdin("hello\n");
        assertEquals("hello", run("print read();"));
    }

    @Test
    void readReturnsNilAtEndOfInput() {
        stdin("");
        assertEquals("nil", run("print read();"));
    }

    @Test
    void readCanBeCombinedWithIntConversion() {
        stdin("21\n");
        assertEquals("42", run("print int(read()) * 2;"));
    }

    @Test
    void multipleReadsConsumeSuccessiveLines() {
        stdin("a\nb\n");
        assertEquals("a-b", run("x = read(); y = read(); print x + '-' + y;"));
    }
}
