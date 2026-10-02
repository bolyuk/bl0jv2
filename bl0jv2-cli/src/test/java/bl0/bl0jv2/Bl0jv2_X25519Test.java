package bl0.bl0jv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyAgreement;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

// stdlib/crypto/x25519.bl0 against RFC 7748 and the JDK's X25519
class Bl0jv2_X25519Test {

    private static byte[] scalarMult(Path dir, byte[] k, byte[] u) throws IOException {
        Path entry = dir.resolve("entry.bl0");
        Files.writeString(entry, "import 'stdlib/crypto/x25519.bl0'; print X25519.scalarMult("
                + Bl0jv2_Sha512Test.bytesLiteral(k) + ", " + Bl0jv2_Sha512Test.bytesLiteral(u) + ");");
        return Bl0jv2_TestRunner.runFile(entry) == null ? null : Bl0jv2_Sha512Test.parse(Bl0jv2_TestRunner.runFile(entry));
    }

    private static byte[] hex(String s) { return HexFormat.of().parseHex(s); }

    @Test
    void rfc7748Vectors(@TempDir Path dir) throws Exception {
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
                HexFormat.of().formatHex(scalarMult(dir,
                        hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                        hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))));
        assertEquals("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
                HexFormat.of().formatHex(scalarMult(dir,
                        hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                        hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"))));
    }

    @Test
    void diffieHellmanAgreesWithTheJdk(@TempDir Path dir) throws Exception {
        Random random = new Random(11);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("X25519");
        for (int round = 0; round < 3; round++) {
            byte[] secret = new byte[32];
            random.nextBytes(secret);
            KeyPair peer = gen.generateKeyPair();
            // the peer's public u as 32 little-endian bytes
            BigInteger u = ((XECPublicKey) peer.getPublic()).getU();
            byte[] ub = new byte[32];
            byte[] be = u.toByteArray();
            for (int i = 0; i < be.length && i < 32; i++) ub[i] = be[be.length - 1 - i];

            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(KeyFactory.getInstance("X25519").generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, secret)));
            ka.doPhase(peer.getPublic(), true);
            assertEquals(HexFormat.of().formatHex(ka.generateSecret()),
                    HexFormat.of().formatHex(scalarMult(dir, secret, ub)), "round " + round);
        }
    }
}
