package com.dsmod.deepseek;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * DeepSeekHashV1 PoW solver.
 *
 * DeepSeekHashV1 = SHA3-256 with Keccak-f[1600] round 0 skipped:
 * rate=136, padding 0x06 + 0x80, 32-byte output, state lane order
 * index = x + 5*y (column major, the reference Keccak layout).
 *
 * Fast path: the host app ships the very same hash as a Rust native
 * library (librscrypto.so) behind
 * com.deepseek.crypto.PowCalculator#a(expireAt, challenge, salt).
 * We are injected into the host process, so reflection gives us the
 * native solver with no extra .so to ship. The pure Java solver below
 * is the full standalone implementation and is used when the host
 * class is missing (other app versions / unit tests).
 */
public final class Pow {
    private static final String TAG = "DSMOD";
    private static final int RATE = 136;

    /** Keccak-f[1600] round constants; round 0 is skipped, so rc[1..23] are used. */
    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL, 0x8000000080008000L,
            0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L,
    };

    /** rho offsets r[x][y] = RHO[x + 5*y] */
    private static final int[] RHO = {
             0, 36,  3, 41, 18,
             1, 44, 10, 45,  2,
            62,  6, 43, 15, 61,
            28, 55, 25, 21, 56,
            27, 20, 39,  8, 14,
    };

    private static final int DEFAULT_DIFFICULTY = 144000;

    private Pow() {}

    public static long rotl64(long v, int n) {
        return (v << n) | (v >>> (64 - n));
    }

    /** Keccak-f[1600] permutation, rounds 1..23 only. Column-major lane state. */
    public static void keccakF23(long[] s) {
        long[] c = new long[5];
        long[] d = new long[5];
        long[] b = new long[25];
        for (int r = 1; r < 24; r++) {
            for (int x = 0; x < 5; x++) {
                c[x] = s[x] ^ s[x + 5] ^ s[x + 10] ^ s[x + 15] ^ s[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                d[x] = c[(x + 4) % 5] ^ rotl64(c[(x + 1) % 5], 1);
            }
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 5; x++) {
                    s[x + 5 * y] ^= d[x];
                }
            }
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 5; x++) {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = rotl64(s[x + 5 * y], RHO[y + 5 * x]);
                }
            }
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 5; x++) {
                    long b0 = b[x + 5 * y];
                    long b1 = b[(x + 1) % 5 + 5 * y];
                    long b2 = b[(x + 2) % 5 + 5 * y];
                    s[x + 5 * y] = b0 ^ ((~b1) & b2);
                }
            }
            s[0] ^= RC[r];
        }
    }

    private static long le64(byte[] d, int off) {
        return (d[off] & 0xFFL)
                | ((d[off + 1] & 0xFFL) << 8)
                | ((d[off + 2] & 0xFFL) << 16)
                | ((d[off + 3] & 0xFFL) << 24)
                | ((d[off + 4] & 0xFFL) << 32)
                | ((d[off + 5] & 0xFFL) << 40)
                | ((d[off + 6] & 0xFFL) << 48)
                | ((d[off + 7] & 0xFFL) << 56);
    }

    private static void putLe64(byte[] out, int off, long v) {
        for (int i = 0; i < 8; i++) out[off + i] = (byte) (v >>> (8 * i));
    }

    private static void absorb(long[] s, byte[] block33, int off) {
        for (int i = 0; i < RATE / 8; i++) s[i] ^= le64(block33, off + i * 8);
    }

    public static byte[] hashV1(byte[] data) {
        long[] s = new long[25];
        int off = 0;
        while (off + RATE <= data.length) {
            absorb(s, data, off);
            keccakF23(s);
            off += RATE;
        }
        byte[] finalBlock = new byte[RATE];
        int tail = data.length - off;
        System.arraycopy(data, off, finalBlock, 0, tail);
        finalBlock[tail] = 0x06;
        finalBlock[RATE - 1] |= (byte) 0x80;
        for (int i = 0; i < RATE / 8; i++) s[i] ^= le64(finalBlock, i * 8);
        keccakF23(s);
        byte[] out = new byte[32];
        putLe64(out, 0, s[0]);
        putLe64(out, 8, s[1]);
        putLe64(out, 16, s[2]);
        putLe64(out, 24, s[3]);
        return out;
    }

    public static String hex(byte[] data) {
        char[] chars = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = chars[v >>> 4];
            out[i * 2 + 1] = chars[v & 0x0F];
        }
        return new String(out);
    }

    public static String hashV1Hex(byte[] data) {
        return hex(hashV1(data));
    }

    /**
     * Try the host native solver first (Rust, ~20ms for 144k),
     * then fall back to pure Java.
     */
    public static long solve(String challengeHex, String salt, long expireAtNet, long difficulty) {
        long viaHost = solveNative(challengeHex, salt, expireAtNet);
        if (viaHost >= 0) return viaHost;
        return solveJava(challengeHex, salt, expireAtNet, difficulty);
    }

    private static long solveNative(String challengeHex, String salt, long expireAtNet) {
        try {
            Class<?> c = Host.forName("com.deepseek.crypto.PowCalculator");
            Object inst;
            try {
                Field f = c.getField("a");
                f.setAccessible(true);
                inst = f.get(null);
            } catch (Throwable t) {
                inst = c.getDeclaredConstructor().newInstance();
            }
            if (inst == null) return -1L;
            Method m = c.getMethod("a", long.class, String.class, String.class);
            m.setAccessible(true);
            Object ans = m.invoke(inst, expireAtNet, challengeHex, salt);
            if (ans instanceof Number) {
                long n = ((Number) ans).longValue();
                return n >= 0 ? n : -1L;
            }
        } catch (Throwable t) {
            Logs.d(Logs.UPSTREAM, TAG, "host PowCalculator unavailable: " + t);
        }
        return -1L;
    }

    /**
     * Pure Java search over [0, difficulty).
     *
     * Prefix block is absorbed once; each nonce only fills the trailing
     * decimal digits plus 0x06/0x80 padding into one 136-byte block.
     */
    public static long solveJava(String challengeHex, String salt, long expireAt, long difficulty) {
        long diff = difficulty <= 0 ? DEFAULT_DIFFICULTY : difficulty;
        byte[] prefix = (salt + "_" + expireAt + "_").getBytes(StandardCharsets.UTF_8);

        long[] base = new long[25];
        int off = 0;
        while (off + RATE <= prefix.length) {
            absorb(base, prefix, off);
            keccakF23(base);
            off += RATE;
        }
        int tailLen = prefix.length - off;
        if (tailLen >= RATE - 1) tailLen = RATE - 2;
        byte[] tail = new byte[tailLen];
        System.arraycopy(prefix, off, tail, 0, tailLen);

        byte[] buf = new byte[RATE];
        for (long n = 0; n < diff; n++) {
            String digits = Long.toString(n);
            int dLen = digits.length();
            if (tailLen + dLen + 1 > RATE) continue;
            System.arraycopy(tail, 0, buf, 0, tailLen);
            for (int i = 0; i < dLen; i++) buf[tailLen + i] = (byte) digits.charAt(i);
            buf[tailLen + dLen] = 0x06;
            buf[RATE - 1] |= (byte) 0x80;

            long[] st = base.clone();
            for (int i = 0; i < RATE / 8; i++) st[i] ^= le64(buf, i * 8);
            keccakF23(st);

            byte[] out = new byte[32];
            putLe64(out, 0, st[0]);
            putLe64(out, 8, st[1]);
            putLe64(out, 16, st[2]);
            putLe64(out, 24, st[3]);
            if (challengeHex.equalsIgnoreCase(hex(out))) return n;
        }
        return -1L;
    }

    /** base64(JSON{algorithm,challenge,salt,answer,signature,target_path}) */
    public static String buildHeader(String algorithm, String challenge, String salt, long answer,
                                      String signature, String targetPath) {
        try {
            JSONObject o = new JSONObject();
            o.put("algorithm", algorithm);
            o.put("challenge", challenge);
            o.put("salt", salt);
            o.put("answer", answer);
            o.put("signature", signature);
            o.put("target_path", targetPath);
            return Base64.encodeToString(o.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }
}
