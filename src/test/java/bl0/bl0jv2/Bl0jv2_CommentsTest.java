package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Bl0jv2_CommentsTest {

    @Test
    void lineCommentIsIgnored() {
        assertEquals("5", run("// this is a comment\nprint 5;"));
    }

    @Test
    void trailingLineCommentIsIgnored() {
        assertEquals("5", run("print 5; // trailing comment"));
    }

    @Test
    void commentedOutStatementDoesNotRun() {
        assertEquals("a", run("// print 'b';\nprint 'a';"));
    }

    @Test
    void commentAtEndOfFileWithNoTrailingNewline() {
        assertEquals("5", run("print 5; // no newline after this"));
    }
}
