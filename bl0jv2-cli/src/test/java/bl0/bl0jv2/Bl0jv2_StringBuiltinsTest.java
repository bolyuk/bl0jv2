package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import static org.junit.jupiter.api.Assertions.assertEquals;

// strSub / strFind / strUpper / strLower / strJoin and the stdlib built on them
class Bl0jv2_StringBuiltinsTest {

    private static String run(Path dir, String body) throws IOException {
        return run(dir, body, vm -> { });
    }

    private static String run(Path dir, String body, Consumer<Bl0jv2_jVM> configure) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry,
                "import 'stdlib/arrlib.bl0'; import 'stdlib/str/case.bl0'; import 'stdlib/str/find.bl0'; " +
                "import 'stdlib/str/split.bl0'; import 'stdlib/str/substr.bl0'; import 'stdlib/str/trim.bl0'; " + body);
        return Bl0jv2_TestRunner.runFile(entry, configure);
    }

    @Test
    void substring(@TempDir Path dir) throws IOException {
        assertEquals("ell||hello|o", run(dir,
                "s = 'hello'; print strSub(s, 1, 4) + '|' + strSub(s, 2, 2) + '|' + strSub(s, 0, 5) + '|' + strSub(s, 4, 5);"));
    }

    @Test
    void substringOutsideTheStringIsAnError(@TempDir Path dir) throws IOException {
        assertEquals("strSub: range [2, 9) is outside the string (length 5)", run(dir,
                "try { x = strSub('hello', 2, 9); } catch (e) { print e; }"));
        assertEquals("strSub: range [-1, 2) is outside the string (length 5)", run(dir,
                "try { x = strSub('hello', -1, 2); } catch (e) { print e; }"));
        assertEquals("strSub: range [3, 1) is outside the string (length 5)", run(dir,
                "try { x = strSub('hello', 3, 1); } catch (e) { print e; }"));
    }

    @Test
    void find(@TempDir Path dir) throws IOException {
        assertEquals("2|-1|0|3|0", run(dir,
                "print str(strFind('abcabc', 'ca', 0)) + '|' + str(strFind('abc', 'x', 0)) + '|' + str(strFind('abc', '', 0)) + " +
                "'|' + str(strFind('abcabc', 'abc', 1)) + '|' + str(Find.find('abc', 'a'));"));
    }

    @Test
    void findNotFoundIsMinusOneNotAnError(@TempDir Path dir) throws IOException {
        assertEquals("-1", run(dir, "print Find.find('hello', 'z');"));
    }

    @Test
    void caseConversionTouchesOnlyAsciiLetters(@TempDir Path dir) throws IOException {
        assertEquals("HELLO, WORLD 1!|hello, world 1!", run(dir,
                "print Case.upper('Hello, World 1!') + '|' + Case.lower('Hello, World 1!');"));
    }

    @Test
    void joinShowsElementsLikeStr(@TempDir Path dir) throws IOException {
        assertEquals("1, a, true, [2, 3]|", run(dir,
                "print Arr.join([1, 'a', true, [2, 3]], ', ') + '|' + Arr.join([], ',');"));
    }

    @Test
    void splitOnASingleCharacter(@TempDir Path dir) throws IOException {
        assertEquals("[a, b, c]", run(dir, "print Split.split('a,b,c', ',');"));
    }

    @Test
    void splitKeepsEmptyPiecesAtTheEdges(@TempDir Path dir) throws IOException {
        assertEquals("[, a, , b, ]", run(dir, "print Split.split(',a,,b,', ',');"));
    }

    @Test
    void splitOnAMultiCharacterSeparator(@TempDir Path dir) throws IOException {
        assertEquals("[one, two, three]", run(dir, "print Split.split('one::two::three', '::');"));
    }

    @Test
    void splitOnTheEmptySeparatorGivesCharacters(@TempDir Path dir) throws IOException {
        assertEquals("[a, b, c]", run(dir, "print Split.split('abc', '');"));
    }

    @Test
    void splitWithNoSeparatorPresentGivesTheWholeString(@TempDir Path dir) throws IOException {
        assertEquals("[abc]", run(dir, "print Split.split('abc', ',');"));
    }

    @Test
    void trimStillWorks(@TempDir Path dir) throws IOException {
        assertEquals("[hi]", run(dir, "print '[' + Trim.trim('  \t hi \n') + ']';"));
    }

    @Test
    void bigStringOperationsNeedAConstantNumberOfHeapEntries(@TempDir Path dir) throws IOException {
        // 100000 characters: the per-character versions would need 100000+ entries
        assertEquals("100000|100000|true", run(dir,
                "s = 'ab' * 50000; u = Case.upper(s); parts = Split.split(s, 'b'); " +
                "print str(len(u)) + '|' + str(len(Arr.join(parts, 'b'))) + '|' + str(Find.find(u, 'AB') == 0);",
                vm -> vm.set_max_heap_entries(60000)));
    }
}
