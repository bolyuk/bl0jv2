package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// stdlib/crypto/ed25519.bl0 against RFC 8032 and the JDK's Ed25519 (which is deterministic too: the signatures are equal)
class Bl0jv2_Ed25519Test {

    private static String eval(Path dir, String expression) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/crypto/ed25519.bl0'; print " + expression + ";");
        return Bl0jv2_TestRunner.runFile(entry);
    }

    private static String hexOf(String printed) {
        return HexFormat.of().formatHex(Bl0jv2_Sha512Test.parse(printed));
    }

    private static String lit(byte[] b) { return Bl0jv2_Sha512Test.bytesLiteral(b); }

    @Test
    void rfc8032TestVectors(@TempDir Path dir) throws Exception {
        byte[] seed = HexFormat.of().parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", hexOf(eval(dir, "Ed25519.publicKey(" + lit(seed) + ")")));
        assertEquals("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
                hexOf(eval(dir, "Ed25519.sign(" + lit(seed) + ", [])")));
        byte[] seed2 = HexFormat.of().parseHex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
        assertEquals("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
                hexOf(eval(dir, "Ed25519.sign(" + lit(seed2) + ", [114])")));
    }

    @Test
    void signaturesEqualTheJdksAndVerify(@TempDir Path dir) throws Exception {
        Random random = new Random(5);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        for (int length : new int[]{0, 20, 150}) {
            byte[] seed = new byte[32];
            random.nextBytes(seed);
            byte[] message = new byte[length];
            random.nextBytes(message);
            PrivateKey key = kf.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(message);
            String expected = HexFormat.of().formatHex(s.sign());
            String sig = eval(dir, "Ed25519.sign(" + lit(seed) + ", " + lit(message) + ")");
            assertEquals(expected, hexOf(sig), "length " + length);
            String pub = eval(dir, "Ed25519.publicKey(" + lit(seed) + ")");
            assertEquals("true", eval(dir, "Ed25519.verify(" + pub + ", " + lit(message) + ", " + sig + ")"));
            byte[] bad = message.clone();
            if (bad.length > 0) bad[0] ^= 1;
            String other = bad.length > 0 ? lit(bad) : "[1]";
            assertEquals("false", eval(dir, "Ed25519.verify(" + pub + ", " + other + ", " + sig + ")"));
        }
    }
}
