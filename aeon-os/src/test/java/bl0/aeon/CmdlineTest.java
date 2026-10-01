package bl0.aeon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// lib/cmdline.bl0: quoting, pipes and redirections
class CmdlineTest {

    // each stage as  words | <in | >out (>> when appending)
    private static String parse(String line) throws Exception {
        String escaped = line.replace("\\", "\\\\").replace("'", "\\'");
        return AeonSession.runSnippet("import 'lib/cmdline.bl0'; " +
                "try { stages = Cmdline.parse('" + escaped + "'); r = ''; i = 0; " +
                "while (i < len(stages)) { s = stages[i]; if (i > 0) { r = r + ' || '; } " +
                "w = []; j = 0; while (j < len(s.words)) { push(w, '[' + s.words[j] + ']'); j += 1; } r = r + strJoin(w, ' '); " +
                "if (s.inFile != nil) { r = r + ' <' + s.inFile; } " +
                "if (s.outFile != nil) { r = r + (s.append ? ' >>' : ' >') + s.outFile; } i += 1; } print r; } " +
                "catch (e) { print 'ERR ' + str(e); }");
    }

    @Test
    void wordsAreSplitOnBlanks() throws Exception {
        assertEquals("[ls] [-l] [docs]", parse("  ls   -l\tdocs "));
        assertEquals("", parse("   "));
    }

    @Test
    void quotesKeepBlanksAndOperatorsAsText() throws Exception {
        assertEquals("[echo] [a  b] [c|d] [>x]", parse("echo \"a  b\" 'c|d' \">x\""));
        assertEquals("[echo] [] [x]", parse("echo \"\" x"));
        assertEquals("[say] [it's] [\"quoted\"]", parse("say \"it's\" '\"quoted\"'"));
        assertEquals("[a] [b c]", parse("a b' 'c"));
    }

    @Test
    void doubleQuotesKnowTheirEscapes() throws Exception {
        assertEquals("[echo] [one\ntwo\tx\"\\y]", parse("echo \"one\\ntwo\\tx\\\"\\\\y\""));
        assertEquals("[echo] [a\\qb]", parse("echo \"a\\qb\""));   // an unknown escape stays as written
    }

    @Test
    void aBackslashOutsideQuotesQuotesTheNextCharacter() throws Exception {
        assertEquals("[echo] [a b] [|] [>]", parse("echo a\\ b \\| \\>"));
    }

    @Test
    void pipesAndRedirectionsNeedNoSpaces() throws Exception {
        assertEquals("[cat] [a] <in || [grep] [x] || [wc] >out", parse("cat a<in|grep x|wc>out"));
        assertEquals("[echo] [hi] >>log", parse("echo hi>>log"));
    }

    @Test
    void syntaxErrorsAreReported() throws Exception {
        assertEquals("ERR syntax error: unterminated quote", parse("echo \"oops"));
        assertEquals("ERR syntax error: unterminated quote", parse("echo 'oops"));
        assertEquals("ERR syntax error: a line cannot end with a backslash", parse("echo x\\"));
        assertEquals("ERR syntax error: nothing before |", parse("| wc"));
        assertEquals("ERR syntax error: nothing after |", parse("ls |"));
        assertEquals("ERR syntax error: > needs a file name", parse("ls >"));
        assertEquals("ERR syntax error: < needs a file name", parse("cat < | wc"));
        assertEquals("ERR syntax error: a redirection needs a command", parse("> out"));
        assertEquals("ERR syntax error: only the first command can read from a file", parse("ls | wc < x"));
        assertEquals("ERR syntax error: a redirected output cannot be piped on", parse("ls > x | wc"));
    }
}
