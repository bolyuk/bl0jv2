package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

// stdlib/str/base64.bl0 against the JDK's encoder
class Bl0jv2_Base64Test {

    private static String eval(Path dir, String expression) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/str/base64.bl0'; print " + expression + ";");
        return Bl0jv2_TestRunner.runFile(entry);
    }

    @Test
    void everyLengthRoundTripsAndAgreesWithTheJdk(@TempDir Path dir) throws IOException {
        Random random = new Random(1);
        for (int length = 0; length <= 7; length++) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            String expected = Base64.getEncoder().encodeToString(data);
            assertEquals(expected, eval(dir, "Base64.encode(" + Bl0jv2_Sha512Test.bytesLiteral(data) + ")"), "encode " + length);
            assertEquals(Bl0jv2_Sha512Test.bytesLiteral(data).replace(",", ", "),
                    eval(dir, "Base64.decode('" + expected + "')").trim().replace(",", ", ").replace(",  ", ", "), "decode " + length);
        }
        assertEquals("nil", eval(dir, "Base64.decode('a*b')"));
    }
}
