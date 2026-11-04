package com.clean.stickman;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/** RSA-2048 adb key: persisted as n/d/e, raw PKCS#1 v1.5 signing, adb public-key blob. */
public class AdbKey {
    static final byte[] SHA1_PREFIX = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    final BigInteger n, d, e;

    AdbKey(BigInteger n, BigInteger d, BigInteger e) { this.n = n; this.d = d; this.e = e; }

    public static AdbKey loadOrCreate(File dir) throws Exception {
        File f = new File(dir, "adbkey.txt");
        if (f.exists()) {
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int r = 0; while (r < b.length) { int k = in.read(b, r, b.length - r); if (k < 0) break; r += k; }
            in.close();
            String[] p = new String(b, "UTF-8").trim().split("\n");
            return new AdbKey(new BigInteger(p[0]), new BigInteger(p[1]), new BigInteger(p[2]));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        RSAPrivateKey pr = (RSAPrivateKey) kp.getPrivate();
        RSAPublicKey pu = (RSAPublicKey) kp.getPublic();
        AdbKey k = new AdbKey(pu.getModulus(), pr.getPrivateExponent(), pu.getPublicExponent());
        FileOutputStream out = new FileOutputStream(f);
        out.write((k.n + "\n" + k.d + "\n" + k.e + "\n").getBytes("UTF-8"));
        out.close();
        return k;
    }

    /** PKCS#1 v1.5 type-1 signature over SHA1 DigestInfo || token (token is used as the digest). */
    public byte[] sign(byte[] token) {
        int k = 256;
        byte[] em = new byte[k];
        int tLen = SHA1_PREFIX.length + token.length;
        em[0] = 0; em[1] = 1;
        for (int i = 2; i < k - tLen - 1; i++) em[i] = (byte) 0xff;
        em[k - tLen - 1] = 0;
        System.arraycopy(SHA1_PREFIX, 0, em, k - tLen, SHA1_PREFIX.length);
        System.arraycopy(token, 0, em, k - token.length, token.length);
        BigInteger s = new BigInteger(1, em).modPow(d, n);
        return toFixed(s, k);
    }

    static byte[] toFixed(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        byte[] o = new byte[len];
        if (b.length >= len) System.arraycopy(b, b.length - len, o, 0, len);
        else System.arraycopy(b, 0, o, len - b.length, b.length);
        return o;
    }

    static void putLE(byte[] b, int o, long v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    /** adb's RSAPublicKey struct (len, n0inv, n[64], rr[64], exponent) base64'd + " name\0". */
    public byte[] publicKeyPayload(String name) throws Exception {
        final int words = 64;
        byte[] s = new byte[4 + 4 + words * 4 + words * 4 + 4];
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        putLE(s, 0, words);
        putLE(s, 4, n0inv.longValue());
        BigInteger mask = r32.subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) {
            putLE(s, 8 + i * 4, n.shiftRight(32 * i).and(mask).longValue());
            putLE(s, 8 + words * 4 + i * 4, rr.shiftRight(32 * i).and(mask).longValue());
        }
        putLE(s, 8 + words * 8, e.longValue());
        String str = Base64.encodeToString(s, Base64.NO_WRAP) + " " + name;
        byte[] a = str.getBytes("UTF-8");
        byte[] out = new byte[a.length + 1];
        System.arraycopy(a, 0, out, 0, a.length);
        return out;
    }
}
