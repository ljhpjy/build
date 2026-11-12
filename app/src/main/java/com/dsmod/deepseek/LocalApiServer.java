package com.dsmod.deepseek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local HTTP API server: OpenAI + Anthropic compatible.
 *
 *   POST /v1/chat/completions          OpenAI chat (stream / non-stream)
 *   POST /v1/messages                  Anthropic Messages (stream / non-stream)
 *   POST /v1/messages/count_tokens     Anthropic token estimate
 *   GET  /v1/models                    model list
 *   GET  /healthz                      health / status
 */
public final class LocalApiServer {
    private static final String TAG = "DSMOD";
    private static final String VERSION = "1.0.0";

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile ServerSocket server;
    private static volatile ExecutorService pool;
    private static volatile Semaphore gate = new Semaphore(Config.concurrency);
    private static volatile String deviceId = "";
    private static volatile Context appContext;
    private static volatile long startedAtWall = 0L;
    private static final ThreadLocal<String> REQ_ID = new ThreadLocal<>();

    private static final java.util.concurrent.atomic.AtomicLong REQUESTS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ERRORS =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile String lastError = "";
    private static volatile String lastErrorKind = "";

    private LocalApiServer() {}

    private static void log(String cat, String message) {
        Logs.i(cat, TAG, message);
    }

    private static String readLogTail(String cat, int lines) {
        return Logs.tail(cat, lines);
    }

    public static boolean isRunning() {
        return STARTED.get() && server != null && !server.isClosed();
    }

    public static String statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", isRunning() ? "ok" : "stopped");
            o.put("engine", "direct");
            o.put("host", Config.host);
            o.put("port", Config.port);
            o.put("version", VERSION);
            o.put("token", Accounts.currentToken() != null);
            o.put("accounts", Accounts.size());
            o.put("accounts_active", Accounts.activeCount());
            try {
                JSONObject pool = new JSONObject(Accounts.statusJson());
                o.put("host_token_present", pool.optBoolean("host_token_present"));
                o.put("last_picked", pool.optInt("last_picked", 0));
                o.put("token_error", pool.optString("token_error", ""));
            } catch (Throwable ignore) { }
            o.put("api_key", !Config.apiKey.isEmpty());
            o.put("thinking", Config.thinking);
            o.put("requests", REQUESTS.get());
            o.put("errors", ERRORS.get());
            o.put("last_error", lastError);
            o.put("last_error_kind", lastErrorKind);
            o.put("uptime_sec", startedAtWall == 0L ? 0L
                    : (System.currentTimeMillis() - startedAtWall) / 1000L);
        } catch (Throwable ignore) { }
        return o.toString();
    }

    public static synchronized void ensureStarted(Context context) {
        if (STARTED.get() && server != null && !server.isClosed()) return;
        appContext = context.getApplicationContext();
        try {
            loadConfigFile(appContext);
            Accounts.init(appContext);
            deviceId = loadDeviceId(appContext);
            gate = new Semaphore(Math.max(1, Config.concurrency));
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName(Config.host), Config.port));
            server = ss;
            startedAtWall = System.currentTimeMillis();
            pool = new java.util.concurrent.ThreadPoolExecutor(
                    0, 64, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<Runnable>());
            STARTED.set(true);
            Thread acceptor = new Thread(() -> acceptLoop(ss), "dsmod-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
            log(Logs.MAIN, "local api listening on " + Config.host + ":" + Config.port);
        } catch (Throwable t) {
            Logs.e(Logs.MAIN, TAG, "start local api failed on port " + Config.port, t);
            STARTED.set(false);
        }
    }

    private static void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                Socket socket = ss.accept();
                ExecutorService p = pool;
                if (p != null) {
                    try {
                        p.execute(() -> handle(socket));
                    } catch (Throwable rejected) {
                        try { socket.close(); } catch (Throwable ignore) { }
                    }
                } else {
                    socket.close();
                }
            } catch (Throwable t) {
                if (ss.isClosed()) break;
                try { Thread.sleep(50L); } catch (InterruptedException ignored) { }
            }
        }
    }

    private static void handle(Socket socket) {
        boolean acquired = false;
        String logLine = null;
        long startedAt = 0L;
        try {
            socket.setSoTimeout(120000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            HttpRequest req = readRequest(in);
            if (req == null) {
                writeJson(out, 400, errJson("bad request", "invalid_request", null));
                return;
            }
            String rawPath = req.path;
            String query = "";
            int q = rawPath.indexOf('?');
            if (q >= 0) {
                query = rawPath.substring(q + 1);
                rawPath = rawPath.substring(0, q);
            }
            String path = rawPath;
            REQUESTS.incrementAndGet();
            // Throttle only upstream /v1/* calls; admin + health stay responsive
            // even while a chat request is stuck in the upstream engine.
            if (path.startsWith("/v1/")) {
                acquired = gate.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!acquired) {
                    writeJson(out, 503, errJson("server busy", "server_error", "busy"));
                    return;
                }
            }
            String reqId = "req_" + Long.toHexString(System.currentTimeMillis())
                    + Integer.toHexString((int) (Math.random() * 0xFFFF));
            REQ_ID.set(reqId);
            logLine = req.method + " " + req.path + " " + reqId;
            startedAt = System.currentTimeMillis();

            if ("OPTIONS".equals(req.method)) {
                writeCors(out);
                return;
            }
            if ("/".equals(path)) {
                writeHtml(out, 200, indexHtml());
                return;
            }
            if ("/admin".equals(path) || "/admin/".equals(path)) {
                writeHtml(out, 200, adminHtml());
                return;
            }
            if ("/admin/api/overview".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                JSONObject overview = new JSONObject(statusJson());
                try {
                    JSONArray detail = new JSONArray();
                    String[] tokens = Accounts.snapshot();
                    for (int i = 0; i < tokens.length; i++) {
                        JSONObject account = new JSONObject();
                        account.put("index", i + 1);
                        account.put("token", mask(tokens[i]));
                        detail.put(account);
                    }
                    overview.put("accounts_detail", detail);
                } catch (Throwable ignore) { }
                writeJson(out, 200, overview.toString());
                return;
            }
            if ("/admin/api/accounts".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                if ("POST".equals(req.method)) {
                    handleAdminAccountsPost(out, req.body);
                } else {
                    handleAdminAccountsGet(out);
                }
                return;
            }
            if ("/admin/api/accounts/add".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                JSONObject json = new JSONObject(req.body);
                String t = json.optString("token", "").trim();
                JSONObject root = new JSONObject();
                root.put("added", Accounts.addToken(t));
                try {
                    root.put("accounts", new JSONObject(Accounts.statusJson()).optJSONArray("accounts"));
                } catch (Throwable ignore) { }
                writeJson(out, 200, root.toString());
                return;
            }
            if ("/admin/api/accounts/remove".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                JSONObject json = new JSONObject(req.body);
                int index = json.optInt("index", -1) - 1;
                boolean removed = index >= 0 && Accounts.removeToken(index);
                JSONObject root = new JSONObject();
                root.put("removed", removed);
                try {
                    root.put("accounts", new JSONObject(Accounts.statusJson()).optJSONArray("accounts"));
                } catch (Throwable ignore) { }
                writeJson(out, 200, root.toString());
                return;
            }
            if ("/admin/api/accounts/verify".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                String[] tokens = Accounts.snapshot();
                JSONObject root = new JSONObject();
                JSONArray results = new JSONArray();
                int okCount = 0;
                for (int i = 0; i < tokens.length; i++) {
                    Accounts.VerifyResult vr = Accounts.verify(tokens[i]);
                    JSONObject item = new JSONObject();
                    item.put("index", i + 1);
                    item.put("ok", vr.ok);
                    item.put("detail", vr.detail);
                    results.put(item);
                    if (vr.ok) okCount += 1;
                }
                root.put("valid", okCount);
                root.put("total", tokens.length);
                root.put("results", results);
                writeJson(out, 200, root.toString());
                return;
            }
            if ("/admin/api/config".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                if ("POST".equals(req.method) || "PUT".equals(req.method)) {
                    handleAdminConfigPost(out, req.body);
                } else {
                    writeJson(out, 200, configJson().toString());
                }
                return;
            }
            if ("/admin/api/logs".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                String cat = queryParam(query, "cat");
                int lines = 300;
                String ls = queryParam(query, "lines");
                if (!ls.isEmpty()) {
                    try { lines = Integer.parseInt(ls); } catch (Throwable ignore) { }
                }
                org.json.JSONArray cats = new org.json.JSONArray();
                for (String c : Logs.categories()) cats.put(c);
                JSONObject logs = new JSONObject();
                logs.put("lines", lines);
                logs.put("cat", cat);
                logs.put("categories", cats);
                logs.put("log", readLogTail(cat, lines));
                writeJson(out, 200, logs.toString());
                return;
            }
            if ("/admin/api/logs/export".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                        java.util.Locale.US).format(new java.util.Date());
                String cat = queryParam(query, "cat");
                String suffix = cat.isEmpty() ? "" : "-" + cat;
                writeDownload(out, "dsmod-log" + suffix + "-" + ts + ".txt", Logs.export(cat));
                return;
            }
            if ("/admin/api/logs/clear".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                Logs.clear();
                JSONObject root = new JSONObject();
                root.put("cleared", true);
                writeJson(out, 200, root.toString());
                return;
            }
            if ("/healthz".equals(path) || "/health".equals(path)) {
                JSONObject h = new JSONObject(statusJson());
                writeJson(out, 200, h.toString());
                return;
            }
            if ("/v1/embeddings".equals(path)) {
                writeJson(out, 501, errJson(
                        "embeddings are not supported by the DeepSeek web backend",
                        "invalid_request_error", "unsupported"));
                return;
            }
            if ("/v1/models".equals(path) || "/models".equals(path)) {
                handleModels(out);
                return;
            }
            if ("/v1/chat/completions".equals(path) || "/chat/completions".equals(path)) {
                if (!"POST".equals(req.method)) {
                    writeJson(out, 405, errJson("method not allowed", "invalid_request", null));
                    return;
                }
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                handleOpenAiChat(out, req.body);
                return;
            }
            if ("/v1/completions".equals(path)) {
                if (!"POST".equals(req.method)) {
                    writeJson(out, 405, errJson("method not allowed", "invalid_request", null));
                    return;
                }
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                handleLegacyCompletions(out, req.body);
                return;
            }
            if ("/v1/responses".equals(path)) {
                if (!"POST".equals(req.method)) {
                    writeJson(out, 405, errJson("method not allowed", "invalid_request", null));
                    return;
                }
                if (!authorized(req)) {
                    writeJson(out, 401, errJson("invalid api key", "authentication_error", null));
                    return;
                }
                handleResponses(out, req.body);
                return;
            }
            if ("/v1/messages".equals(path)) {
                if (!"POST".equals(req.method)) {
                    writeJson(out, 405, anthropicError(405, "method not allowed"));
                    return;
                }
                if (!authorized(req)) {
                    writeJson(out, 401, anthropicError(401, "invalid api key"));
                    return;
                }
                handleAnthropicMessages(out, req.body);
                return;
            }
            if ("/v1/messages/count_tokens".equals(path)) {
                if (!authorized(req)) {
                    writeJson(out, 401, anthropicError(401, "invalid api key"));
                    return;
                }
                handleCountTokens(out, req.body);
                return;
            }
            writeJson(out, 404, errJson("not found: " + path, "not_found", null));
        } catch (Throwable t) {
            String msg = String.valueOf(t);
            // Client went away (browser refresh / SSE cancel): expected, not a server fault.
            if (msg.contains("Broken pipe") || msg.contains("Connection reset")
                    || msg.contains("Socket closed")) {
                log(Logs.HTTP, "client closed: " + msg);
            } else {
                ERRORS.incrementAndGet();
                lastError = msg;
                log(Logs.HTTP, "connection error: " + msg);
            }
        } finally {
            if (acquired) gate.release();
            if (logLine != null) {
                log(Logs.HTTP, logLine + " " + (System.currentTimeMillis() - startedAt) + "ms");
            }
            REQ_ID.remove();
            try { socket.close(); } catch (Throwable ignore) { }
        }
    }

    private static boolean authorized(HttpRequest req) {
        if (Config.apiKey == null || Config.apiKey.isEmpty()) return true;
        String auth = req.headers.optString("authorization", "");
        if (("Bearer " + Config.apiKey).equals(auth) || Config.apiKey.equals(auth)) return true;
        return Config.apiKey.equals(req.headers.optString("x-api-key", ""));
    }

    /* ------------------------------------------------------------------ */
    /* HTTP helpers                                                        */
    /* ------------------------------------------------------------------ */

    private static final class HttpRequest {
        String method = "GET";
        String path = "/";
        String body = "";
        final JSONObject headers = new JSONObject();
    }

    private static HttpRequest readRequest(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            byte[] cur = head.toByteArray();
            int n = cur.length;
            if (n >= 4 && cur[n - 4] == '\r' && cur[n - 3] == '\n'
                    && cur[n - 2] == '\r' && cur[n - 1] == '\n') {
                break;
            }
            if (n > 65536) return null;
        }
        if (head.size() == 0) return null;
        String raw = new String(head.toByteArray(), StandardCharsets.UTF_8);
        String[] lines = raw.split("\r\n");
        if (lines.length == 0) return null;
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        HttpRequest req = new HttpRequest();
        req.method = first[0];
        req.path = first[1];
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String key = lines[i].substring(0, colon).trim().toLowerCase();
            String value = lines[i].substring(colon + 1).trim();
            try {
                req.headers.put(key, value);
            } catch (Throwable ignore) { }
            if ("content-length".equals(key)) {
                try { contentLength = Integer.parseInt(value); } catch (Throwable ignore) { }
            }
        }
        if (contentLength > 0) {
            if (contentLength > 32 * 1024 * 1024) return null;
            byte[] data = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(data, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            req.body = new String(data, 0, read, StandardCharsets.UTF_8);
        }
        return req;
    }

    private static void writeJson(OutputStream out, int code, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String reqId = REQ_ID.get();
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + (reqId != null ? "X-Request-Id: " + reqId + "\r\n" : "")
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Content-Type, Authorization, x-api-key, anthropic-version, x-requested-with\r\n"
                + "Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if (!key.equals(part.substring(0, eq))) continue;
            try {
                return java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8");
            } catch (Throwable t) {
                return part.substring(eq + 1);
            }
        }
        return "";
    }

    private static void writeDownload(OutputStream out, String filename, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Disposition: attachment; filename=\"" + filename + "\"\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static void writeCors(OutputStream out) throws Exception {
        String head = "HTTP/1.1 204 No Content\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type, Authorization, x-api-key, anthropic-version, x-requested-with\r\n"
                + "Access-Control-Max-Age: 86400\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void writeSseHeaders(OutputStream out) throws Exception {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void writeChunk(OutputStream out, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void endChunked(OutputStream out) throws Exception {
        out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String reason(int code) {
        if (code == 200) return "OK";
        if (code == 400) return "Bad Request";
        if (code == 401) return "Unauthorized";
        if (code == 403) return "Forbidden";
        if (code == 404) return "Not Found";
        if (code == 405) return "Method Not Allowed";
        if (code == 413) return "Payload Too Large";
        if (code == 429) return "Too Many Requests";
        if (code == 500) return "Internal Server Error";
        return "Error";
    }

    private static String errJson(String message, String type, String code) {
        JSONObject err = new JSONObject();
        JSONObject root = new JSONObject();
        try {
            err.put("message", message);
            err.put("type", type == null ? "invalid_request_error" : type);
            if (code != null) err.put("code", code);
            root.put("error", err);
        } catch (Throwable ignore) { }
        return root.toString();
    }

    /** Human-readable Chinese prefix so callers can tell rate-limit from upstream failure. */
    private static String friendlyMessage(DeepSeekClient.ApiException e) {
        String code = e.code == null ? "" : e.code;
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (e.status == 429) return "上游限流（" + (code.isEmpty() ? "rate_limited" : code) + "）：" + msg;
        if (e.status == 401) return "登录失效（" + (code.isEmpty() ? "auth_expired" : code) + "）：" + msg;
        if (e.status == 503) return "账号不可用（" + (code.isEmpty() ? "account_unavailable" : code) + "）：" + msg;
        if (e.status == 413) return "上下文过长（" + (code.isEmpty() ? "prompt_too_long" : code) + "）：" + msg;
        if (e.status >= 500) return "上游接口失败（" + (code.isEmpty() ? "upstream_error" : code) + "）：" + msg;
        return msg;
    }

    /** JSON error response with a Retry-After header (rate-limit). */
    private static void writeJsonRetry(OutputStream out, int code, String body, int retryAfterSec)
            throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Retry-After: " + retryAfterSec + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static String anthropicError(int status, String message) {
        JSONObject error = new JSONObject();
        JSONObject root = new JSONObject();
        try {
            String type;
            if (status == 400) type = "invalid_request_error";
            else if (status == 401) type = "authentication_error";
            else if (status == 403) type = "permission_error";
            else if (status == 404) type = "not_found_error";
            else if (status == 413) type = "request_too_large";
            else if (status == 429) type = "rate_limit_error";
            else type = "api_error";
            error.put("type", type);
            error.put("message", message);
            root.put("type", "error");
            root.put("error", error);
        } catch (Throwable ignore) { }
        return root.toString();
    }

    /* ------------------------------------------------------------------ */
    /* OpenAI chat                                                         */
    /* ------------------------------------------------------------------ */

    private static String pickToken() throws DeepSeekClient.ApiException {
        if (Accounts.size() == 0) {
            throw new DeepSeekClient.ApiException(503,
                    "no account available: open DeepSeek and sign in first", "account_unavailable");
        }
        String token = Accounts.pick();
        if (token == null) {
            long wait = Accounts.nextAvailableAt() - System.currentTimeMillis();
            if (wait > Config.maxCooldownWaitMs) {
                long sec = Math.max(1L, (wait + 999L) / 1000L);
                throw new DeepSeekClient.ApiException(429,
                        "all accounts are cooling down, retry in " + sec + "s", "rate_limited");
            }
            try { Thread.sleep(Math.max(0L, wait)); } catch (InterruptedException ignored) { }
            token = Accounts.pick();
        }
        if (token == null) {
            if (Accounts.activeCount() == 0) {
                throw new DeepSeekClient.ApiException(503,
                        "all accounts are disabled: sign in to DeepSeek again", "account_unavailable");
            }
            throw new DeepSeekClient.ApiException(429,
                    "all accounts are cooling down, retry later", "rate_limited");
        }
        log(Logs.ACCOUNT, Accounts.lastPickLabel());
        return token;
    }

    private interface AccountAttempt<T> {
        T run(String token) throws DeepSeekClient.ApiException;
    }

    /**
     * fuckds-style failover: one attempt per pool account. Account-scoped
     * failures (401 / 429 / 5xx) park that account and immediately retry
     * with the next one inside the same request.
     */
    private static <T> T withFailover(AccountAttempt<T> attempt) throws DeepSeekClient.ApiException {
        int total = Math.max(1, Accounts.size());
        DeepSeekClient.ApiException last = null;
        for (int i = 0; i < total; i++) {
            String token = pickToken();
            try {
                return attempt.run(token);
            } catch (DeepSeekClient.ApiException e) {
                if (e.status == 401) {
                    Accounts.markAuthFailed(token, e.getMessage());
                } else if (e.status == 429) {
                    Accounts.markRateLimited(token, Config.rateLimitCooldownMs);
                } else if (e.status >= 500) {
                    Accounts.markError(token, e.getMessage());
                } else {
                    throw e;
                }
                last = e;
                Logs.w(Logs.ACCOUNT, TAG, "failover after " + e.status + " "
                        + (e.code == null ? "" : e.code) + ", attempts=" + total);
            }
        }
        throw last != null ? last
                : new DeepSeekClient.ApiException(503, "no account available", "account_unavailable");
    }

    private static void handleOpenAiChat(OutputStream out, String body) {
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String token = null;
        try {
            ChatRunner.Request req = ChatRunner.normalizeOpenAI(new JSONObject(body));
            final String sid = id;
            final String model = req.model;

            if (req.stream) {
                token = pickToken();
                DeepSeekClient client = new DeepSeekClient(token, deviceId);
                writeSseHeaders(out);
                final boolean[] roleSent = {false};
                ChatRunner.StreamSink sink = new ChatRunner.StreamSink() {
                    @Override
                    public void onReasoning(String delta) {
                        try {
                            writeOpenAiChunk(out, sid, model, "reasoning_content", delta, roleSent);
                        } catch (Throwable ignore) { }
                    }

                    @Override
                    public void onContent(String delta) {
                        try {
                            writeOpenAiChunk(out, sid, model, "content", delta, roleSent);
                        } catch (Throwable ignore) { }
                    }
                };
                ChatRunner.Result result = ChatRunner.run(req, client, sink);
                if (!result.toolCalls.isEmpty()) {
                    writeToolCallChunks(out, id, model, result.toolCalls, roleSent);
                }
                writeOpenAiFinish(out, id, model,
                        result.toolCalls.isEmpty() ? "stop" : "tool_calls");
                if (req.streamIncludeUsage) writeOpenAiUsage(out, id, model, result);
                writeChunk(out, "data: [DONE]\n\n");
                endChunked(out);
                return;
            }

            ChatRunner.Result result = withFailover(new AccountAttempt<ChatRunner.Result>() {
                @Override
                public ChatRunner.Result run(String tok) throws DeepSeekClient.ApiException {
                    return ChatRunner.run(req, new DeepSeekClient(tok, deviceId), null);
                }
            });
            writeJson(out, 200, buildOpenAiResponse(id, req.model, result).toString());
        } catch (DeepSeekClient.ApiException e) {
            lastErrorKind = e.code == null ? "" : e.code;
            Logs.e(Logs.HTTP, TAG, "api error: " + e.status + " "
                    + (e.code == null ? "" : e.code + " ")
                    + (e.getMessage() == null ? "" : e.getMessage()));
            if (e.status == 401 && token != null) {
                Accounts.markAuthFailed(token, e.getMessage());
            } else if (e.status == 429 && token != null) {
                Accounts.markRateLimited(token, Config.rateLimitCooldownMs);
            } else if (e.status >= 500 && token != null) {
                Accounts.markError(token, e.getMessage());
            }
            try {
                String errBody = errJson(friendlyMessage(e), typeFor(e.status), e.code);
                if (e.status == 429) {
                    writeJsonRetry(out, 429, errBody, 60);
                } else {
                    writeJson(out, e.status >= 400 ? e.status : 502, errBody);
                }
            } catch (Throwable ignore) { }
        } catch (Throwable t) {
            try {
                writeJson(out, 500, errJson(String.valueOf(t), "server_error", null));
            } catch (Throwable ignore) { }
        }
    }

    private static void handleLegacyCompletions(OutputStream out, String body) {
        String id = "cmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String token = null;
        try {
            JSONObject json = new JSONObject(body);
            Object promptObj = json.opt("prompt");
            String prompt;
            if (promptObj instanceof String) {
                prompt = (String) promptObj;
            } else if (promptObj instanceof JSONArray) {
                StringBuilder sb = new StringBuilder();
                JSONArray arr = (JSONArray) promptObj;
                for (int i = 0; i < arr.length(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(arr.optString(i, ""));
                }
                prompt = sb.toString();
            } else {
                prompt = "";
            }
            if (prompt.trim().isEmpty()) {
                writeJson(out, 400, errJson("prompt is required", "invalid_request", "invalid_prompt"));
                return;
            }
            JSONArray messages = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", prompt);
            messages.put(user);
            JSONObject wrapped = new JSONObject();
            wrapped.put("messages", messages);
            ChatRunner.Request req = ChatRunner.normalizeOpenAI(wrapped);
            req.stream = json.optBoolean("stream", false);
            req.model = json.optString("model", Config.defaultModel);

            token = pickToken();
            DeepSeekClient client = new DeepSeekClient(token, deviceId);
            if (req.stream) {
                writeSseHeaders(out);
                final String modelName = req.model;
                ChatRunner.StreamSink sink = new ChatRunner.StreamSink() {
                    @Override
                    public void onReasoning(String delta) {
                        try { writeCompletionChunk(out, id, modelName, delta); } catch (Throwable ignore) { }
                    }

                    @Override
                    public void onContent(String delta) {
                        try { writeCompletionChunk(out, id, modelName, delta); } catch (Throwable ignore) { }
                    }
                };
                ChatRunner.run(req, client, sink);
                writeChunk(out, "data: [DONE]\n\n");
                endChunked(out);
                return;
            }
            ChatRunner.Result result = ChatRunner.run(req, client, null);
            JSONObject resp = new JSONObject();
            resp.put("id", id);
            resp.put("object", "text_completion");
            resp.put("created", System.currentTimeMillis() / 1000L);
            resp.put("model", req.model);
            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("text", result.content);
            choice.put("logprobs", JSONObject.NULL);
            choice.put("finish_reason", "stop");
            choices.put(choice);
            resp.put("choices", choices);
            resp.put("usage", usageOf(result));
            writeJson(out, 200, resp.toString());
        } catch (DeepSeekClient.ApiException e) {
            lastErrorKind = e.code == null ? "" : e.code;
            Logs.e(Logs.HTTP, TAG, "api error: " + e.status + " "
                    + (e.code == null ? "" : e.code + " ")
                    + (e.getMessage() == null ? "" : e.getMessage()));
            if (e.status == 401 && token != null) {
                Accounts.markAuthFailed(token, e.getMessage());
            } else if (e.status == 429 && token != null) {
                Accounts.markRateLimited(token, Config.rateLimitCooldownMs);
            } else if (e.status >= 500 && token != null) {
                Accounts.markError(token, e.getMessage());
            }
            try {
                String errBody = errJson(friendlyMessage(e), typeFor(e.status), e.code);
                if (e.status == 429) {
                    writeJsonRetry(out, 429, errBody, 60);
                } else {
                    writeJson(out, e.status >= 400 ? e.status : 502, errBody);
                }
            } catch (Throwable ignore) { }
        } catch (Throwable t) {
            try {
                writeJson(out, 500, errJson(String.valueOf(t), "server_error", null));
            } catch (Throwable ignore) { }
        }
    }

    private static void writeCompletionChunk(OutputStream out, String id, String model, String text)
            throws Exception {
        JSONObject c = new JSONObject();
        c.put("id", id);
        c.put("object", "text_completion");
        c.put("created", System.currentTimeMillis() / 1000L);
        c.put("model", model);
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("text", text);
        choice.put("logprobs", JSONObject.NULL);
        choice.put("finish_reason", JSONObject.NULL);
        choices.put(choice);
        c.put("choices", choices);
        writeChunk(out, "data: " + c.toString() + "\n\n");
    }

    private static String typeFor(int status) {
        if (status == 400) return "invalid_request_error";
        if (status == 401) return "authentication_error";
        if (status == 403) return "permission_error";
        if (status == 404) return "not_found_error";
        if (status == 413) return "request_too_large";
        if (status == 429) return "rate_limit_error";
        return "api_error";
    }

    private static void writeOpenAiChunk(OutputStream out, String id, String model,
                                         String field, String delta, boolean[] roleSent) throws Exception {
        JSONObject d = new JSONObject();
        if (!roleSent[0]) {
            d.put("role", "assistant");
            roleSent[0] = true;
        }
        d.put(field, delta);
        JSONObject c = chunkShell(id, model);
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", d);
        choice.put("finish_reason", JSONObject.NULL);
        choices.put(choice);
        c.put("choices", choices);
        writeChunk(out, "data: " + c.toString() + "\n\n");
    }

    private static JSONObject chunkShell(String id, String model) throws Exception {
        JSONObject c = new JSONObject();
        c.put("id", id);
        c.put("object", "chat.completion.chunk");
        c.put("created", System.currentTimeMillis() / 1000L);
        c.put("model", model);
        return c;
    }

    private static void writeToolCallChunks(OutputStream out, String id, String model,
                                            java.util.List<ToolKit.Call> calls, boolean[] roleSent)
            throws Exception {
        for (int i = 0; i < calls.size(); i++) {
            ToolKit.Call call = calls.get(i);
            JSONObject tc = new JSONObject();
            tc.put("index", i);
            tc.put("id", callId(i));
            tc.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", call.name);
            fn.put("arguments", call.arguments);
            tc.put("function", fn);
            JSONArray arr = new JSONArray();
            arr.put(tc);
            JSONObject d = new JSONObject();
            if (!roleSent[0]) {
                d.put("role", "assistant");
                roleSent[0] = true;
            }
            d.put("tool_calls", arr);
            JSONObject c = chunkShell(id, model);
            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("delta", d);
            choice.put("finish_reason", JSONObject.NULL);
            choices.put(choice);
            c.put("choices", choices);
            writeChunk(out, "data: " + c.toString() + "\n\n");
        }
    }

    private static void writeOpenAiFinish(OutputStream out, String id, String model,
                                          String finishReason) throws Exception {
        JSONObject c = chunkShell(id, model);
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", new JSONObject());
        choice.put("finish_reason", finishReason);
        choices.put(choice);
        c.put("choices", choices);
        writeChunk(out, "data: " + c.toString() + "\n\n");
    }

    private static void writeOpenAiUsage(OutputStream out, String id, String model,
                                         ChatRunner.Result result) throws Exception {
        JSONObject c = chunkShell(id, model);
        c.put("choices", new JSONArray());
        c.put("usage", usageOf(result));
        writeChunk(out, "data: " + c.toString() + "\n\n");
    }

    private static String callId(int index) {
        return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + index;
    }

    private static JSONObject usageOf(ChatRunner.Result result) {
        JSONObject usage = new JSONObject();
        try {
            int prompt = ChatRunner.estimateTokens(result.prompt);
            int reasoning = ChatRunner.estimateTokens(result.reasoning);
            int completion = ChatRunner.estimateTokens(result.content) + reasoning;
            usage.put("prompt_tokens", prompt);
            usage.put("completion_tokens", completion);
            usage.put("total_tokens", prompt + completion);
            usage.put("estimated", true);
            if (reasoning > 0) {
                JSONObject details = new JSONObject();
                details.put("reasoning_tokens", reasoning);
                usage.put("completion_tokens_details", details);
            }
        } catch (Throwable ignore) { }
        return usage;
    }

    private static JSONObject buildOpenAiResponse(String id, String model, ChatRunner.Result result) {
        JSONObject root = new JSONObject();
        try {
            root.put("id", id);
            root.put("object", "chat.completion");
            root.put("created", System.currentTimeMillis() / 1000L);
            root.put("model", model);
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", result.content);
            if (!result.reasoning.isEmpty()) message.put("reasoning_content", result.reasoning);
            if (!result.toolCalls.isEmpty()) {
                JSONArray calls = new JSONArray();
                for (int i = 0; i < result.toolCalls.size(); i++) {
                    ToolKit.Call call = result.toolCalls.get(i);
                    JSONObject tc = new JSONObject();
                    tc.put("id", callId(i));
                    tc.put("index", i);
                    tc.put("type", "function");
                    JSONObject fn = new JSONObject();
                    fn.put("name", call.name);
                    fn.put("arguments", call.arguments);
                    tc.put("function", fn);
                    calls.put(tc);
                }
                message.put("tool_calls", calls);
            }
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("logprobs", JSONObject.NULL);
            choice.put("finish_reason", result.toolCalls.isEmpty() ? "stop" : "tool_calls");
            JSONArray choices = new JSONArray();
            choices.put(choice);
            root.put("choices", choices);
            root.put("usage", usageOf(result));
        } catch (Throwable ignore) { }
        return root;
    }

    /* ------------------------------------------------------------------ */
    /* Anthropic messages                                                  */
    /* ------------------------------------------------------------------ */

    private static void handleAnthropicMessages(OutputStream out, String body) {
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String token = null;
        try {
            ChatRunner.Request req = ChatRunner.normalizeAnthropic(new JSONObject(body));
            if (req.stream) {
                token = pickToken();
                DeepSeekClient client = new DeepSeekClient(token, deviceId);
                writeSseHeaders(out);
                AnthropicSse sse = new AnthropicSse(out, id, req.model,
                        ChatRunner.estimateTokens(req.messages.toString()));
                sse.messageStart();
                ChatRunner.StreamSink sink = new ChatRunner.StreamSink() {
                    @Override
                    public void onReasoning(String delta) {
                        try {
                            sse.thinkingDelta(delta);
                        } catch (Throwable ignore) { }
                    }

                    @Override
                    public void onContent(String delta) {
                        try {
                            sse.textDelta(delta);
                        } catch (Throwable ignore) { }
                    }
                };
                ChatRunner.Result result = ChatRunner.run(req, client, sink);
                sse.finish(result);
                endChunked(out);
                return;
            }

            ChatRunner.Result result = withFailover(new AccountAttempt<ChatRunner.Result>() {
                @Override
                public ChatRunner.Result run(String tok) throws DeepSeekClient.ApiException {
                    return ChatRunner.run(req, new DeepSeekClient(tok, deviceId), null);
                }
            });
            writeJson(out, 200, buildAnthropicResponse(id, req.model, result).toString());
        } catch (DeepSeekClient.ApiException e) {
            lastErrorKind = e.code == null ? "" : e.code;
            Logs.e(Logs.HTTP, TAG, "api error: " + e.status + " "
                    + (e.code == null ? "" : e.code + " ")
                    + (e.getMessage() == null ? "" : e.getMessage()));
            if (e.status == 401 && token != null) {
                Accounts.markAuthFailed(token, e.getMessage());
            } else if (e.status == 429 && token != null) {
                Accounts.markRateLimited(token, Config.rateLimitCooldownMs);
            } else if (e.status >= 500 && token != null) {
                Accounts.markError(token, e.getMessage());
            }
            try {
                String errBody = anthropicError(e.status, friendlyMessage(e));
                if (e.status == 429) {
                    writeJsonRetry(out, 429, errBody, 60);
                } else {
                    writeJson(out, e.status >= 400 ? e.status : 502, errBody);
                }
            } catch (Throwable ignore) { }
        } catch (Throwable t) {
            try {
                writeJson(out, 500, anthropicError(500, String.valueOf(t)));
            } catch (Throwable ignore) { }
        }
    }

    private static JSONObject buildAnthropicResponse(String id, String model, ChatRunner.Result result) {
        JSONObject root = new JSONObject();
        try {
            root.put("id", id);
            root.put("type", "message");
            root.put("role", "assistant");
            root.put("model", model);
            JSONArray blocks = new JSONArray();
            if (!result.reasoning.isEmpty()) {
                JSONObject thinking = new JSONObject();
                thinking.put("type", "thinking");
                thinking.put("thinking", result.reasoning);
                thinking.put("signature", "");
                blocks.put(thinking);
            }
            if (!result.content.isEmpty()) {
                JSONObject text = new JSONObject();
                text.put("type", "text");
                text.put("text", result.content);
                blocks.put(text);
            }
            for (int i = 0; i < result.toolCalls.size(); i++) {
                ToolKit.Call call = result.toolCalls.get(i);
                JSONObject tool = new JSONObject();
                tool.put("type", "tool_use");
                tool.put("id", "toolu_" + id.substring(Math.max(0, id.length() - 12)) + "_" + i);
                tool.put("name", call.name);
                tool.put("input", parseInput(call.arguments));
                blocks.put(tool);
            }
            if (blocks.length() == 0) {
                JSONObject text = new JSONObject();
                text.put("type", "text");
                text.put("text", "");
                blocks.put(text);
            }
            root.put("content", blocks);
            root.put("stop_reason", result.toolCalls.isEmpty() ? "end_turn" : "tool_use");
            root.put("stop_sequence", JSONObject.NULL);
            JSONObject usage = new JSONObject();
            usage.put("input_tokens", ChatRunner.estimateTokens(result.prompt));
            usage.put("output_tokens", ChatRunner.estimateTokens(result.content)
                    + ChatRunner.estimateTokens(result.reasoning));
            root.put("usage", usage);
        } catch (Throwable ignore) { }
        return root;
    }

    private static JSONObject parseInput(String args) {
        if (args != null) {
            try {
                return new JSONObject(args);
            } catch (Throwable ignore) { }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("input", args == null ? "" : args);
        } catch (Throwable ignore) { }
        return o;
    }

    private static void handleCountTokens(OutputStream out, String body) {
        try {
            ChatRunner.Request req = ChatRunner.normalizeAnthropic(new JSONObject(body));
            Prompt.Built built = Prompt.build(req.messages, req.tools);
            JSONObject resp = new JSONObject();
            resp.put("input_tokens", ChatRunner.estimateTokens(built.prompt));
            writeJson(out, 200, resp.toString());
        } catch (DeepSeekClient.ApiException e) {
            try {
                writeJson(out, e.status >= 400 ? e.status : 400, anthropicError(e.status, e.getMessage()));
            } catch (Throwable ignore) { }
        } catch (Throwable t) {
            try {
                writeJson(out, 500, anthropicError(500, String.valueOf(t)));
            } catch (Throwable ignore) { }
        }
    }

    /** Anthropic SSE writer with content block bookkeeping. */
    private static final class AnthropicSse {
        private final OutputStream out;
        private final String id;
        private final String model;
        private final int inputTokens;
        private int nextIndex = 0;
        private int thinkingIndex = -1;
        private int textIndex = -1;
        private boolean thinkingClosed = false;
        private boolean textClosed = false;

        AnthropicSse(OutputStream out, String id, String model, int inputTokens) {
            this.out = out;
            this.id = id;
            this.model = model;
            this.inputTokens = inputTokens;
        }

        void event(String name, JSONObject payload) throws Exception {
            writeChunk(out, "event: " + name + "\ndata: " + payload.toString() + "\n\n");
        }

        void messageStart() throws Exception {
            JSONObject message = new JSONObject();
            message.put("id", id);
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("model", model);
            message.put("content", new JSONArray());
            message.put("stop_reason", JSONObject.NULL);
            message.put("stop_sequence", JSONObject.NULL);
            JSONObject usage = new JSONObject();
            usage.put("input_tokens", inputTokens);
            usage.put("output_tokens", 0);
            message.put("usage", usage);
            JSONObject payload = new JSONObject();
            payload.put("type", "message_start");
            payload.put("message", message);
            event("message_start", payload);
        }

        void thinkingDelta(String delta) throws Exception {
            if (thinkingClosed) return;
            if (thinkingIndex < 0) {
                thinkingIndex = nextIndex++;
                JSONObject block = new JSONObject();
                block.put("type", "thinking");
                block.put("thinking", "");
                block.put("signature", "");
                JSONObject payload = new JSONObject();
                payload.put("type", "content_block_start");
                payload.put("index", thinkingIndex);
                payload.put("content_block", block);
                event("content_block_start", payload);
            }
            JSONObject d = new JSONObject();
            d.put("type", "thinking_delta");
            d.put("thinking", delta);
            JSONObject payload = new JSONObject();
            payload.put("type", "content_block_delta");
            payload.put("index", thinkingIndex);
            payload.put("delta", d);
            event("content_block_delta", payload);
        }

        private void closeThinking() throws Exception {
            if (thinkingIndex < 0 || thinkingClosed) return;
            thinkingClosed = true;
            JSONObject payload = new JSONObject();
            payload.put("type", "content_block_stop");
            payload.put("index", thinkingIndex);
            event("content_block_stop", payload);
        }

        void textDelta(String delta) throws Exception {
            if (textClosed) return;
            closeThinking();
            if (textIndex < 0) {
                textIndex = nextIndex++;
                JSONObject block = new JSONObject();
                block.put("type", "text");
                block.put("text", "");
                JSONObject payload = new JSONObject();
                payload.put("type", "content_block_start");
                payload.put("index", textIndex);
                payload.put("content_block", block);
                event("content_block_start", payload);
            }
            JSONObject d = new JSONObject();
            d.put("type", "text_delta");
            d.put("text", delta);
            JSONObject payload = new JSONObject();
            payload.put("type", "content_block_delta");
            payload.put("index", textIndex);
            payload.put("delta", d);
            event("content_block_delta", payload);
        }

        private void closeText() throws Exception {
            if (textIndex < 0 || textClosed) return;
            textClosed = true;
            JSONObject payload = new JSONObject();
            payload.put("type", "content_block_stop");
            payload.put("index", textIndex);
            event("content_block_stop", payload);
        }

        void finish(ChatRunner.Result result) throws Exception {
            closeThinking();
            closeText();
            if (thinkingIndex < 0 && textIndex < 0 && result.toolCalls.isEmpty()) {
                textIndex = nextIndex++;
                JSONObject block = new JSONObject();
                block.put("type", "text");
                block.put("text", "");
                JSONObject start = new JSONObject();
                start.put("type", "content_block_start");
                start.put("index", textIndex);
                start.put("content_block", block);
                event("content_block_start", start);
                JSONObject stop = new JSONObject();
                stop.put("type", "content_block_stop");
                stop.put("index", textIndex);
                event("content_block_stop", stop);
            }
            for (int i = 0; i < result.toolCalls.size(); i++) {
                ToolKit.Call call = result.toolCalls.get(i);
                int index = nextIndex++;
                JSONObject block = new JSONObject();
                block.put("type", "tool_use");
                block.put("id", "toolu_" + id.substring(Math.max(0, id.length() - 12)) + "_" + i);
                block.put("name", call.name);
                block.put("input", new JSONObject());
                JSONObject start = new JSONObject();
                start.put("type", "content_block_start");
                start.put("index", index);
                start.put("content_block", block);
                event("content_block_start", start);

                JSONObject d = new JSONObject();
                d.put("type", "input_json_delta");
                d.put("partial_json", call.arguments);
                JSONObject delta = new JSONObject();
                delta.put("type", "content_block_delta");
                delta.put("index", index);
                delta.put("delta", d);
                event("content_block_delta", delta);

                JSONObject stop = new JSONObject();
                stop.put("type", "content_block_stop");
                stop.put("index", index);
                event("content_block_stop", stop);
            }

            JSONObject messageDelta = new JSONObject();
            messageDelta.put("type", "message_delta");
            JSONObject deltaObj = new JSONObject();
            deltaObj.put("stop_reason", result.toolCalls.isEmpty() ? "end_turn" : "tool_use");
            deltaObj.put("stop_sequence", JSONObject.NULL);
            messageDelta.put("delta", deltaObj);
            JSONObject usage = new JSONObject();
            usage.put("output_tokens", ChatRunner.estimateTokens(result.content)
                    + ChatRunner.estimateTokens(result.reasoning));
            messageDelta.put("usage", usage);
            event("message_delta", messageDelta);

            JSONObject stopPayload = new JSONObject();
            stopPayload.put("type", "message_stop");
            event("message_stop", stopPayload);
        }
    }

    /* ------------------------------------------------------------------ */
    /* OpenAI Responses API                                                */
    /* ------------------------------------------------------------------ */

    private static void safePutJson(JSONArray arr, Object value) {
        try {
            arr.put(value);
        } catch (Throwable ignore) { }
    }

    private static void handleResponses(OutputStream out, String body) {
        String id = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String token = null;
        try {
            JSONObject json = new JSONObject(body);
            JSONArray messages = new JSONArray();

            String instructions = json.optString("instructions", "");
            if (!instructions.isEmpty()) {
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", instructions);
                safePutJson(messages, sys);
            }

            Object input = json.opt("input");
            if (input instanceof String) {
                JSONObject m = new JSONObject();
                m.put("role", "user");
                m.put("content", input);
                safePutJson(messages, m);
            } else if (input instanceof JSONArray) {
                JSONArray items = (JSONArray) input;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String type = item.optString("type", "");
                    String role = item.optString("role", "");
                    if (!type.isEmpty() && !"message".equals(type) && role.isEmpty()) continue;
                    Object content = item.opt("content");
                    StringBuilder text = new StringBuilder();
                    if (content instanceof String) {
                        text.append((String) content);
                    } else if (content instanceof JSONArray) {
                        JSONArray parts = (JSONArray) content;
                        for (int j = 0; j < parts.length(); j++) {
                            JSONObject part = parts.optJSONObject(j);
                            if (part == null) continue;
                            String ptype = part.optString("type", "");
                            if ("input_text".equals(ptype) || "output_text".equals(ptype)
                                    || "text".equals(ptype)) {
                                text.append(part.optString("text", ""));
                            }
                        }
                    }
                    if (text.length() == 0 && role.isEmpty()) continue;
                    JSONObject m = new JSONObject();
                    m.put("role", role.isEmpty() ? "user" : role);
                    m.put("content", text.toString());
                    safePutJson(messages, m);
                }
            }

            JSONObject wrapped = new JSONObject();
            wrapped.put("messages", messages);

            JSONArray tools = json.optJSONArray("tools");
            JSONArray normalizedTools = new JSONArray();
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject tool = tools.optJSONObject(i);
                    if (tool == null) continue;
                    JSONObject fnSrc = tool.optJSONObject("function");
                    if (fnSrc == null) fnSrc = tool;
                    String name = fnSrc.optString("name", "").trim();
                    if (name.isEmpty()) continue;
                    JSONObject fn = new JSONObject();
                    fn.put("name", name);
                    String desc = fnSrc.optString("description", "");
                    if (!desc.isEmpty()) fn.put("description", desc);
                    JSONObject params = fnSrc.optJSONObject("parameters");
                    if (params != null) fn.put("parameters", params);
                    JSONObject wrapper = new JSONObject();
                    wrapper.put("type", "function");
                    wrapper.put("function", fn);
                    safePutJson(normalizedTools, wrapper);
                }
            }
            wrapped.put("tools", normalizedTools);

            ChatRunner.Request req = ChatRunner.normalizeOpenAI(wrapped);
            req.stream = json.optBoolean("stream", false);
            req.model = json.optString("model", Config.defaultModel);

            token = pickToken();
            DeepSeekClient client = new DeepSeekClient(token, deviceId);

            if (req.stream) {
                writeSseHeaders(out);
                ResponsesSse sse = new ResponsesSse(out, id, req.model);
                sse.created();
                ChatRunner.StreamSink sink = new ChatRunner.StreamSink() {
                    @Override
                    public void onReasoning(String delta) {
                        try {
                            sse.reasoningDelta(delta);
                        } catch (Throwable ignore) { }
                    }

                    @Override
                    public void onContent(String delta) {
                        try {
                            sse.textDelta(delta);
                        } catch (Throwable ignore) { }
                    }
                };
                ChatRunner.Result result = ChatRunner.run(req, client, sink);
                sse.finish(result);
                endChunked(out);
                return;
            }

            ChatRunner.Result result = ChatRunner.run(req, client, null);
            writeJson(out, 200, buildResponsesResponse(id, req.model, result).toString());
        } catch (DeepSeekClient.ApiException e) {
            lastErrorKind = e.code == null ? "" : e.code;
            Logs.e(Logs.HTTP, TAG, "api error: " + e.status + " "
                    + (e.code == null ? "" : e.code + " ")
                    + (e.getMessage() == null ? "" : e.getMessage()));
            if (e.status == 401 && token != null) {
                Accounts.markAuthFailed(token, e.getMessage());
            } else if (e.status == 429 && token != null) {
                Accounts.markRateLimited(token, Config.rateLimitCooldownMs);
            } else if (e.status >= 500 && token != null) {
                Accounts.markError(token, e.getMessage());
            }
            try {
                String errBody = errJson(friendlyMessage(e), typeFor(e.status), e.code);
                if (e.status == 429) {
                    writeJsonRetry(out, 429, errBody, 60);
                } else {
                    writeJson(out, e.status >= 400 ? e.status : 502, errBody);
                }
            } catch (Throwable ignore) { }
        } catch (Throwable t) {
            try {
                writeJson(out, 500, errJson(String.valueOf(t), "server_error", null));
            } catch (Throwable ignore) { }
        }
    }

    private static JSONObject buildResponsesResponse(String id, String model, ChatRunner.Result result) {
        JSONObject root = new JSONObject();
        try {
            root.put("id", id);
            root.put("object", "response");
            root.put("created_at", System.currentTimeMillis() / 1000L);
            root.put("status", "completed");
            root.put("model", model);
            root.put("output", responsesOutput(id, result));
            root.put("usage", responsesUsage(result));
        } catch (Throwable ignore) { }
        return root;
    }

    private static JSONArray responsesOutput(String id, ChatRunner.Result result) {
        JSONArray output = new JSONArray();
        try {
            String suffix = id.substring(Math.max(0, id.length() - 8));
            if (!result.reasoning.isEmpty()) {
                JSONObject r = new JSONObject();
                r.put("id", "rs_" + suffix);
                r.put("type", "reasoning");
                r.put("status", "completed");
                r.put("summary", new JSONArray());
                output.put(r);
            }
            if (!result.content.isEmpty()) {
                JSONObject part = new JSONObject();
                part.put("type", "output_text");
                part.put("text", result.content);
                part.put("annotations", new JSONArray());
                JSONArray content = new JSONArray();
                content.put(part);
                JSONObject msg = new JSONObject();
                msg.put("id", "msg_" + suffix);
                msg.put("type", "message");
                msg.put("role", "assistant");
                msg.put("status", "completed");
                msg.put("content", content);
                output.put(msg);
            }
            for (int i = 0; i < result.toolCalls.size(); i++) {
                ToolKit.Call call = result.toolCalls.get(i);
                JSONObject fc = new JSONObject();
                fc.put("id", "fc_" + suffix + "_" + i);
                fc.put("type", "function_call");
                fc.put("status", "completed");
                fc.put("call_id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                fc.put("name", call.name);
                fc.put("arguments", call.arguments);
                output.put(fc);
            }
        } catch (Throwable ignore) { }
        return output;
    }

    private static JSONObject responsesUsage(ChatRunner.Result result) {
        JSONObject usage = new JSONObject();
        try {
            int input = ChatRunner.estimateTokens(result.prompt);
            int output = ChatRunner.estimateTokens(result.content)
                    + ChatRunner.estimateTokens(result.reasoning);
            usage.put("input_tokens", input);
            usage.put("output_tokens", output);
            usage.put("total_tokens", input + output);
        } catch (Throwable ignore) { }
        return usage;
    }

    /** Responses API SSE writer. */
    private static final class ResponsesSse {
        private final OutputStream out;
        private final String id;
        private final String model;
        private final String suffix;
        private int outputIndex = 0;
        private boolean reasoningOpen = false;
        private boolean textOpen = false;

        ResponsesSse(OutputStream out, String id, String model) {
            this.out = out;
            this.id = id;
            this.model = model;
            this.suffix = id.substring(Math.max(0, id.length() - 8));
        }

        void event(String name, JSONObject payload) throws Exception {
            writeChunk(out, "event: " + name + "\ndata: " + payload.toString() + "\n\n");
        }

        private JSONObject shell(String status) throws Exception {
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("object", "response");
            r.put("created_at", System.currentTimeMillis() / 1000L);
            r.put("status", status);
            r.put("model", model);
            r.put("output", new JSONArray());
            return r;
        }

        void created() throws Exception {
            JSONObject payload = new JSONObject();
            payload.put("type", "response.created");
            payload.put("response", shell("in_progress"));
            event("response.created", payload);
        }

        void reasoningDelta(String delta) throws Exception {
            if (!reasoningOpen) {
                reasoningOpen = true;
                outputIndex++;
                JSONObject item = new JSONObject();
                item.put("id", "rs_" + suffix);
                item.put("type", "reasoning");
                item.put("status", "in_progress");
                item.put("summary", new JSONArray());
                JSONObject added = new JSONObject();
                added.put("type", "response.output_item.added");
                added.put("output_index", outputIndex - 1);
                added.put("item", item);
                event("response.output_item.added", added);
            }
            JSONObject payload = new JSONObject();
            payload.put("type", "response.reasoning_summary_text.delta");
            payload.put("output_index", 0);
            payload.put("summary_index", 0);
            payload.put("delta", delta);
            event("response.reasoning_summary_text.delta", payload);
        }

        void textDelta(String delta) throws Exception {
            if (!textOpen) {
                textOpen = true;
                outputIndex++;
                JSONObject item = new JSONObject();
                item.put("id", "msg_" + suffix);
                item.put("type", "message");
                item.put("role", "assistant");
                item.put("status", "in_progress");
                item.put("content", new JSONArray());
                JSONObject added = new JSONObject();
                added.put("type", "response.output_item.added");
                added.put("output_index", outputIndex - 1);
                added.put("item", item);
                event("response.output_item.added", added);

                JSONObject part = new JSONObject();
                part.put("type", "output_text");
                part.put("text", "");
                part.put("annotations", new JSONArray());
                JSONObject partAdded = new JSONObject();
                partAdded.put("type", "response.content_part.added");
                partAdded.put("item_id", "msg_" + suffix);
                partAdded.put("output_index", outputIndex - 1);
                partAdded.put("content_index", 0);
                partAdded.put("part", part);
                event("response.content_part.added", partAdded);
            }
            JSONObject payload = new JSONObject();
            payload.put("type", "response.output_text.delta");
            payload.put("item_id", "msg_" + suffix);
            payload.put("output_index", reasoningOpen ? 1 : 0);
            payload.put("content_index", 0);
            payload.put("delta", delta);
            event("response.output_text.delta", payload);
        }

        void finish(ChatRunner.Result result) throws Exception {
            JSONObject resp = shell("completed");
            resp.put("output", responsesOutput(id, result));
            resp.put("usage", responsesUsage(result));
            JSONObject payload = new JSONObject();
            payload.put("type", "response.completed");
            payload.put("response", resp);
            event("response.completed", payload);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Index page / misc                                                   */
    /* ------------------------------------------------------------------ */

    private static void writeHtml(OutputStream out, int code, String html) throws Exception {
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static String indexHtml() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>DSMod DeepSeek Local API</title></head><body>"
                + "<h1>DSMod DeepSeek Local API</h1>"
                + "<p>监听: 0.0.0.0:" + Config.port + "（所有网卡）</p>"
                + "<p>OpenAI base_url: <code>http://127.0.0.1:" + Config.port + "/v1</code></p>"
                + "<p>Anthropic base_url: <code>http://127.0.0.1:" + Config.port + "</code></p>"
                + "<h2>Endpoints</h2><ul>"
                + "<li>POST /v1/chat/completions</li>"
                + "<li>POST /v1/completions</li>"
                + "<li>POST /v1/messages</li>"
                + "<li>POST /v1/messages/count_tokens</li>"
                + "<li>POST /v1/responses</li>"
                + "<li>GET /v1/models</li>"
                + "<li>GET /healthz</li>"
                + "<li>GET /admin/api/overview</li>"
                + "</ul></body></html>";
    }
    private static final boolean ADMIN_PAGE_V2 = true;

    private static String adminHtml() {
        if (ADMIN_PAGE_V2) return AdminPage.build(Config.port);
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>DSMod Admin</title><style>"
                + "body{font-family:system-ui,-apple-system,sans-serif;margin:0;padding:20px;max-width:960px;color:#222}"
                + "h1{font-size:20px}h2{font-size:15px;margin:0 0 8px}"
                + ".card{border:1px solid #e3e3e3;border-radius:10px;padding:14px;margin:14px 0}"
                + "pre{background:#f7f7f7;padding:10px;border-radius:6px;overflow:auto;font-size:12px;max-height:280px}"
                + "table{border-collapse:collapse;width:100%;font-size:13px}"
                + "td,th{border-bottom:1px solid #eee;padding:6px;text-align:left}"
                + ".muted{color:#888;font-size:12px}"
                + "</style></head><body>"
                + "<h1>DSMod &middot; DeepSeek Local API</h1>"
                + "<p class=\"muted\">监听 0.0.0.0:" + Config.port + " &nbsp;|&nbsp; OpenAI http://127.0.0.1:" + Config.port + "/v1 &nbsp;|&nbsp; Anthropic http://127.0.0.1:" + Config.port + "</p>"
                + "<div class=\"card\"><h2>Status</h2><pre id=\"status\">loading...</pre></div>"
                + "<div class=\"card\"><h2>Accounts</h2><div id=\"accounts\">loading...</div>"
                + "<p class=\"muted\">API key（可选）：<input id=\"apikey\" placeholder=\"Bearer key\" style=\"width:220px\" onchange=\"localStorage.setItem('dsmod_key',this.value)\"></p>"
                + "<p class=\"muted\">添加：<input id=\"tokenAdd\" placeholder=\"粘贴 userToken\" style=\"width:280px\"> <button onclick=\"addToken()\">添加</button> "
                + "<button onclick=\"verifyPool()\">验证全部</button> <span id=\"poolmsg\" class=\"muted\"></span></p>"
                + "<textarea id=\"tokens\" rows=\"3\" style=\"width:100%;box-sizing:border-box\" placeholder=\"一行一个 userToken；整体替换号池\"></textarea>"
                + "<p><button onclick=\"savePool()\">整体替换</button></p></div>"
                + "<div class=\"card\"><h2>Config</h2>"
                + "<p class=\"muted\">apiKey <input id=\"cApiKey\" style=\"width:200px\" placeholder=\"留空不修改\"> "
                + "thinkingMode <select id=\"cThinkingMode\"><option>always</option><option>auto</option><option>never</option></select> "
                + "thinking <input id=\"cThinking\" type=\"checkbox\"> "
                + "search <input id=\"cSearch\" type=\"checkbox\"> "
                + "concurrency <input id=\"cConcurrency\" type=\"number\" min=\"1\" style=\"width:70px\"></p>"
                + "<p class=\"muted\">maxPromptChars <input id=\"cMaxPrompt\" type=\"number\" style=\"width:120px\"> "
                + "maxAttachmentChars <input id=\"cMaxAttachment\" type=\"number\" style=\"width:130px\"> "
                + "requestTimeoutMs <input id=\"cTimeout\" type=\"number\" style=\"width:130px\"></p>"
                + "<p><button onclick=\"saveConfig()\">保存配置</button> <span id=\"cfgmsg\" class=\"muted\"></span></p>"
                + "<pre id=\"config\">loading...</pre></div>"
                + "<div class=\"card\"><h2>Logs (tail)</h2><pre id=\"logs\">loading...</pre></div>"
                + "<script>"
                + "function esc(s){return String(s).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c]})}"
                + "function gh(extra){const h=Object.assign({},extra||{});const k=localStorage.getItem('dsmod_key')||'';if(k){h['Authorization']='Bearer '+k;h['x-api-key']=k}return h}"
                + "async function j(u,o){o=o||{};o.headers=gh(o.headers);const r=await fetch(u,o);return await r.json()}"
                + "async function savePool(){const v=document.getElementById('tokens').value.split('\\n').map(function(s){return s.trim()}).filter(Boolean);"
                + "if(!v.length){document.getElementById('poolmsg').textContent='请输入 token';return}"
                + "try{const r=await fetch('/admin/api/accounts',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:JSON.stringify({tokens:v})});"
                + "document.getElementById('poolmsg').textContent='已保存 '+r.status}catch(e){document.getElementById('poolmsg').textContent='错误: '+e}}"
                + "async function saveConfig(){const b={apiKey:document.getElementById('cApiKey').value,"
                + "thinkingMode:document.getElementById('cThinkingMode').value,"
                + "thinking:document.getElementById('cThinking').checked,search:document.getElementById('cSearch').checked,"
                + "concurrency:Number(document.getElementById('cConcurrency').value),"
                + "maxPromptChars:Number(document.getElementById('cMaxPrompt').value),"
                + "maxAttachmentChars:Number(document.getElementById('cMaxAttachment').value),"
                + "requestTimeoutMs:Number(document.getElementById('cTimeout').value)};"
                + "try{const r=await fetch('/admin/api/config',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:JSON.stringify(b)});"
                + "document.getElementById('cfgmsg').textContent='已保存 '+r.status}catch(e){document.getElementById('cfgmsg').textContent='错误: '+e}}"
                + "async function addToken(){const t=document.getElementById('tokenAdd').value.trim();"
                + "if(!t){document.getElementById('poolmsg').textContent='请输入 token';return}"
                + "try{const r=await fetch('/admin/api/accounts/add',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:JSON.stringify({token:t})});"
                + "document.getElementById('poolmsg').textContent='已添加 HTTP '+r.status;document.getElementById('tokenAdd').value='';load()}catch(e){document.getElementById('poolmsg').textContent='错误: '+e}}"
                + "async function removeToken(i){if(!confirm('删除账号 #'+i+'？'))return;"
                + "try{const r=await fetch('/admin/api/accounts/remove',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:JSON.stringify({index:i})});"
                + "const d=await r.json();document.getElementById('poolmsg').textContent=d.removed?'已删除':'未删除（当前登录号受保护）';load()}catch(e){document.getElementById('poolmsg').textContent='错误: '+e}}"
                + "async function verifyPool(){document.getElementById('poolmsg').textContent='验证中…';"
                + "try{const r=await fetch('/admin/api/accounts/verify',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:'{}'});const d=await r.json();"
                + "document.getElementById('poolmsg').textContent='有效 '+d.valid+'/'+d.total}catch(e){document.getElementById('poolmsg').textContent='错误: '+e}}"
                + "async function load(){"
                + "try{const s=await j('/healthz');document.getElementById('status').textContent=JSON.stringify(s,null,2)}catch(e){document.getElementById('status').textContent='error: '+e}"
                + "try{const a=await j('/admin/api/accounts');"
                + "let h='<table><tr><th>#</th><th>token</th><th>requests</th><th>cooldown(ms)</th><th>last</th><th></th></tr>';"
                + "(a.accounts||[]).forEach(function(x){h+='<tr><td>'+(x.current?'* ':'')+x.index+'</td><td>'+esc(x.token)+'</td><td>'+x.requests+'</td><td>'+x.cooldownMs+'</td><td>'+esc(x.lastError||'-')+'</td>"
                + "<td><button onclick=\"removeToken('+x.index+')\">删除</button></td></tr>'});"
                + "h+='</table><p class=\"muted\">last picked: '+(a.last_picked||0)+' | host token: '+(a.host_token_present?'yes':'no')+'</p>';"
                + "document.getElementById('accounts').innerHTML=h}catch(e){document.getElementById('accounts').textContent='error: '+e}"
                + "try{const c=await j('/admin/api/config');document.getElementById('config').textContent=JSON.stringify(c,null,2);"
                + "document.getElementById('cApiKey').value='';"
                + "document.getElementById('cApiKey').placeholder=c.apiKeySet?'已设置（留空不修改）':'未设置';"
                + "if(c.thinkingMode)document.getElementById('cThinkingMode').value=c.thinkingMode;"
                + "document.getElementById('cThinking').checked=!!c.thinking;"
                + "document.getElementById('cSearch').checked=!!c.search;document.getElementById('cConcurrency').value=c.concurrency;"
                + "document.getElementById('cMaxPrompt').value=c.maxPromptChars;document.getElementById('cMaxAttachment').value=c.maxAttachmentChars;"
                + "document.getElementById('cTimeout').value=c.requestTimeoutMs}catch(e){}"
                + "try{const l=await j('/admin/api/logs');document.getElementById('logs').textContent=l.log||'(empty)'}catch(e){}"
                + "}"
                + "document.getElementById('apikey').value=localStorage.getItem('dsmod_key')||'';"
                + "load();setInterval(load,5000);"
                + "</script></body></html>";
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 8) return "****";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    /* ------------------------------------------------------------------ */
    /* Admin API                                                           */
    /* ------------------------------------------------------------------ */

    private static void handleAdminAccountsGet(OutputStream out) throws Exception {
        writeJson(out, 200, Accounts.statusJson());
    }

    private static void handleAdminAccountsPost(OutputStream out, String body) throws Exception {
        JSONObject json = new JSONObject(body);
        JSONArray arr = json.optJSONArray("tokens");
        if (arr == null) {
            writeJson(out, 400, errJson("tokens is required", "invalid_request", null));
            return;
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String t = arr.optString(i, "").trim();
            if (!t.isEmpty() && !list.contains(t)) list.add(t);
        }
        if (list.isEmpty()) {
            writeJson(out, 400, errJson("tokens is empty", "invalid_request", null));
            return;
        }
        boolean append = "append".equals(json.optString("mode", "replace"));
        int saved = 0;
        if (append) {
            for (String t : list) {
                if (Accounts.addToken(t)) saved += 1;
            }
        } else {
            Accounts.setPool(list.toArray(new String[0]));
            saved = list.size();
        }
        JSONObject root = new JSONObject();
        root.put("saved", saved);
        root.put("mode", append ? "append" : "replace");
        try {
            root.put("accounts", new JSONObject(Accounts.statusJson()).optJSONArray("accounts"));
        } catch (Throwable ignore) { }
        writeJson(out, 200, root.toString());
    }

    private static JSONObject configJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("host", Config.host);
            o.put("port", Config.port);
            o.put("apiKeySet", Config.apiKey != null && !Config.apiKey.isEmpty());
            o.put("thinking", Config.thinking);
            o.put("thinkingMode", Config.thinkingMode);
            o.put("search", Config.search);
            o.put("concurrency", Config.concurrency);
            o.put("maxRounds", Config.maxRounds);
            o.put("maxPromptChars", Config.maxPromptChars);
            o.put("maxAttachmentChars", Config.maxAttachmentChars);
            o.put("overflowToAttachment", Config.overflowToAttachment);
            o.put("requestTimeoutMs", Config.requestTimeoutMs);
        } catch (Throwable ignore) { }
        return o;
    }

    private static void saveConfigFile(Context ctx) {
        try {
            JSONObject o = configJson();
            File f = new File(ctx.getFilesDir(), Config.configFile);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Logs.w(Logs.CONFIG, TAG, "save config failed", t);
        }
    }

    private static void handleAdminConfigPost(OutputStream out, String body) throws Exception {
        JSONObject json = new JSONObject(body);
        if (json.has("apiKey")) {
            String key = json.optString("apiKey", "");
            if (!key.isEmpty() && !"****".equals(key)) Config.apiKey = key;
        }
        if (json.has("thinking")) Config.thinking = json.optBoolean("thinking", Config.thinking);
        if (json.has("thinkingMode")) {
            String mode = json.optString("thinkingMode", Config.thinkingMode);
            if ("always".equals(mode) || "auto".equals(mode) || "never".equals(mode)) {
                Config.thinkingMode = mode;
            }
        }
        if (json.has("search")) Config.search = json.optBoolean("search", Config.search);
        if (json.has("concurrency")) {
            Config.concurrency = Math.max(1, json.optInt("concurrency", Config.concurrency));
        }
        if (json.has("maxPromptChars")) {
            Config.maxPromptChars = Math.max(1000, json.optInt("maxPromptChars", Config.maxPromptChars));
        }
        if (json.has("maxAttachmentChars")) {
            Config.maxAttachmentChars = Math.max(1000,
                    json.optInt("maxAttachmentChars", Config.maxAttachmentChars));
        }
        if (json.has("overflowToAttachment")) {
            Config.overflowToAttachment = json.optBoolean("overflowToAttachment", Config.overflowToAttachment);
        }
        if (json.has("requestTimeoutMs")) {
            Config.requestTimeoutMs = Math.max(10000L,
                    json.optLong("requestTimeoutMs", Config.requestTimeoutMs));
        }
        if (json.has("host")) Config.host = json.optString("host", Config.host);
        if (json.has("port")) Config.port = json.optInt("port", Config.port);
        Context ctx = appContext;
        if (ctx != null) saveConfigFile(ctx);
        gate = new Semaphore(Math.max(1, Config.concurrency));
        writeJson(out, 200, configJson().toString());
    }

    /* ------------------------------------------------------------------ */
    /* Model list                                                          */
    /* ------------------------------------------------------------------ */

    private static void handleModels(OutputStream out) throws Exception {
        JSONArray data = new JSONArray();
        String[] models = {
                Config.defaultModel, "deepseek-chat", "deepseek-reasoner", "deepseek-expert",
                "deepseek-v4.1-flash-1017",
        };
        java.util.List<String> seen = new java.util.ArrayList<>();
        for (String id : models) {
            if (id == null || id.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            JSONObject m = new JSONObject();
            try {
                m.put("id", id);
                m.put("object", "model");
                m.put("created", 0);
                m.put("owned_by", "deepseek-web");
            } catch (Throwable ignore) { }
            data.put(m);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("object", "list");
            root.put("data", data);
        } catch (Throwable ignore) { }
        writeJson(out, 200, root.toString());
    }

    /* ------------------------------------------------------------------ */
    /* Config / device id                                                  */
    /* ------------------------------------------------------------------ */

    private static void loadConfigFile(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), Config.configFile);
            if (!f.isFile()) return;
            byte[] data = readAll(new FileInputStream(f));
            JSONObject o = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (o.has("host")) Config.host = o.optString("host", Config.host);
            if (o.has("port")) Config.port = o.optInt("port", Config.port);
            if (o.has("apiKey")) Config.apiKey = o.optString("apiKey", Config.apiKey);
            if (o.has("thinking")) Config.thinking = o.optBoolean("thinking", Config.thinking);
            if (o.has("thinkingMode")) Config.thinkingMode = o.optString("thinkingMode", Config.thinkingMode);
            if (o.has("search")) Config.search = o.optBoolean("search", Config.search);
            if (o.has("maxRounds")) Config.maxRounds = o.optInt("maxRounds", Config.maxRounds);
            if (o.has("concurrency")) Config.concurrency = o.optInt("concurrency", Config.concurrency);
            if (o.has("maxPromptChars")) {
                Config.maxPromptChars = o.optInt("maxPromptChars", Config.maxPromptChars);
            }
            if (o.has("maxAttachmentChars")) {
                Config.maxAttachmentChars = o.optInt("maxAttachmentChars", Config.maxAttachmentChars);
            }
            if (o.has("overflowToAttachment")) {
                Config.overflowToAttachment = o.optBoolean("overflowToAttachment", Config.overflowToAttachment);
            }
            if (o.has("requestTimeoutMs")) {
                Config.requestTimeoutMs = o.optLong("requestTimeoutMs", Config.requestTimeoutMs);
            }
        } catch (Throwable t) {
            Logs.w(Logs.CONFIG, TAG, "load config file failed", t);
        }
    }

    private static String loadDeviceId(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), Config.deviceFile);
            if (f.isFile()) {
                String s = new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) return s;
            }
            String id = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID()
                    .toString().replace("-", "").substring(0, 8);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(id.getBytes(StandardCharsets.UTF_8));
            }
            return id;
        } catch (Throwable t) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
