package bl0.aeon;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.XECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;

/**
 * A small SSH-2 client for the tests, written from the RFCs with the JDK's crypto (and a BigInteger Poly1305), so the
 * server in stdlib/net/ssh.bl0 is checked against an independent implementation. It offers what the server speaks:
 * curve25519-sha256 (with strict kex), ssh-ed25519, chacha20-poly1305@openssh.com.
 */
final class SshTestClient implements AutoCloseable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private long sendSeq, recvSeq;
    private byte[] sendMain, sendHeader, recvMain, recvHeader;
    private byte[] sessionId;
    byte[] hostKey;                       // the server's raw Ed25519 public key
    private int channel = -1;             // the server's number of our channel
    private int windowLeft;
    final StringBuilder received = new StringBuilder();
    Integer exitStatus;
    boolean channelClosed;

    SshTestClient(int port) throws Exception {
        socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(60_000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    // ---- encodings
    static byte[] u32(long n) { return new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n}; }
    static byte[] cat(byte[]... parts) {
        var b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.writeBytes(p);
        return b.toByteArray();
    }
    static byte[] str(byte[] b) { return cat(u32(b.length), b); }
    static byte[] str(String s) { return str(s.getBytes(StandardCharsets.UTF_8)); }
    static byte[] mpint(byte[] be) {
        int i = 0;
        while (i < be.length && be[i] == 0) i++;
        byte[] body = Arrays.copyOfRange(be, i, be.length);
        if (body.length > 0 && (body[0] & 0x80) != 0) body = cat(new byte[]{0}, body);
        return str(body);
    }

    private static final class Reader {
        final ByteBuffer b;
        Reader(byte[] data, int pos) { b = ByteBuffer.wrap(data); b.position(pos); }
        int u8() { return b.get() & 255; }
        long u32() { return b.getInt() & 0xFFFFFFFFL; }
        byte[] bytes() { byte[] r = new byte[(int) u32()]; b.get(r); return r; }
        String text() { return new String(bytes(), StandardCharsets.UTF_8); }
    }

    // ---- packets
    private void sendPacket(byte[] payload) throws IOException {
        int padding = 8 - ((1 + payload.length) % 8);
        if (padding < 4) padding += 8;
        byte[] body = cat(new byte[]{(byte) padding}, payload, new byte[padding]);
        if (sendMain == null) {
            out.write(cat(u32(body.length), body));
        } else {
            byte[] nonce = nonce(sendSeq);
            byte[] encLen = chacha(sendHeader, 0, nonce, u32(body.length));
            byte[] ct = chacha(sendMain, 1, nonce, body);
            byte[] tag = poly(Arrays.copyOf(chacha(sendMain, 0, nonce, new byte[64]), 32), cat(encLen, ct));
            out.write(cat(encLen, ct, tag));
        }
        out.flush();
        sendSeq++;
    }

    private byte[] readPacket() throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] body;
        if (recvMain == null) {
            byte[] head = new byte[4];
            d.readFully(head);
            body = new byte[ByteBuffer.wrap(head).getInt()];
            d.readFully(body);
        } else {
            byte[] nonce = nonce(recvSeq);
            byte[] encLen = new byte[4];
            d.readFully(encLen);
            int n = ByteBuffer.wrap(chacha(recvHeader, 0, nonce, encLen)).getInt();
            byte[] ct = new byte[n];
            d.readFully(ct);
            byte[] tag = new byte[16];
            d.readFully(tag);
            byte[] expect = poly(Arrays.copyOf(chacha(recvMain, 0, nonce, new byte[64]), 32), cat(encLen, ct));
            if (!MessageDigest.isEqual(expect, tag)) throw new IOException("bad MAC from the server");
            body = chacha(recvMain, 1, nonce, ct);
        }
        recvSeq++;
        int padding = body[0] & 255;
        return Arrays.copyOfRange(body, 1, body.length - padding);
    }

    private static byte[] nonce(long seq) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[11 - i] = (byte) (seq >>> (8 * i));
        return n;
    }

    private static byte[] chacha(byte[] key, int counter, byte[] nonce12, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("ChaCha20");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new ChaCha20ParameterSpec(nonce12, counter));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] poly(byte[] key, byte[] msg) {
        byte[] rb = Arrays.copyOf(key, 16);
        rb[3] &= 15; rb[7] &= 15; rb[11] &= 15; rb[15] &= 15;
        rb[4] &= 252; rb[8] &= 252; rb[12] &= 252;
        BigInteger r = le(rb), s = le(Arrays.copyOfRange(key, 16, 32));
        BigInteger p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5)), h = BigInteger.ZERO;
        for (int i = 0; i < msg.length; i += 16) {
            int n = Math.min(16, msg.length - i);
            byte[] block = new byte[n + 1];
            System.arraycopy(msg, i, block, 0, n);
            block[n] = 1;
            h = h.add(le(block)).multiply(r).mod(p);
        }
        byte[] tb = h.add(s).mod(BigInteger.ONE.shiftLeft(128)).toByteArray();
        byte[] outTag = new byte[16];
        for (int i = 0; i < 16 && i < tb.length; i++) outTag[i] = tb[tb.length - 1 - i];
        return outTag;
    }

    private static BigInteger le(byte[] b) {
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) be[i] = b[b.length - 1 - i];
        return new BigInteger(1, be);
    }

    private static byte[] sha256(byte[]... parts) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (byte[] p : parts) md.update(p);
        return md.digest();
    }

    // ---- the exchange
    /** the key exchange; throws if the server's host-key signature does not verify */
    void handshake() throws Exception {
        byte[] ours = "SSH-2.0-testclient".getBytes(StandardCharsets.US_ASCII);
        out.write(cat(ours, new byte[]{13, 10}));
        out.flush();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != '\n') {
            if (c < 0) throw new IOException("the server closed before its version");
            line.write(c);
        }
        byte[] theirs = line.toByteArray();
        if (theirs.length > 0 && theirs[theirs.length - 1] == 13) theirs = Arrays.copyOf(theirs, theirs.length - 1);

        byte[] myInit = cat(new byte[]{20}, new byte[16],
                str("curve25519-sha256,kex-strict-c-v00@openssh.com"), str("ssh-ed25519"),
                str("chacha20-poly1305@openssh.com"), str("chacha20-poly1305@openssh.com"),
                str("hmac-sha2-256"), str("hmac-sha2-256"), str("none"), str("none"), str(""), str(""), new byte[]{0}, u32(0));
        sendPacket(myInit);
        byte[] serverInit = readPacket();
        if (serverInit[0] != 20) throw new IOException("no KEXINIT: " + serverInit[0]);
        boolean strict = new String(serverInit, StandardCharsets.ISO_8859_1).contains("kex-strict-s-v00@openssh.com");

        KeyPairGenerator gen = KeyPairGenerator.getInstance("X25519");
        KeyPair mine = gen.generateKeyPair();
        byte[] q = littleEndian(((XECPublicKey) mine.getPublic()).getU());
        sendPacket(cat(new byte[]{30}, str(q)));
        byte[] reply = readPacket();
        if (reply[0] != 31) throw new IOException("no ECDH reply: " + reply[0] + " " + new String(reply, StandardCharsets.ISO_8859_1));
        Reader r = new Reader(reply, 1);
        byte[] blob = r.bytes();
        byte[] serverPoint = r.bytes();
        byte[] sigBlob = r.bytes();

        KeyFactory kf = KeyFactory.getInstance("X25519");
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(mine.getPrivate());
        ka.doPhase(kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, le(maskTop(serverPoint)))), true);
        byte[] shared = ka.generateSecret();
        byte[] k = mpint(shared);
        byte[] h = sha256(str(ours), str(theirs), str(myInit), str(serverInit), str(blob), str(q), str(serverPoint), k);
        if (sessionId == null) sessionId = h;

        Reader br = new Reader(blob, 0);
        if (!br.text().equals("ssh-ed25519")) throw new IOException("host key type");
        hostKey = br.bytes();
        Reader sr = new Reader(sigBlob, 0);
        sr.text();
        byte[] sig = sr.bytes();
        Signature verify = Signature.getInstance("Ed25519");
        verify.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(edKey(hostKey)));
        verify.update(h);
        if (!verify.verify(sig)) throw new IOException("the host key signature does not verify");

        if (readPacketType() != 21) throw new IOException("no NEWKEYS");
        sendPacket(new byte[]{21});
        byte[] c2s = derive(k, h, 'C'), s2c = derive(k, h, 'D');
        sendMain = Arrays.copyOf(c2s, 32); sendHeader = Arrays.copyOfRange(c2s, 32, 64);
        recvMain = Arrays.copyOf(s2c, 32); recvHeader = Arrays.copyOfRange(s2c, 32, 64);
        if (strict) { sendSeq = 0; recvSeq = 0; }
    }

    private int readPacketType() throws IOException { return readPacket()[0] & 255; }

    private byte[] derive(byte[] k, byte[] h, char letter) throws Exception {
        byte[] first = sha256(k, h, new byte[]{(byte) letter}, sessionId);
        byte[] second = sha256(k, h, first);
        return cat(first, second);
    }

    private static byte[] maskTop(byte[] point) {
        byte[] c = point.clone();
        c[31] &= 0x7f;
        return c;
    }

    private static byte[] littleEndian(BigInteger u) {
        byte[] be = u.toByteArray(), out = new byte[32];
        for (int i = 0; i < be.length && i < 32; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    private static EdECPublicKeySpec edKey(byte[] raw) {
        byte[] y = raw.clone();
        boolean xOdd = (y[31] & 0x80) != 0;
        y[31] &= 0x7f;
        return new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, le(y)));
    }

    // ---- user authentication and the channel
    private void requestService() throws IOException {
        sendPacket(cat(new byte[]{5}, str("ssh-userauth")));
        if (readPacketType() != 6) throw new IOException("no service accept");
    }

    /** true when the password is accepted */
    boolean loginWithPassword(String user, String password) throws IOException {
        requestService();
        sendPacket(cat(new byte[]{50}, str(user), str("ssh-connection"), str("password"), new byte[]{0}, str(password)));
        byte[] p = readPacket();
        return p[0] == 52;
    }

    /** what the server answers to the method "none": the names of the methods it offers */
    String methods() throws IOException {
        requestService();
        sendPacket(cat(new byte[]{50}, str("nobody"), str("ssh-connection"), str("none")));
        byte[] p = readPacket();
        if (p[0] != 51) throw new IOException("expected a failure listing the methods, got " + p[0]);
        return new Reader(p, 1).text();
    }

    /** true when the key signs in. 'seed' is the Ed25519 private key's 32 bytes. */
    boolean loginWithKey(String user, byte[] seed, boolean sign) throws Exception {
        requestService();
        var kf = KeyFactory.getInstance("Ed25519");
        var priv = kf.generatePrivate(new java.security.spec.EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        byte[] pub = publicKeyOf(seed);
        byte[] blob = cat(str("ssh-ed25519"), str(pub));
        sendPacket(cat(new byte[]{50}, str(user), str("ssh-connection"), str("publickey"), new byte[]{0}, str("ssh-ed25519"), str(blob)));
        byte[] p = readPacket();
        if (p[0] != 60) return false;
        byte[] signedData = cat(str(sessionId), new byte[]{50}, str(user), str("ssh-connection"), str("publickey"), new byte[]{1}, str("ssh-ed25519"), str(blob));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(priv);
        s.update(sign ? signedData : cat(signedData, new byte[]{1}));
        byte[] sig = s.sign();
        sendPacket(cat(new byte[]{50}, str(user), str("ssh-connection"), str("publickey"), new byte[]{1}, str("ssh-ed25519"), str(blob),
                str(cat(str("ssh-ed25519"), str(sig)))));
        return readPacket()[0] == 52;
    }

    static byte[] keyBlob(byte[] pub) { return cat(str("ssh-ed25519"), str(pub)); }

    static byte[] publicKeyOf(byte[] seed) throws Exception {
        var kf = KeyFactory.getInstance("Ed25519");
        var priv = kf.generatePrivate(new java.security.spec.EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        // sign nothing and recover the key: the JDK has no direct accessor, so derive it from a key pair built on the seed
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        g.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) { System.arraycopy(seed, 0, bytes, 0, bytes.length); }
        });
        var pub = (java.security.interfaces.EdECPublicKey) g.generateKeyPair().getPublic();
        byte[] y = littleEndian(pub.getPoint().getY());
        if (pub.getPoint().isXOdd()) y[31] |= (byte) 0x80;
        return y;
    }

    private void openChannel() throws IOException {
        sendPacket(cat(new byte[]{90}, str("session"), u32(0), u32(2097152), u32(32768)));
        byte[] p = readPacket();
        if (p[0] != 91) throw new IOException("channel open refused: " + p[0]);
        Reader r = new Reader(p, 1);
        r.u32();
        channel = (int) r.u32();
        windowLeft = (int) r.u32();
    }

    /** runs a command; returns its output (after waiting for the channel to close) */
    String exec(String command) throws IOException {
        openChannel();
        sendPacket(cat(new byte[]{98}, u32(channel), str("exec"), new byte[]{1}, str(command)));
        pumpUntilClosed();
        return received.toString();
    }

    void openShell(boolean pty) throws IOException {
        openChannel();
        if (pty) {
            sendPacket(cat(new byte[]{98}, u32(channel), str("pty-req"), new byte[]{1}, str("xterm"), u32(80), u32(24), u32(0), u32(0), str("")));
            expectSuccess();
        }
        sendPacket(cat(new byte[]{98}, u32(channel), str("shell"), new byte[]{1}));
        expectSuccess();
    }

    private void expectSuccess() throws IOException {
        while (true) {
            byte[] p = readPacket();
            if (p[0] == 99) return;
            if (p[0] == 100) throw new IOException("channel request refused");
            handle(p);
        }
    }

    void send(String text) throws IOException {
        sendPacket(cat(new byte[]{94}, u32(channel), str(text.getBytes(StandardCharsets.ISO_8859_1))));
    }

    /** reads until 'text' has arrived (or the channel closes); the output so far is returned */
    String readUntil(String text) throws IOException {
        while (!received.toString().contains(text) && !channelClosed) handle(readPacket());
        return received.toString();
    }

    private void pumpUntilClosed() throws IOException {
        while (!channelClosed) handle(readPacket());
    }

    private void handle(byte[] p) throws IOException {
        Reader r = new Reader(p, 1);
        switch (p[0] & 255) {
            case 94 -> { r.u32(); received.append(new String(r.bytes(), StandardCharsets.ISO_8859_1)); }
            case 95 -> { r.u32(); r.u32(); received.append(new String(r.bytes(), StandardCharsets.ISO_8859_1)); }
            case 98 -> {
                r.u32();
                if (r.text().equals("exit-status")) { r.u8(); exitStatus = (int) r.u32(); }
            }
            case 97 -> {
                if (!channelClosed && channel >= 0) sendPacket(cat(new byte[]{97}, u32(channel)));
                channelClosed = true;
            }
            case 1 -> channelClosed = true;
            default -> { }
        }
    }

    void closeChannel() throws IOException {
        sendPacket(cat(new byte[]{97}, u32(channel)));
        pumpUntilClosed();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
