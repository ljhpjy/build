package com.dsmod.deepseek;

import android.util.Base64;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * DeepSeekHashV1 PoW solver.
 *
 * Answer rule (same as fuckds): digest input is
 * salt + "_" + expireAt + "_" + nonce, hashed with SHA3-256
 * (Keccak-f[1600], rate 136, padding 0x06/0x80) and the first three
 * bytes of the digest must be zero. The server challenge is NOT part of
 * the digest input; it only goes into the signed header.
 *
 * The host Rust solver behind PowCalculator#a(expireAt, challenge, salt)
 * is opt-in (Config.powNative) because some builds hang inside JNI; the
 * pure Java solver is the default and has no external dependency.
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
    /** 一次 JNI 超时后禁用原生，避免每个请求都泄漏一个死循环线程。 */
    private static volatile boolean nativeDisabled = false;

    private Pow() {}

    public static long rotl64(long v, int n) {
        return (v << n) | (v >>> (64 - n));
    }

    /** Keccak-f[1600] permutation, full 24 rounds (SHA3-256). Column-major lane state. */
    public static void keccakF23(long[] s) {
        long[] c = new long[5];
        long[] d = new long[5];
        long[] b = new long[25];
        keccakF23Buf(s, c, d, b);
    }

    /** Same permutation with caller-provided scratch buffers (no per-call allocation). */
    private static void keccakF23Buf(long[] s, long[] c, long[] d, long[] b) {
        for (int r = 0; r < 24; r++) {
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

    private static void absorb(long[] s, byte[] block, int off) {
        for (int i = 0; i < RATE / 8; i++) s[i] ^= le64(block, off + i * 8);
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
     * Solve the challenge: optional bounded native attempt, then pure Java.
     * Returns -1 only when no nonce below difficulty matches.
     */
    public static long solve(String challengeHex, String salt, long expireAtNet, long difficulty) {
        long t0 = System.currentTimeMillis();
        if (Config.powNative && !nativeDisabled) {
            Logs.d(Logs.UPSTREAM, TAG, "pow native start challenge_len=" + challengeHex.length()
                    + " challenge_head=" + prefix8(challengeHex) + " salt_len=" + salt.length()
                    + " expire_at=" + expireAtNet + " difficulty=" + difficulty);
            long viaHost = solveNative(challengeHex, salt, expireAtNet, difficulty);
            if (viaHost >= 0) {
                verifyNativeAnswer(salt, expireAtNet, viaHost);
                Logs.i(Logs.UPSTREAM, TAG, "pow native ok answer=" + viaHost
                        + " elapsed_ms=" + (System.currentTimeMillis() - t0));
                return viaHost;
            }
            Logs.w(Logs.UPSTREAM, TAG, "pow native failed, fallback=java");
        }
        long answer = solveJava(challengeHex, salt, expireAtNet, difficulty);
        Logs.i(Logs.UPSTREAM, TAG, "pow java done answer=" + answer
                + " elapsed_ms=" + (System.currentTimeMillis() - t0));
        return answer;
    }

    private static String prefix8(String s) {
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    /** 用纯 Java 算法校验宿主答案，顺手验证两边算法是否一致。 */
    private static void verifyNativeAnswer(String salt, long expireAt, long answer) {
        try {
            byte[] chk = hashV1((salt + "_" + expireAt + "_" + answer)
                    .getBytes(StandardCharsets.UTF_8));
            boolean ok = chk[0] == 0 && chk[1] == 0 && chk[2] == 0;
            Logs.i(Logs.UPSTREAM, TAG, "pow verify " + (ok ? "ok" : "mismatch hash=" + hex(chk)));
        } catch (Throwable ignore) { }
    }

    /** Native call is bounded: a hung JNI must never block the request thread. */
    private static long solveNative(String challengeHex, String salt, long expireAtNet, long difficulty) {
        final long[] result = {-1L};
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
            final Object target = inst;
            final Method m = c.getMethod("a", long.class, String.class, String.class);
            m.setAccessible(true);
            // Host semantics (smali-verified): a(difficulty, prefix, challenge)
            // -> nativeCalculateDeepSeekHashV1Pow(prefix, challenge, difficulty)
            //    prefix = salt + "_" + expire_at + "_"
            final String prefix = salt + "_" + expireAtNet + "_";
            final String challenge = challengeHex;
            final long diff = difficulty;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object ans = m.invoke(target, diff, prefix, challenge);
                        if (ans instanceof Number) {
                            long n = ((Number) ans).longValue();
                            if (n >= 0) result[0] = n;
                        }
                    } catch (Throwable t) {
                        Logs.w(Logs.UPSTREAM, TAG, "pow native error: " + t);
                    }
                }
            }, "dsmod-pow-native");
            worker.setDaemon(true);
            worker.start();
            worker.join(1500L);
            if (worker.isAlive()) {
                nativeDisabled = true;
                Logs.w(Logs.UPSTREAM, TAG, "pow native timeout=1500ms, native disabled for this run, fallback=java");
                return -1L;
            }
        } catch (Throwable t) {
            Logs.d(Logs.UPSTREAM, TAG, "pow native unavailable: " + t);
        }
        return result[0];
    }

    /**
     * Pure Java search over [0, difficulty).
     *
     * Prefix (salt_expireAt_) is absorbed once; each nonce only refills the
     * trailing decimal digits plus 0x06/0x80 padding into a reusable 136-byte
     * block, and scratch arrays are reused so the hot loop does not allocate.
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

        long[] st = new long[25];
        long[] c = new long[5];
        long[] d = new long[5];
        long[] b = new long[25];
        byte[] buf = new byte[RATE];
        byte[] digits = new byte[20];

        for (long n = 0; n < diff; n++) {
            int dLen = 1;
            long v = n;
            while (v >= 10) { dLen++; v /= 10; }
            v = n;
            for (int i = dLen - 1; i >= 0; i--) {
                digits[i] = (byte) ('0' + (v % 10));
                v /= 10;
            }
            int pad = tailLen + dLen;
            if (pad >= RATE) continue;
            System.arraycopy(tail, 0, buf, 0, tailLen);
            System.arraycopy(digits, 0, buf, tailLen, dLen);
            buf[pad] = 0x06;
            if (pad == RATE - 1) {
                buf[pad] = (byte) 0x86;
            } else {
                buf[RATE - 1] = (byte) 0x80;
            }

            System.arraycopy(base, 0, st, 0, 25);
            for (int i = 0; i < RATE / 8; i++) st[i] ^= le64(buf, i * 8);
            keccakF23Buf(st, c, d, b);

            // Answer rule: first 3 bytes of the digest are zero.
            if ((st[0] & 0xFFFFFFL) == 0L) return n;
        }
        Logs.w(Logs.UPSTREAM, TAG, "pow java exhausted difficulty=" + diff);
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
