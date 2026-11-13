package com.dsmod.deepseek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Account pool with observable rotation.
 *
 * Sources:
 *  - the token currently signed in to the official app (read from MMKV key_user_info)
 *  - extra tokens pasted in /admin or written to files/dsmod_accounts.json
 *
 * The pool is captured automatically: a daemon refresher re-reads the host
 * MMKV every 5s, so switching accounts inside DeepSeek adds the new token
 * without restarting the app. Existing entries keep cooldown/counters/last
 * error, exposed via /admin/api/accounts and /healthz.
 */
public final class Accounts {
    private static final String TAG = "DSMOD";
    private static final Object LOCK = new Object();

    /** Login-state failures are parked for 24h: only a new token can recover. */
    private static final long AUTH_INVALID_COOLDOWN_MS = 24 * 60 * 60_000L;

    private static Context ctx;
    private static final List<Entry> POOL = new ArrayList<>();
    private static int rr = 0;
    private static int lastPicked = -1;
    private static String lastPickLabel = "";
    private static String cachedCurrent;
    private static volatile long lastTokenReadAt = 0L;
    private static volatile boolean refresherStarted = false;
    private static volatile String tokenError = "";

    private Accounts() {}

    /** One account and its runtime counters. */
    static final class Entry {
        String token;
        long cooldownUntil = 0L;
        boolean authFailed = false;
        long requests = 0L;
        long lastUsedAt = 0L;
        long lastErrorAt = 0L;
        String lastError = "";
    }

    /** Result of a live token check. */
    static final class VerifyResult {
        final boolean ok;
        final String detail;

        VerifyResult(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    static void init(Context context) {
        if (context == null) return;
        ctx = context.getApplicationContext();
        if (ctx == null) ctx = context;
        loadPoolFile();
        captureCurrent();
        startRefresher();
    }

    /* ------------------------------------------------------------------ */
    /* Token sources                                                       */
    /* ------------------------------------------------------------------ */

    static String currentToken() {
        String cached = cachedCurrent;
        if (cached != null && System.currentTimeMillis() - lastTokenReadAt < 5000L) {
            return cached;
        }
        return readTokenFresh();
    }

    private static String readTokenFresh() {
        try {
            Object kv = defaultMMKV();
            if (kv == null) return cachedCurrent;
            String info = readString(kv, "key_user_info");
            if (info == null || info.isEmpty()) return cachedCurrent;
            JSONObject root = new JSONObject(info);
            String token = findToken(root);
            if (token != null && !token.isEmpty()) {
                cachedCurrent = token;
                lastTokenReadAt = System.currentTimeMillis();
                tokenError = "";
                return token;
            }
            tokenError = "token field not found in key_user_info";
        } catch (Throwable t) {
            Logs.e(Logs.ACCOUNT, TAG, "read MMKV token failed", t);
        }
        return cachedCurrent;
    }

    private static void captureCurrent() {
        String token = readTokenFresh();
        if (token == null || token.isEmpty()) return;
        synchronized (LOCK) {
            for (Entry e : POOL) {
                if (e.token.equals(token)) return;
            }
            Entry entry = new Entry();
            entry.token = token;
            POOL.add(entry);
            persist();
            Logs.i(Logs.ACCOUNT, TAG, "account captured: " + mask(token));
        }
    }

    private static void startRefresher() {
        if (refresherStarted) return;
        refresherStarted = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        captureCurrent();
                    } catch (Throwable ignore) { }
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "dsmod-account-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    /* ------------------------------------------------------------------ */
    /* Pool operations                                                     */
    /* ------------------------------------------------------------------ */

    /** Round-robin pick a usable token; null if none (empty or all cooling). */
    static String pick() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            int n = POOL.size();
            for (int i = 0; i < n; i++) {
                int idx = (rr + i) % n;
                Entry e = POOL.get(idx);
                if (e.authFailed) continue;
                if (e.cooldownUntil > now) continue;
                rr = idx + 1;
                lastPicked = idx;
                lastPickLabel = "account pick #" + (idx + 1) + "/" + n + " token=" + mask(e.token);
                e.requests += 1;
                e.lastUsedAt = now;
                return e.token;
            }
            return null;
        }
    }

    static int size() {
        synchronized (LOCK) {
            captureCurrent();
            return POOL.size();
        }
    }

    static int activeCount() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            int count = 0;
            for (Entry e : POOL) {
                if (!e.authFailed && e.cooldownUntil <= now) count++;
            }
            return count;
        }
    }

    static long nextAvailableAt() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long min = Long.MAX_VALUE;
            for (Entry e : POOL) {
                if (e.authFailed) continue;
                if (e.cooldownUntil <= now) return now;
                if (e.cooldownUntil < min) min = e.cooldownUntil;
            }
            return min == Long.MAX_VALUE ? now : min;
        }
    }

    static void markRateLimited(String token, long ms) {
        synchronized (LOCK) {
            Entry e = find(token);
            if (e == null) return;
            e.cooldownUntil = Math.max(e.cooldownUntil, System.currentTimeMillis() + ms);
            e.lastError = "rate_limited";
            e.lastErrorAt = System.currentTimeMillis();
        }
    }

    static void markAuthFailed(String token, String message) {
        synchronized (LOCK) {
            Entry e = find(token);
            if (e == null) return;
            e.authFailed = true;
            e.cooldownUntil = System.currentTimeMillis() + AUTH_INVALID_COOLDOWN_MS;
            e.lastError = message == null ? "auth_expired" : message;
            e.lastErrorAt = System.currentTimeMillis();
            Logs.w(Logs.ACCOUNT, TAG, "account disabled (auth): " + mask(token));
        }
    }

    static void markError(String token, String message) {
        synchronized (LOCK) {
            Entry e = find(token);
            if (e == null) return;
            e.lastError = message == null ? "upstream_error" : message;
            e.lastErrorAt = System.currentTimeMillis();
        }
    }

    /** Clear every cooldown/authFailed flag (admin "re-enable all"). */
    static void resetCooldowns() {
        synchronized (LOCK) {
            for (Entry e : POOL) {
                e.cooldownUntil = 0L;
                e.authFailed = false;
                e.lastError = "";
            }
        }
    }

    /** Replace the pool with the given tokens (keeps counters for equal tokens). */
    static void setPool(String[] newTokens) {
        if (newTokens == null) return;
        synchronized (LOCK) {
            List<Entry> next = new ArrayList<>();
            for (String t : newTokens) {
                String v = t == null ? "" : t.trim();
                if (v.isEmpty()) continue;
                boolean dup = false;
                for (Entry n : next) {
                    if (n.token.equals(v)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;
                Entry existing = find(v);
                if (existing != null) {
                    next.add(existing);
                } else {
                    Entry entry = new Entry();
                    entry.token = v;
                    next.add(entry);
                }
            }
            POOL.clear();
            POOL.addAll(next);
            rr = 0;
            persist();
        }
    }

    /** Add one token; false when empty or already present. */
    static boolean addToken(String token) {
        if (token == null) return false;
        String t = token.trim();
        if (t.isEmpty()) return false;
        synchronized (LOCK) {
            for (Entry e : POOL) {
                if (e.token.equals(t)) return false;
            }
            Entry entry = new Entry();
            entry.token = t;
            POOL.add(entry);
            persist();
            return true;
        }
    }

    /** Remove one token by 0-based index; the currently signed-in token is protected. */
    static boolean removeToken(int index) {
        synchronized (LOCK) {
            if (index < 0 || index >= POOL.size()) return false;
            String t = POOL.get(index).token;
            if (cachedCurrent != null && cachedCurrent.equals(t)) return false;
            POOL.remove(index);
            rr = 0;
            persist();
            return true;
        }
    }

    /** Live check against /api/v0/users/current. */
    static VerifyResult verify(String token) {
        if (token == null || token.isEmpty()) return new VerifyResult(false, "empty token");
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://chat.deepseek.com/api/v0/users/current");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("accept", "*/*");
            conn.setRequestProperty("accept-encoding", "identity");
            conn.setRequestProperty("authorization", "Bearer " + token);
            conn.setRequestProperty("x-client-bundle-id", "com.deepseek.chat");
            conn.setRequestProperty("x-client-platform", "web");
            conn.setRequestProperty("x-client-version", Config.CLIENT_VERSION);
            conn.setRequestProperty("user-agent", Config.UA_CHROME_WIN);
            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code == 401 || code == 403) return new VerifyResult(false, "HTTP " + code);
            JSONObject root;
            try {
                root = new JSONObject(text);
            } catch (Throwable t) {
                root = null;
            }
            int biz = root == null ? 0 : root.optInt("code", 0);
            String msg = root == null ? "" : root.optString("msg", "");
            if (biz == 40003 || "INVALID_TOKEN".equalsIgnoreCase(msg)) {
                return new VerifyResult(false, "invalid token");
            }
            if (code == 200 && (biz == 0 || biz == 200)) {
                return new VerifyResult(true, "HTTP 200");
            }
            return new VerifyResult(false, "HTTP " + code + " code=" + biz);
        } catch (Throwable t) {
            return new VerifyResult(false, String.valueOf(t));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String[] snapshot() {
        synchronized (LOCK) {
            String[] out = new String[POOL.size()];
            for (int i = 0; i < POOL.size(); i++) out[i] = POOL.get(i).token;
            return out;
        }
    }

    static long[] cooldowns() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long[] out = new long[POOL.size()];
            for (int i = 0; i < POOL.size(); i++) {
                out[i] = Math.max(0L, POOL.get(i).cooldownUntil - now);
            }
            return out;
        }
    }

    /** Per-account status for /admin/api/accounts and /healthz. */
    static JSONArray statsJson() {
        JSONArray arr = new JSONArray();
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < POOL.size(); i++) {
                Entry e = POOL.get(i);
                JSONObject o = new JSONObject();
                try {
                    o.put("index", i + 1);
                    o.put("token", mask(e.token));
                    o.put("authFailed", e.authFailed);
                    o.put("cooldownMs", Math.max(0L, e.cooldownUntil - now));
                    o.put("requests", e.requests);
                    o.put("lastUsedAt", e.lastUsedAt);
                    o.put("current", cachedCurrent != null && cachedCurrent.equals(e.token));
                    o.put("lastError", e.lastError);
                    o.put("lastErrorAt", e.lastErrorAt);
                } catch (Throwable ignore) { }
                arr.put(o);
            }
        }
        return arr;
    }

    static String statusJson() {
        JSONObject root = new JSONObject();
        try {
            synchronized (LOCK) {
                JSONArray accounts = statsJson();
                boolean host = false;
                if (cachedCurrent != null) {
                    for (Entry e : POOL) {
                        if (e.token.equals(cachedCurrent)) {
                            host = true;
                            break;
                        }
                    }
                }
                root.put("host_token_present", host);
                root.put("last_picked", lastPicked >= 0 ? lastPicked + 1 : 0);
                root.put("token_error", tokenError);
                root.put("accounts", accounts);
            }
        } catch (Throwable ignore) { }
        return root.toString();
    }

    /** "pick #2 of 3: abcd****wxyz", written to the access log. */
    static String lastPickLabel() {
        synchronized (LOCK) {
            return lastPickLabel;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Per-token lock: upstream allows one in-flight generation per account. */
    static Object lockFor(String token) {
        String key = token == null ? "" : token;
        Object lock = LOCKS.get(key);
        if (lock == null) {
            Object fresh = new Object();
            Object prev = LOCKS.putIfAbsent(key, fresh);
            lock = prev == null ? fresh : prev;
        }
        return lock;
    }

    static String mask(String token) {
        if (token == null || token.length() <= 8) return "****";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    private static Entry find(String token) {
        for (Entry e : POOL) {
            if (e.token.equals(token)) return e;
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Persistence                                                         */
    /* ------------------------------------------------------------------ */

    private static void loadPoolFile() {
        try {
            File f = new File(ctx.getFilesDir(), Config.accountsFile);
            if (!f.isFile()) return;
            byte[] data = readAll(f);
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("tokens");
            if (arr == null) {
                arr = root.optJSONArray("accounts");
                if (arr != null) {
                    JSONArray tokens = new JSONArray();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item == null) continue;
                        String t = item.optString("token", "").trim();
                        if (!t.isEmpty()) tokens.put(t);
                    }
                    arr = tokens;
                }
            }
            if (arr == null) return;
            synchronized (LOCK) {
                POOL.clear();
                for (int i = 0; i < arr.length(); i++) {
                    String t = arr.optString(i, "").trim();
                    if (t.isEmpty()) continue;
                    boolean dup = false;
                    for (Entry e : POOL) {
                        if (e.token.equals(t)) {
                            dup = true;
                            break;
                        }
                    }
                    if (dup) continue;
                    Entry entry = new Entry();
                    entry.token = t;
                    POOL.add(entry);
                }
            }
        } catch (Throwable t) {
            Logs.w(Logs.ACCOUNT, TAG, "load pool file failed", t);
        }
    }

    private static void persist() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Entry e : POOL) arr.put(e.token);
            root.put("tokens", arr);
            File f = new File(ctx.getFilesDir(), Config.accountsFile);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Logs.w(Logs.ACCOUNT, TAG, "persist pool failed", t);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String readAll(java.io.InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ */
    /* MMKV reflection                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Host R8 renames MMKV methods (defaultMMKV -> l, getString -> j, ...),
     * so look them up by SIGNATURE, never by name.
     */
    private static Object defaultMMKV() {
        try {
            Class<?> mmkv = Host.forName("com.tencent.mmkv.MMKV");
            // initialize(Context) -> String rootDir
            for (Method m : mmkv.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == Context.class && m.getReturnType() == String.class) {
                    try {
                        m.setAccessible(true);
                        m.invoke(null, ctx);
                        break;
                    } catch (Throwable ignore) { }
                }
            }
            // defaultMMKV() -> MMKV (static, no args)
            for (Method m : mmkv.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != mmkv) continue;
                if (m.getParameterTypes().length != 0) continue;
                try {
                    m.setAccessible(true);
                    Object kv = m.invoke(null);
                    if (kv != null) return kv;
                } catch (Throwable ignore) { }
            }
            // defaultMMKV(int) -> MMKV
            for (Method m : mmkv.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != mmkv) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == int.class) {
                    try {
                        m.setAccessible(true);
                        Object kv = m.invoke(null, 1);
                        if (kv != null) return kv;
                    } catch (Throwable ignore) { }
                }
            }
            // mmkvWithID(String) / mmkvWithID(String, int): default id is null
            for (Method m : mmkv.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != mmkv) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 1 && p[0] == String.class) {
                        m.setAccessible(true);
                        Object kv = m.invoke(null, (Object) null);
                        if (kv != null) return kv;
                    }
                    if (p.length == 2 && p[0] == String.class && p[1] == int.class) {
                        m.setAccessible(true);
                        Object kv = m.invoke(null, null, 1);
                        if (kv != null) return kv;
                    }
                } catch (Throwable ignore) { }
            }
            tokenError = "MMKV instance not found";
        } catch (Throwable t) {
            tokenError = "MMKV class not found: " + t;
        }
        return null;
    }

    private static String readString(Object kv, String key) {
        // SharedPreferences.getString is a framework interface method; R8 cannot rename it.
        try {
            if (kv instanceof android.content.SharedPreferences) {
                String v = ((android.content.SharedPreferences) kv).getString(key, "");
                if (v != null) return v;
            }
        } catch (Throwable t) {
            tokenError = "getString failed: " + t;
        }
        for (Method m : kv.getClass().getMethods()) {
            if (!m.getReturnType().equals(String.class)) continue;
            String name = m.getName();
            Class<?>[] p = m.getParameterTypes();
            if (("getString".equals(name) || "decodeString".equals(name))
                    && p.length >= 1 && p[0] == String.class) {
                try {
                    m.setAccessible(true);
                    Object v;
                    if (p.length == 1) v = m.invoke(kv, key);
                    else if (p.length == 2 && p[1] == String.class) v = m.invoke(kv, key, "");
                    else continue;
                    if (v != null) return String.valueOf(v);
                } catch (Throwable ignore) { }
            }
        }
        return null;
    }

    private static String findToken(JSONObject o) {
        String direct = o.optString("token", "");
        if (!direct.isEmpty()) return direct;
        JSONArray keys = o.names();
        if (keys == null) return null;
        for (int i = 0; i < keys.length(); i++) {
            String k = keys.optString(i);
            JSONObject child = o.optJSONObject(k);
            if (child != null) {
                String t = findToken(child);
                if (t != null) return t;
                continue;
            }
            JSONArray arr = o.optJSONArray(k);
            if (arr != null) {
                for (int j = 0; j < arr.length(); j++) {
                    JSONObject item = arr.optJSONObject(j);
                    if (item != null) {
                        String t = findToken(item);
                        if (t != null) return t;
                    }
                }
            }
        }
        return null;
    }
}
