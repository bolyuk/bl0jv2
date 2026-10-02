package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

// stdlib/crypto/chacha20.bl0 and poly1305.bl0 against RFC 8439, the JDK's ChaCha20 and a BigInteger Poly1305
class Bl0jv2_ChaChaPolyTest {

    private static String eval(Path dir, String library, String expression) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/crypto/" + library + ".bl0'; print " + expression + ";");
        return Bl0jv2_TestRunner.runFile(entry);
    }

    private static String lit(byte[] b) { return Bl0jv2_Sha512Test.bytesLiteral(b); }

    private static String hex(String printed) { return HexFormat.of().formatHex(Bl0jv2_Sha512Test.parse(printed)); }

    /** Poly1305 by the book, with BigInteger */
    static byte[] poly(byte[] key, byte[] msg) {
        byte[] rb = java.util.Arrays.copyOf(key, 16);
        rb[3] &= 15; rb[7] &= 15; rb[11] &= 15; rb[15] &= 15;
        rb[4] &= 252; rb[8] &= 252; rb[12] &= 252;
        BigInteger r = le(rb), s = le(java.util.Arrays.copyOfRange(key, 16, 32));
        BigInteger p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5)), h = BigInteger.ZERO;
        for (int i = 0; i < msg.length; i += 16) {
            int n = Math.min(16, msg.length - i);
            byte[] block = new byte[n + 1];
            System.arraycopy(msg, i, block, 0, n);
            block[n] = 1;
            h = h.add(le(block)).multiply(r).mod(p);
        }
        BigInteger t = h.add(s).mod(BigInteger.ONE.shiftLeft(128));
        byte[] out = new byte[16];
        byte[] tb = t.toByteArray();
        for (int i = 0; i < 16 && i < tb.length; i++) out[i] = tb[tb.length - 1 - i];
        return out;
    }

    private static BigInteger le(byte[] b) {
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) be[i] = b[b.length - 1 - i];
        return new BigInteger(1, be);
    }

    @Test
    void chachaMatchesTheJdkStream(@TempDir Path dir) throws Exception {
        Random random = new Random(3);
        for (int length : new int[]{1, 64, 65, 200}) {
            byte[] key = new byte[32], seq = new byte[8], data = new byte[length];
            random.nextBytes(key); random.nextBytes(seq); random.nextBytes(data);
            byte[] nonce12 = new byte[12];
            System.arraycopy(seq, 0, nonce12, 4, 8);
            Cipher c = Cipher.getInstance("ChaCha20");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new ChaCha20ParameterSpec(nonce12, 1));
            assertEquals(HexFormat.of().formatHex(c.doFinal(data)),
                    hex(eval(dir, "chacha20", "ChaCha20.crypt(" + lit(key) + ", 1, " + lit(seq) + ", " + lit(data) + ")")), "length " + length);
        }
    }

    @Test
    void poly1305Rfc8439Vector(@TempDir Path dir) throws Exception {
        byte[] key = HexFormat.of().parseHex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b");
        byte[] msg = "Cryptographic Forum Research Group".getBytes();
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", hex(eval(dir, "poly1305", "Poly1305.mac(" + lit(key) + ", " + lit(msg) + ")")));
    }

    @Test
    void poly1305MatchesBigIntegerOnRandomMessages(@TempDir Path dir) throws Exception {
        Random random = new Random(9);
        for (int length : new int[]{0, 1, 15, 16, 17, 33, 100}) {
            byte[] key = new byte[32], msg = new byte[length];
            random.nextBytes(key); random.nextBytes(msg);
            assertEquals(HexFormat.of().formatHex(poly(key, msg)),
                    hex(eval(dir, "poly1305", "Poly1305.mac(" + lit(key) + ", " + lit(msg) + ")")), "length " + length);
        }
        // the extremes: all ones, which makes the reduction work
        byte[] key = new byte[32], msg = new byte[48];
        java.util.Arrays.fill(key, (byte) 0xff); java.util.Arrays.fill(msg, (byte) 0xff);
        assertEquals(HexFormat.of().formatHex(poly(key, msg)), hex(eval(dir, "poly1305", "Poly1305.mac(" + lit(key) + ", " + lit(msg) + ")")));
    }
}
