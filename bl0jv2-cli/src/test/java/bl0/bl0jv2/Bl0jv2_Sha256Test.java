package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// stdlib/crypto/sha256.bl0 against the published test vectors
class Bl0jv2_Sha256Test {

    private static String eval(Path dir, String expression) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/crypto/sha256.bl0'; print " + expression + ";");
        return Bl0jv2_TestRunner.runFile(entry);
    }

    private static String hex(Path dir, String text) throws IOException {
        return eval(dir, "Sha256.hex('" + text + "')");
    }

    @Test
    void shortMessages(@TempDir Path dir) throws IOException {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(dir, ""));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(dir, "abc"));
        assertEquals("d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
                hex(dir, "The quick brown fox jumps over the lazy dog"));
    }

    @Test
    void aMessageThatNeedsASecondBlockForThePadding(@TempDir Path dir) throws IOException {
        // 56 bytes: the length no longer fits in the first block
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
                hex(dir, "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"));
    }

    @Test
    void textIsHashedAsUtf8(@TempDir Path dir) throws IOException {
        // 'é' is the two bytes C3 A9
        assertEquals(hex(dir, "é"), eval(dir, "Sha256.hexOfBytes([195, 169])"));
    }
}
