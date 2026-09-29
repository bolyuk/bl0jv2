package bl0.bl0jv2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
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

    // set_in_reader() mirrors set_out_writer() - a host can supply its own
    // input source without ever touching System.in (unlike every other
    // test in this file, which redirects the real System.in)
    @Test
    void setInReaderSuppliesInputWithoutTouchingSystemIn() {
        assertEquals("hello", run("print read();",
                vm -> vm.set_in_reader(new StringReader("hello\n"))));
    }
}
