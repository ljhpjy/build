package com.dsmod.deepseek;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Direct engine against chat.deepseek.com internal web APIs.
 *
 *   POST /api/v0/chat_session/create       new conversation per request
 *   POST /api/v0/chat/create_pow_challenge proof-of-work challenge
 *   POST /api/v0/chat/completion           SSE JSON-patch stream
 *   POST /api/v0/file/upload_file          multipart attachment upload
 */
public final class DeepSeekClient {
    private static final String TAG = "DSMOD";

    public static final String PATH_SESSION = "/api/v0/chat_session/create";
    public static final String PATH_POW = "/api/v0/chat/create_pow_challenge";
    public static final String PATH_COMPLETION = "/api/v0/chat/completion";
    public static final String PATH_UPLOAD = "/api/v0/file/upload_file";

    /** Incremental output sink. */
    public interface Sink {
        void onReasoning(String delta);
        void onContent(String delta);
    }

    /** Upstream failure carrying an HTTP-like status for protocol layers. */
    public static final class ApiException extends Exception {
        public final int status;
        public final String code;

        public ApiException(int status, String message, String code) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** create_pow_challenge payload. */
    public static final class Challenge {
        public String algorithm;
        public String challenge;
        public String salt;
        public long expireAt;
        public long difficulty;
        public String signature;
        public String targetPath;
    }

    /** One full completion result. */
    public static final class Reply {
        public String content = "";
        public String reasoning = "";
        public long usage = -1;
        public long firstTokenMs = -1;
    }

    private final String token;
    private final String deviceId;
    private final String baseUrl;

    public DeepSeekClient(String token, String deviceId) {
        this.token = token;
        this.deviceId = deviceId;
        this.baseUrl = Config.baseUrl;
    }

    public String token() {
        return token;
    }

    /* ------------------------------------------------------------------ */
    /* Session                                                             */
    /* ------------------------------------------------------------------ */

    public String createSession() throws ApiException {
        JSONObject body = new JSONObject();
        String text = post(PATH_SESSION, body);
        JSONObject session = dive(text, "data", "biz_data", "chat_session");
        String id = session == null ? "" : session.optString("id", "");
        if (id.isEmpty()) {
            throw new ApiException(502, "create session failed: " + msgOf(parse(text), text), "session_failed");
        }
        return id;
    }

    /* ------------------------------------------------------------------ */
    /* PoW                                                                 */
    /* ------------------------------------------------------------------ */

    public String powHeader(String targetPath) throws ApiException {
        return solve(targetPath).header;
    }

    private static final class Solved {
        String header;
        long expireAt;
    }

    private Solved solve(String targetPath) throws ApiException {
        Challenge c = fetchChallenge(targetPath);
        long begin = System.currentTimeMillis();
        long answer = Pow.solve(c.challenge, c.salt, c.expireAt, c.difficulty);
        if (answer < 0) {
            throw new ApiException(502, "pow: no solution within difficulty", "pow_failed");
        }
        long elapsed = System.currentTimeMillis() - begin;
        // Real browser JS takes 120..520ms for 144k rounds; pad fast native
        // answers a little so request timing stays plausible.
        if (elapsed < 90L) {
            try {
                Thread.sleep(90L - elapsed + (long) (Math.random() * 60.0));
            } catch (InterruptedException ignored) { }
        }
        Log.d(TAG, "pow solved in " + (System.currentTimeMillis() - begin)
                + "ms difficulty=" + c.difficulty);
        Solved solved = new Solved();
        solved.header = Pow.buildHeader(c.algorithm, c.challenge, c.salt, answer, c.signature, c.targetPath);
        solved.expireAt = c.expireAt;
        return solved;
    }

    public Challenge fetchChallenge(String targetPath) throws ApiException {
        String path = (targetPath == null || targetPath.isEmpty()) ? PATH_COMPLETION : targetPath;
        JSONObject body = new JSONObject();
        try {
            body.put("target_path", path);
        } catch (Throwable ignore) { }
        String text = post(PATH_POW, body);
        JSONObject root = parse(text);
        String message = msgOf(root, text);
        JSONObject challenge = dive(text, "data", "biz_data", "challenge");
        if (challenge == null) {
            throw new ApiException(502, "pow challenge failed: " + message, "pow_challenge_failed");
        }
        Challenge c = new Challenge();
        c.algorithm = challenge.optString("algorithm", "DeepSeekHashV1");
        c.challenge = challenge.optString("challenge", "");
        c.salt = challenge.optString("salt", "");
        c.expireAt = challenge.optLong("expire_at", 0L);
        c.difficulty = challenge.optLong("difficulty", 144000L);
        c.signature = challenge.optString("signature", "");
        c.targetPath = challenge.optString("target_path", path);
        if (c.challenge.isEmpty() || c.salt.isEmpty()) {
            throw new ApiException(502, "pow challenge missing fields", "pow_challenge_failed");
        }
        return c;
    }

    /* ------------------------------------------------------------------ */
    /* Completion (SSE patch stream)                                       */
    /* ------------------------------------------------------------------ */

    public Reply completion(String prompt, boolean thinking, boolean search,
                            List<String> refFileIds, Sink sink) throws ApiException {
        Reply reply = new Reply();
        long t0 = System.currentTimeMillis();

        coldStartIfNeeded();
        String sessionId = takeSession();
        String pow = takePow();
        if (Config.humanTiming) {
            try {
                Thread.sleep(120L + (long) (Math.random() * 360.0));
            } catch (InterruptedException ignored) { }
        }

        JSONObject body = new JSONObject();
        try {
            body.put("chat_session_id", sessionId);
            body.put("parent_message_id", JSONObject.NULL);
            body.put("model_type", "default");
            body.put("prompt", prompt);
            JSONArray ids = new JSONArray();
            if (refFileIds != null) for (String id : refFileIds) ids.put(id);
            body.put("ref_file_ids", ids);
            body.put("thinking_enabled", thinking);
            body.put("search_enabled", search);
            body.put("action", JSONObject.NULL);
            body.put("preempt", false);
        } catch (Throwable t) {
            throw new ApiException(400, "build completion body failed: " + t, "invalid_request");
        }

        boolean powRetried = false;
        int apiRetries = 0;
        int emptyRetries = 0;

        while (true) {
            HttpURLConnection conn = null;
            try {
                conn = open(PATH_COMPLETION);
                conn.setRequestProperty("x-ds-pow-response", pow);
                writeBody(conn, body.toString());
                int code = conn.getResponseCode();
                String contentType = String.valueOf(conn.getContentType());

                if (code != 200 || !contentType.contains("event-stream")) {
                    String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                    JSONObject root = parse(text);
                    String message = msgOf(root, text);
                    int bizCode = root == null ? 0 : root.optInt("code", 0);

                    if ((bizCode == 40301 || message.toUpperCase().contains("POW")) && !powRetried) {
                        powRetried = true;
                        pow = powHeader(PATH_COMPLETION);
                        continue;
                    }
                    if (code == 401 || code == 403 || isAuthInvalid(message)) {
                        throw new ApiException(401, "login state invalid: " + message, "auth_expired");
                    }
                    if (code == 429 || message.contains("rate")) {
                        throw new ApiException(429, "upstream rate limited: " + message, "rate_limited");
                    }
                    apiRetries += 1;
                    if (apiRetries <= 3) {
                        try { Thread.sleep(500L); } catch (InterruptedException ignored) { }
                        continue;
                    }
                    throw new ApiException(502, "upstream error: " + message, "upstream_error");
                }

                reply = readStream(conn, sink, t0);
                if (reply.content.isEmpty() && reply.reasoning.isEmpty()) {
                    if (emptyRetries < 2) {
                        emptyRetries += 1;
                        pow = powHeader(PATH_COMPLETION);
                        try { Thread.sleep(700L * emptyRetries); } catch (InterruptedException ignored) { }
                        continue;
                    }
                    throw new ApiException(429, "empty upstream stream (rate limited?)", "empty_stream");
                }
                return reply;
            } catch (ApiException e) {
                throw e;
            } catch (Throwable t) {
                throw new ApiException(502, "completion failed: " + t, "upstream_error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private Reply readStream(HttpURLConnection conn, Sink sink, long t0) throws ApiException {
        Reply reply = new Reply();
        State state = new State();
        String event = null;
        StringBuilder data = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        handleFrame(state, event, data.toString().trim());
                        event = null;
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5)).append('\n');
                }
            }
            if (data.length() > 0) handleFrame(state, event, data.toString().trim());
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw new ApiException(502, "read stream failed: " + t, "stream_failed");
        }

        reply.content = collect(state, "RESPONSE");
        reply.reasoning = collect(state, "THINK");
        reply.usage = state.usage;
        reply.firstTokenMs = state.firstTokenAt > 0 ? state.firstTokenAt - t0 : -1;
        return reply;
    }

    private void handleFrame(State state, String event, String text) throws ApiException {
        if (text.isEmpty()) return;
        JSONObject payload;
        try {
            payload = new JSONObject(text);
        } catch (Throwable t) {
            return;
        }
        if ("hint".equals(event) && "error".equals(payload.optString("type"))) {
            String message = payload.optString("content", "upstream hint error");
            String finish = payload.optString("finish_reason", "");
            if ("rate_limit_reached".equals(finish) || message.contains("rate")
                    || message.contains("\u9891\u7e41") || message.contains("\u4f11\u606f")) {
                throw new ApiException(429, message, "rate_limited");
            }
            throw new ApiException(502, message, "upstream_hint");
        }
        applyPatch(state, payload);
    }

    /* ------------------------------------------------------------------ */
    /* JSON patch stream state                                             */
    /* ------------------------------------------------------------------ */

    private static final class Fragment {
        String type = "";
        String content = "";
    }

    private static final class State {
        final List<Fragment> fragments = new ArrayList<>();
        String status = "";
        long usage = -1;
        long firstTokenAt = 0;
    }

    private static void parseFragments(JSONArray arr, List<Fragment> out) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Fragment f = new Fragment();
            f.type = o.optString("type", "");
            f.content = o.optString("content", "");
            out.add(f);
        }
    }

    private void applyPatch(State state, JSONObject patch) {
        if (!patch.has("p")) {
            Object v = patch.opt("v");
            if (v instanceof String) {
                appendLast(state, (String) v);
                return;
            }
            if (v instanceof JSONObject) {
                JSONObject response = ((JSONObject) v).optJSONObject("response");
                if (response != null) {
                    JSONArray fragments = response.optJSONArray("fragments");
                    if (fragments != null) {
                        state.fragments.clear();
                        parseFragments(fragments, state.fragments);
                    }
                    String status = response.optString("status", "");
                    if (!status.isEmpty()) state.status = status;
                }
            }
            return;
        }
        String path = patch.optString("p", "");
        String op = patch.optString("o", "APPEND");
        if ("BATCH".equals(op)) {
            // Sub-patch paths are relative to the batch path, e.g.
            // response + {p:"accumulated_token_usage"}.
            String base = path.endsWith("/") ? path : path + "/";
            JSONArray subs = patch.optJSONArray("v");
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject sub = subs.optJSONObject(i);
                    if (sub == null) continue;
                    if (sub.has("p")) {
                        try {
                            JSONObject copy = new JSONObject(sub.toString());
                            copy.put("p", base + sub.optString("p", ""));
                            applyPatch(state, copy);
                        } catch (Throwable ignore) { }
                    } else {
                        applyPatch(state, sub);
                    }
                }
            }
            return;
        }
        if ("response/fragments".equals(path) && "APPEND".equals(op)) {
            JSONArray arr = patch.optJSONArray("v");
            if (arr != null) parseFragments(arr, state.fragments);
        } else if ("response/fragments/-1/content".equals(path)) {
            appendLast(state, patch.optString("v", ""));
        } else if ("response/status".equals(path)) {
            state.status = patch.optString("v", "");
        } else if ("response/accumulated_token_usage".equals(path)) {
            Object v = patch.opt("v");
            if (v instanceof Number) state.usage = ((Number) v).longValue();
        }
    }

    private static void appendLast(State state, String text) {
        if (text == null || text.isEmpty()) return;
        if (state.fragments.isEmpty()) state.fragments.add(new Fragment());
        Fragment last = state.fragments.get(state.fragments.size() - 1);
        last.content = last.content + text;
        if (state.firstTokenAt == 0 && ("RESPONSE".equals(last.type) || "THINK".equals(last.type))) {
            state.firstTokenAt = System.currentTimeMillis();
        }
    }

    private static String collect(State state, String type) {
        StringBuilder sb = new StringBuilder();
        for (Fragment f : state.fragments) {
            if (type.equals(f.type)) sb.append(f.content);
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Warm caches (one reusable session + one prefetched pow header)      */
    /* ------------------------------------------------------------------ */

    private static final class Cache {
        volatile String sessionId;
        volatile String pow;
        volatile long powExpireAt;
        volatile boolean sessionBusy;
        volatile boolean powBusy;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Cache> CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ExecutorService PREFETCH =
            java.util.concurrent.Executors.newFixedThreadPool(2,
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "dsmod-prefetch");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private Cache cache() {
        return CACHE.computeIfAbsent(token == null ? "" : token, k -> new Cache());
    }

    private String takeSession() throws ApiException {
        Cache c = cache();
        String id = c.sessionId;
        c.sessionId = null;
        if (id == null || id.isEmpty()) id = createSession();
        prefetchSession();
        return id;
    }

    private void prefetchSession() {
        try {
            PREFETCH.execute(new Runnable() {
                @Override
                public void run() {
                    Cache c = cache();
                    if (c.sessionBusy || c.sessionId != null) return;
                    c.sessionBusy = true;
                    try {
                        c.sessionId = createSession();
                    } catch (Throwable ignore) {
                    } finally {
                        c.sessionBusy = false;
                    }
                }
            });
        } catch (Throwable ignore) { }
    }

    private String takePow() throws ApiException {
        Cache c = cache();
        String header = c.pow;
        long expireAt = c.powExpireAt;
        c.pow = null;
        boolean usable = header != null && !header.isEmpty()
                && (expireAt <= 0
                || expireAt * 1000L - System.currentTimeMillis() > Config.powExpirySafetyMs);
        if (usable) {
            prefetchPow();
            return header;
        }
        Solved solved = solve(PATH_COMPLETION);
        prefetchPow();
        return solved.header;
    }

    private void prefetchPow() {
        try {
            PREFETCH.execute(new Runnable() {
                @Override
                public void run() {
                    Cache c = cache();
                    if (c.powBusy || c.pow != null) return;
                    c.powBusy = true;
                    try {
                        Solved solved = solve(PATH_COMPLETION);
                        c.pow = solved.header;
                        c.powExpireAt = solved.expireAt;
                    } catch (Throwable ignore) {
                    } finally {
                        c.powBusy = false;
                    }
                }
            });
        } catch (Throwable ignore) { }
    }

    /* ------------------------------------------------------------------ */
    /* Cold start (once per account per process)                           */
    /* ------------------------------------------------------------------ */

    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> COLD_STARTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void coldStartIfNeeded() {
        if (!Config.coldStart) return;
        String key = token == null ? "" : token;
        if (COLD_STARTED.putIfAbsent(key, Boolean.TRUE) != null) return;
        try {
            String did = didFromDevice();
            get("/api/v0/client/settings?did=" + did + "&scope=banner");
            JSONObject device = new JSONObject();
            device.put("device_id", deviceId);
            device.put("device_model", "");
            post("/api/v0/users/auth_token/check_device", device);
            Log.d(TAG, "cold start sequence done");
        } catch (Throwable t) {
            Log.w(TAG, "cold start failed (ignored): " + t);
        }
    }

    private String didFromDevice() {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("did:" + deviceId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", d[i] & 0xFF));
            String h = hex.toString();
            return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
                    + "-" + h.substring(16, 20) + "-" + h.substring(20, 32);
        } catch (Throwable t) {
            return deviceId;
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(String.format("%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(data == null ? 0 : data.length);
        }
    }

    private String get(String path) throws ApiException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout((int) Math.min(Config.requestTimeoutMs, 600000L));
            conn.setInstanceFollowRedirects(true);
            applyHeaders(conn, false);
            int code = conn.getResponseCode();
            return readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        } catch (Throwable t) {
            throw new ApiException(502, "GET " + path + " failed: " + t, "network_error");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /* ------------------------------------------------------------------ */
    /* File upload                                                         */
    /* ------------------------------------------------------------------ */

    private static final java.util.concurrent.ConcurrentHashMap<String, CachedFile> FILE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class CachedFile {
        String id;
        long expireAt;
    }

    public String uploadFile(byte[] data, String mime, String name) throws ApiException {
        String hash = sha256Hex(data);
        String cacheKey = (token == null ? "" : token) + ":" + hash;
        CachedFile cached = FILE_CACHE.get(cacheKey);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.id;
        }
        String pow = powHeader(PATH_UPLOAD);
        String boundary = "----dsmod" + UUID.randomUUID().toString().replace("-", "");
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";

        ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
        try {
            bodyStream.write(head.getBytes(StandardCharsets.UTF_8));
            bodyStream.write(data);
            bodyStream.write(tail.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            throw new ApiException(500, "upload body failed", "upload_failed");
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + PATH_UPLOAD);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            applyHeaders(conn, false);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("x-ds-pow-response", pow);
            try (OutputStream os = conn.getOutputStream()) {
                bodyStream.writeTo(os);
                os.flush();
            }
            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JSONObject root = parse(text);
            JSONObject biz = dive(text, "data", "biz_data");
            String id = biz == null ? "" : biz.optString("id", "");
            if (id.isEmpty()) {
                throw new ApiException(code >= 400 ? code : 502,
                        "upload failed: " + msgOf(root, text), "upload_failed");
            }
            if (FILE_CACHE.size() > 256) FILE_CACHE.clear();
            CachedFile entry = new CachedFile();
            entry.id = id;
            entry.expireAt = System.currentTimeMillis() + 30 * 60_000L;
            FILE_CACHE.put(cacheKey, entry);
            return id;
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw new ApiException(502, "upload failed: " + t, "upload_failed");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /* ------------------------------------------------------------------ */
    /* HTTP helpers                                                        */
    /* ------------------------------------------------------------------ */

    private String post(String path, JSONObject body) throws ApiException {
        HttpURLConnection conn = null;
        try {
            conn = open(path);
            writeBody(conn, body.toString());
            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code == 401 || code == 403 || isAuthInvalid(msgOf(parse(text), text))) {
                throw new ApiException(401, "login state invalid", "auth_expired");
            }
            return text;
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw new ApiException(502, path + " failed: " + t, "network_error");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection open(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout((int) Math.min(Config.requestTimeoutMs, 600000L));
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(true);
        applyHeaders(conn, true);
        return conn;
    }

    private void applyHeaders(HttpURLConnection conn, boolean addContentType) {
        conn.setRequestProperty("accept", "*/*");
        // Keep identity: manually asking for gzip makes HttpURLConnection skip
        // its transparent decompression, so JSON/SSE bodies would be unreadable.
        conn.setRequestProperty("accept-encoding", "identity");
        conn.setRequestProperty("accept-language", Config.ACCEPT_LANGUAGE);
        if (addContentType) conn.setRequestProperty("content-type", "application/json");
        conn.setRequestProperty("origin", baseUrl);
        conn.setRequestProperty("referer", baseUrl + "/");
        conn.setRequestProperty("user-agent", Config.UA_CHROME_WIN);
        conn.setRequestProperty("sec-ch-ua", Config.SEC_CH_UA);
        conn.setRequestProperty("sec-ch-ua-mobile", "?0");
        conn.setRequestProperty("sec-ch-ua-platform", "\"Windows\"");
        conn.setRequestProperty("sec-fetch-site", "same-origin");
        conn.setRequestProperty("sec-fetch-mode", "cors");
        conn.setRequestProperty("sec-fetch-dest", "empty");
        conn.setRequestProperty("priority", "u=1, i");
        conn.setRequestProperty("x-client-bundle-id", "com.deepseek.chat");
        conn.setRequestProperty("x-client-locale", "zh_CN");
        conn.setRequestProperty("x-client-platform", "web");
        conn.setRequestProperty("x-client-timezone-offset", String.valueOf(-8 * 3600));
        conn.setRequestProperty("x-client-version", Config.CLIENT_VERSION);
        conn.setRequestProperty("x-device-id", deviceId);
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("authorization", "Bearer " + token);
        }
    }

    private static void writeBody(HttpURLConnection conn, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONObject parse(String text) {
        try {
            return new JSONObject(text);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String msgOf(JSONObject root, String raw) {
        if (root == null) return brief(raw);
        String msg = root.optString("msg", "");
        if (!msg.isEmpty()) return msg;
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            String biz = data.optString("biz_msg", "");
            if (!biz.isEmpty()) return biz;
        }
        return brief(raw);
    }

    private static String brief(String text) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    /** Dig a JSON object path; intermediate values must be objects. */
    private static JSONObject dive(String text, String... path) {
        JSONObject cur = parse(text);
        if (cur == null) return null;
        for (int i = 0; i < path.length; i++) {
            if (i == path.length - 1) return cur.optJSONObject(path[i]);
            cur = cur.optJSONObject(path[i]);
            if (cur == null) return null;
        }
        return cur;
    }

    private static boolean isAuthInvalid(String message) {
        if (message == null) return false;
        String m = message.trim().toUpperCase();
        return m.equals("INVALID_TOKEN") || m.equals("TOKEN_EXPIRED") || m.equals("TOKEN_INVALID")
                || m.equals("USER_NOT_FOUND") || m.equals("NOT_LOGGED_IN");
    }
}
