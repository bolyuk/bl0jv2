package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

// stdlib/crypto/sha512.bl0 against the JDK's SHA-512
class Bl0jv2_Sha512Test {

    static String bytesLiteral(byte[] data) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < data.length; i++) sb.append(i == 0 ? "" : ",").append(data[i] & 255);
        return sb.append("]").toString();
    }

    static byte[] parse(String printed) {
        String s = printed.trim().replace("[", "").replace("]", "").replace(" ", "");
        if (s.isEmpty()) return new byte[0];
        String[] parts = s.split(",");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i]);
        return out;
    }

    private static byte[] digest(Path dir, byte[] message) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/crypto/sha512.bl0'; print Sha512.digest(" + bytesLiteral(message) + ");");
        return parse(Bl0jv2_TestRunner.runFile(entry));
    }

    @Test
    void messagesOfEveryInterestingLength(@TempDir Path dir) throws Exception {
        var jdk = MessageDigest.getInstance("SHA-512");
        Random random = new Random(7);
        // 111, 112 (the padding no longer fits in the block), 127, 128, 129, and a few blocks
        for (int length : new int[]{0, 1, 3, 111, 112, 113, 127, 128, 129, 300}) {
            byte[] message = new byte[length];
            random.nextBytes(message);
            assertEquals(java.util.HexFormat.of().formatHex(jdk.digest(message)),
                    java.util.HexFormat.of().formatHex(digest(dir, message)), "length " + length);
        }
    }
}
