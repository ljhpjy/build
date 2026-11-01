package com.dsmod.probe;

import android.content.Context;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local OpenAI/Anthropic-compatible API gateway.
 *
 * <p>Rebuilt from the 1.7.5 APK dex (R8-obfuscated). The original class had 29 fields and
 * 137 methods. This file is written in blocks; block 1 contains constants, state, protocol
 * selection, logging and the public entry points. Block 2+ add the HTTP transport, the
 * OpenAI/Anthropic/Responses routes and the tool bridges.</p>
 */
abstract class LocalApiGateway {
    static final String TAG = "DeekseepLocalApi";
    static final String PROTOCOL_OPENAI = "openai";
    static final String PROTOCOL_ANTHROPIC = "anthropic";
    static final String PREFS = "deekseep_local_api";
    static final String KEY_PROTOCOL = "protocol";
    static final String KEY_PORT = "port";
    static final String KEY_API_KEY = "api_key";
    static final String LOG_FILE = "deekseep_api.log";
    static final String LOG_OLD = "deekseep_api.log.1";
    static final long LOG_MAX_BYTES = 0x80000L;
    static final int HEADER_LIMIT = 32 * 1024;
    static final int BODY_LIMIT = 8 * 1024 * 1024;
    static final String KEY_PREFIX = "sk-deekseep-";
    static final String DEFAULT_MODEL = "deepseek-chat";
    static final String EXPERT_MODEL = "deepseek-expert";
    static final String REASONER_MODEL = "deepseek-reasoner";
    static final String DISCLAIMER = "本回答由 AI 生成，内容仅供参考，请仔细甄别";

    /** Guard for start/stop and status publication. */
    static final Object LOCK = new Object();
    /** Per-connection state: the socket currently served by this thread. */
    static final ThreadLocal<Socket> CURRENT_SOCKET = new ThreadLocal<>();
    /** Per-connection state: the client id announced on the request, if any. */
    static final ThreadLocal<String> CURRENT_CLIENT_ID = new ThreadLocal<>();
    /**
     * Client ids whose peer half has closed. A plain ThreadLocal would be useless here: the
     * watcher thread writes the flag, but the worker thread that owns the socket reads it, so
     * the marker has to be shared across threads and keyed by client id.
     */
    static final ConcurrentHashMap<String, Boolean> CLIENT_GONE = new ConcurrentHashMap<>();
    /** A client id not refreshed for this long is treated as disconnected. */
    static final long CLIENT_STALE_MS = 180000L;

    static volatile boolean running;
    static volatile ServerSocket serverSocket;
    static volatile javax.net.ssl.SSLServerSocket tlsServerSocket;
    static volatile Thread tlsAcceptThread;
    static volatile int tlsPort;
    static volatile boolean tlsRunning;
    static volatile Thread acceptThread;
    static volatile Thread watchThread;
    static volatile ThreadPoolExecutor workers;
    static volatile int port;
    static volatile String protocol = PROTOCOL_OPENAI;
    static volatile String apiKey = "";
    static volatile String lastError = "";
    static volatile Context appContext;

    static final AtomicLong REQUESTS = new AtomicLong();
    static final AtomicLong ERRORS = new AtomicLong();
    static final AtomicLong CANCELLED = new AtomicLong();
    static final AtomicLong TOKENS_IN = new AtomicLong();
    static final AtomicLong TOKENS_OUT = new AtomicLong();
    static final AtomicLong LAST_REQUEST_AT = new AtomicLong();
    static final ConcurrentHashMap<String, Long> CLIENTS = new ConcurrentHashMap<>();

    private LocalApiGateway() {}

    // ------------------------------------------------------------- protocol

    static String currentProtocol() {
        return PROTOCOL_ANTHROPIC.equals(protocol) ? PROTOCOL_ANTHROPIC : PROTOCOL_OPENAI;
    }

    static String normalizeProtocol(String value) {
        if (value == null) return PROTOCOL_OPENAI;
        String v = value.trim().toLowerCase(Locale.US);
        return PROTOCOL_ANTHROPIC.equals(v) ? PROTOCOL_ANTHROPIC : PROTOCOL_OPENAI;
    }

    static String protocolLabel() {
        return PROTOCOL_ANTHROPIC.equals(currentProtocol()) ? "Anthropic" : "OpenAI";
    }

    /** Base URL clients should use, including the protocol-specific prefix. */
    static String baseUrl() {
        String host = localAddress();
        if (host.length() == 0) host = "127.0.0.1";
        String scheme = "http://";
        return scheme + host + ":" + (port > 0 ? port : 8765) + protocolPrefix();
    }

    static String protocolPrefix() {
        return PROTOCOL_ANTHROPIC.equals(currentProtocol()) ? "" : "/v1";
    }

    static String localAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    String host = addr.getHostAddress();
                    if (host != null && host.indexOf(':') < 0) return host;
                }
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }

    /** Lower is better; used to pick the preferred LAN interface. */
    static int interfaceRank(String name) {
        if (name == null) return 9;
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith("wlan") || n.startsWith("wifi")) return 0;
        if (n.startsWith("rmnet")) return 1;
        if (n.startsWith("tun") || n.startsWith("vpn")) return 5;
        return 3;
    }

    // ---------------------------------------------------------------- API key

    static String generateApiKey() {
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        return KEY_PREFIX + Base64.encodeToString(raw, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    static String fingerprint(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("deekseep-local-");
            for (int i = 0; i < 6; i++) sb.append(String.format(Locale.US, "%02x", digest[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "deekseep-local";
        }
    }

    static boolean checkAuth(java.util.Map<String, String> headers) {
        if (headers == null) return false;
        String expected = apiKey == null ? "" : apiKey.trim();
        if (expected.length() == 0) return true;
        String auth = headers.get("authorization");
        if (auth != null) {
            String v = auth.trim();
            if (v.regionMatches(true, 0, "Bearer ", 0, 7)) v = v.substring(7).trim();
            if (v.equals(expected)) return true;
        }
        String xKey = headers.get("x-api-key");
        return xKey != null && xKey.trim().equals(expected);
    }

    // ---------------------------------------------------------------- logging

    static synchronized void log(String line) {
        if (line == null || line.length() == 0) return;
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        Log.i(TAG, "[LOCAL_API] " + line);
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            File file = new File("/data/data/com.deepseek.chat/files/" + LOG_FILE);
            if (file.isFile() && file.length() > LOG_MAX_BYTES) {
                File old = new File("/data/data/com.deepseek.chat/files/" + LOG_OLD);
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            Writer w = new FileWriter(file, true);
            try {
                w.write(stamp + "  " + line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {}
    }

    static String describe(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String msg = cause.getMessage();
        if (msg != null && msg.trim().length() > 0) return msg.trim();
        return cause.getClass().getSimpleName();
    }

    static String requestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    static long now() {
        return System.currentTimeMillis();
    }

    // -------------------------------------------------------------- lifecycle

    static Bundle start(Context context) {
        Bundle out = new Bundle();
        Context ctx = context.getApplicationContext();
        appContext = ctx;
        synchronized (LOCK) {
            if (running) {
                out.putBoolean("accepted", true);
                out.putString("endpoint", baseUrl());
                fillStatus(ctx, out);
                return out;
            }
            try {
                loadSettings(ctx);
                ServerSocket socket = openServerSocket();
                serverSocket = socket;
                port = socket.getLocalPort();
                running = true;
                lastError = "";
                acceptThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        acceptLoop(socket, "http");
                    }
                }, "Deekseep-API-Accept");
                acceptThread.setDaemon(true);
                acceptThread.start();
                startTlsListener(ctx);
                startClientWatch();
                log("START port=" + port + " protocol=" + currentProtocol()
                        + (tlsRunning ? " tls_port=" + tlsPort : " tls=off"));
                out.putBoolean("accepted", true);
                out.putString("endpoint", baseUrl());
            } catch (Throwable t) {
                running = false;
                serverSocket = null;
                port = 0;
                lastError = "启动本地 API 失败：" + describe(t);
                Log.w(TAG, lastError, t);
                out.putBoolean("accepted", false);
                out.putString("error", lastError);
            }
            fillStatus(ctx, out);
            return out;
        }
    }

    static Bundle stop(Context context) {
        Bundle out = new Bundle();
        Context ctx = context.getApplicationContext();
        appContext = ctx;
        synchronized (LOCK) {
            running = false;
            ServerSocket socket = serverSocket;
            serverSocket = null;
            if (socket != null) {
                try { socket.close(); } catch (Throwable ignored) {}
            }
            javax.net.ssl.SSLServerSocket tls = tlsServerSocket;
            tlsServerSocket = null;
            tlsRunning = false;
            if (tls != null) {
                try { tls.close(); } catch (Throwable ignored) {}
            }
            tlsPort = 0;
            Thread watch = watchThread;
            watchThread = null;
            if (watch != null) {
                try { watch.interrupt(); } catch (Throwable ignored) {}
            }
            ThreadPoolExecutor pool = workers;
            workers = null;
            if (pool != null) {
                try { pool.shutdownNow(); } catch (Throwable ignored) {}
            }
            CLIENTS.clear();
            if (port > 0) log("STOP port=" + port);
            protocol = currentProtocol();
            out.putBoolean("accepted", true);
            fillStatus(ctx, out);
            return out;
        }
    }

    static void loadSettings(Context ctx) {
        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences(PREFS, 0);
        protocol = normalizeProtocol(prefs.getString(KEY_PROTOCOL, PROTOCOL_OPENAI));
        apiKey = prefs.getString(KEY_API_KEY, "");
        if (apiKey == null) apiKey = "";
    }

    static Bundle saveSettings(Context context, Bundle in) {
        Bundle out = new Bundle();
        Context ctx = context.getApplicationContext();
        appContext = ctx;
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS, 0);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        // 1.7.5: accept a new local API port without restarting through the UI.
        if (in != null && in.containsKey("port")) {
            int requested = in.getInt("port", 0);
            if (requested >= 1024 && requested <= 65535) {
                editor.putInt(KEY_PORT, requested);
            } else if (requested != 0) {
                out.putBoolean("accepted", false);
                out.putString("error", "invalid_port must be between 1024 and 65535");
                fillStatus(ctx, out);
                return out;
            }
        }
        if (in != null && in.containsKey("api_key")) {
            String requestedKey = in.getString("api_key", "");
            if (requestedKey != null && requestedKey.trim().length() == 0) {
                editor.remove(KEY_API_KEY);
                apiKey = "";
            }
        }
        if (in != null) {
            String p = normalizeProtocol(in.getString("protocol", currentProtocol()));
            editor.putString(KEY_PROTOCOL, p);
            protocol = p;
            String key = in.getString("api_key", "");
            if (key != null && key.trim().length() > 0) {
                apiKey = key.trim();
                editor.putString(KEY_API_KEY, apiKey);
            }
        }
        editor.apply();
        out.putBoolean("accepted", true);
        fillStatus(ctx, out);
        return out;
    }

    static String rotateApiKey(Context context) {
        Context ctx = context.getApplicationContext();
        appContext = ctx;
        String key = generateApiKey();
        apiKey = key;
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_API_KEY, key).apply();
        return key;
    }

    static Bundle status(Context context) {
        Bundle out = new Bundle();
        appContext = context.getApplicationContext();
        out.putBoolean("accepted", true);
        fillStatus(context, out);
        return out;
    }

    static void fillStatus(Context context, Bundle out) {
        out.putBoolean("running", running);
        out.putInt("port", port);
        out.putString("protocol", currentProtocol());
        out.putString("endpoint", publicBaseUrl());
        out.putString("local_endpoint", baseUrl());
        out.putString("direct_root", directRoot());
        out.putString("api_key", apiKey == null ? "" : apiKey);
        out.putString("key_fingerprint", fingerprint(apiKey == null ? "" : apiKey));
        out.putString("error", lastError == null ? "" : lastError);
        out.putLong("requests", REQUESTS.get());
        out.putLong("errors", ERRORS.get());
        out.putLong("cancelled", CANCELLED.get());
        out.putLong("tokens_in", TOKENS_IN.get());
        out.putLong("tokens_out", TOKENS_OUT.get());
        out.putLong("last_request_at", LAST_REQUEST_AT.get());
        out.putInt("clients", CLIENTS.size());
        out.putString("backend", HostCompat.hostReady() ? "ready" : "not_ready");
        out.putString("disclaimer", DISCLAIMER);
    }

    // ------------------------------------------------ transport (block 2)

    /**
     * 1.7.5 TLS listener: mirrors the dex {@code p()} method. Exposes HTTPS on port+1 using a
     * PKCS#12 keystore at {@code deekseep_local_api_server.p12} in the app files dir. Silently
     * skipped when the keystore is absent, so plain HTTP stays available on every install.
     */
    static void startTlsListener(Context context) {
        if (context == null) return;
        try {
            File store = new File(context.getFilesDir(), "deekseep_local_api_server.p12");
            if (!store.isFile() || store.length() == 0L) {
                log("TLS skipped: keystore missing");
                return;
            }
            char[] password = "deekseep".toCharArray();
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            java.io.FileInputStream in = new java.io.FileInputStream(store);
            try {
                ks.load(in, password);
            } finally {
                closeQuietly(in);
            }
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory
                    .getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, new java.security.SecureRandom());

            int base = port > 0 ? port : 8765;
            javax.net.ssl.SSLServerSocket tls = null;
            Throwable last = null;
            for (int candidate = base + 1; candidate <= base + 9; candidate++) {
                try {
                    javax.net.ssl.SSLServerSocket created =
                            (javax.net.ssl.SSLServerSocket) ssl.getServerSocketFactory()
                                    .createServerSocket();
                    created.setReuseAddress(true);
                    created.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), candidate));
                    created.setSoTimeout(0);
                    created.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                    tls = created;
                    break;
                } catch (Throwable t) {
                    last = t;
                }
            }
            if (tls == null) {
                lastError = "TLS bind failed: " + describe(last);
                log(lastError);
                return;
            }
            tlsServerSocket = tls;
            tlsPort = tls.getLocalPort();
            tlsRunning = true;
            final javax.net.ssl.SSLServerSocket listener = tls;
            tlsAcceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(listener, "https");
                }
            }, "Deekseep-API-TlsAccept");
            tlsAcceptThread.setDaemon(true);
            tlsAcceptThread.start();
            log("TLS listening on 0.0.0.0:" + tlsPort);
        } catch (Throwable t) {
            tlsRunning = false;
            tlsPort = 0;
            lastError = "TLS setup failed: " + describe(t);
            log(lastError);
        }
    }

    static String tlsBaseUrl() {
        if (!tlsRunning || tlsPort <= 0) return "";
        String host = localAddress();
        if (host.length() == 0) host = "127.0.0.1";
        return "https://" + host + ":" + tlsPort + protocolPrefix();
    }

    static ServerSocket openServerSocket() throws Exception {
        int preferred = appContext == null ? 8765
                : appContext.getSharedPreferences(PREFS, 0).getInt(KEY_PORT, 8765);
        Throwable last = null;
        for (int candidate = preferred; candidate <= preferred + 8; candidate++) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), candidate));
                socket.setSoTimeout(0);
                if (appContext != null) {
                    appContext.getSharedPreferences(PREFS, 0).edit()
                            .putInt(KEY_PORT, candidate).apply();
                }
                return socket;
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("no local API port available", last);
    }

    /** Accept loop: one daemon thread per listener, dispatching sockets to the worker pool. */
    static void acceptLoop(ServerSocket socket, String transport) {
        while (running && socket != null && !socket.isClosed()) {
            Socket client = null;
            try {
                client = socket.accept();
                client.setSoTimeout(120000);
                client.setTcpNoDelay(true);
                final Socket accepted = client;
                ThreadPoolExecutor pool = workersPool();
                try {
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            handleClient(accepted);
                        }
                    });
                } catch (Throwable rejected) {
                    ERRORS.incrementAndGet();
                    log("gateway_busy Local API worker queue is full");
                    try {
                        java.io.OutputStream out = accepted.getOutputStream();
                        writeJsonError(out, 503, "gateway_busy",
                                "Local API worker queue is full");
                    } catch (Throwable ignored) {}
                    closeQuietly(accepted);
                }
            } catch (Throwable t) {
                closeQuietly(client);
                if (running) {
                    ERRORS.incrementAndGet();
                    lastError = describe(t);
                    log("ACCEPT_ERROR transport=" + transport + " error=" + lastError);
                    sleepQuietly(50L);
                }
            }
        }
    }

    static synchronized ThreadPoolExecutor workersPool() {
        ThreadPoolExecutor pool = workers;
        if (pool == null || pool.isShutdown()) {
            pool = new ThreadPoolExecutor(4, 32, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(256),
                    new java.util.concurrent.ThreadFactory() {
                        private final java.util.concurrent.atomic.AtomicInteger seq =
                                new java.util.concurrent.atomic.AtomicInteger(1);

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "Deekseep-API-Worker-" + seq.getAndIncrement());
                            t.setDaemon(true);
                            return t;
                        }
                    });
            pool.allowCoreThreadTimeOut(true);
            workers = pool;
        }
        return pool;
    }

    /** Per-connection entry: read request line + headers, route, then write a response. */
    static void handleClient(Socket socket) {
        if (socket == null) return;
        REQUESTS.incrementAndGet();
        LAST_REQUEST_AT.set(now());
        CURRENT_SOCKET.set(socket);
        long started = now();
        String route = "unknown";
        String clientId = null;
        try {
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(
                    socket.getInputStream(), 8192);
            java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(
                    socket.getOutputStream(), 8192);

            String headerText = readHeaderBlock(in, HEADER_LIMIT);
            if (headerText == null || headerText.length() == 0) {
                writeJsonError(out, 400, "bad_request", "Empty HTTP request");
                return;
            }
            String[] lines = headerText.split("\\r?\\n");
            if (lines.length == 0) {
                writeJsonError(out, 400, "bad_request", "Missing request line");
                return;
            }
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) {
                writeJsonError(out, 400, "bad_request", "Malformed request line");
                return;
            }
            String method = requestLine[0].toUpperCase(Locale.US);
            String target = requestLine[1];
            int queryAt = target.indexOf('?');
            String path = queryAt < 0 ? target : target.substring(0, queryAt);
            route = method + " " + path;

            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line == null || line.length() == 0) continue;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                headers.put(name, value);
            }

            clientId = headers.get("x-deekseep-client-id");
            if (clientId == null) {
                clientId = headers.get("x-client-session-id");
            }
            if (clientId != null && clientId.length() > 0) {
                CLIENTS.put(clientId, now());
                CURRENT_CLIENT_ID.set(clientId);
            }

            String expect = headers.get("expect");
            if (expect != null && expect.toLowerCase(Locale.US).contains("100-continue")) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            byte[] body = readBody(in, headers, BODY_LIMIT);

            if ("OPTIONS".equals(method)) {
                writeEmpty(out, 204, headers);
            } else if ("GET".equals(method) && ("/health".equals(path) || "/healthz".equals(path))) {
                JSONObject health = new JSONObject();
                health.put("ok", running);
                health.put("protocol", currentProtocol());
                health.put("port", port);
                health.put("requests", REQUESTS.get());
                health.put("errors", ERRORS.get());
                writeJson(out, 200, health, headers);
            } else if ("GET".equals(method) && ("/v1/models".equals(path) || "/models".equals(path))) {
                if (!checkAuth(headers)) {
                    writeJsonError(out, 401, "authentication_error", "invalid_api_key");
                } else {
                    writeJson(out, 200, modelsPayload(), headers);
                }
            } else if ("GET".equals(method) && ("/".equals(path) || "/status".equals(path))) {
                // 1.7.5 direct_root hint endpoint: clients probing the configured root learn
                // which base URL the gateway expects them to use.
                if (!checkAuth(headers)) {
                    writeJsonError(out, 401, "authentication_error", "invalid_api_key");
                } else {
                    writeJson(out, 200, statsPayload(), headers);
                }
            } else if (path.startsWith("/v1/") || path.startsWith("/")) {
                if (!checkAuth(headers)) {
                    writeJsonError(out, 401, "authentication_error", "invalid_api_key");
                } else {
                    dispatchRoute(method, path, headers, body, out);
                }
            } else {
                writeJsonError(out, 404, "not_found_error", "Unknown route: " + path);
            }
            try {
                out.flush();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            lastError = describe(t);
            log("REQUEST_FAILED route=" + route + " error=" + lastError);
            try {
                writeJsonError(socket.getOutputStream(), 500, "server_error", lastError);
            } catch (Throwable ignored) {}
        } finally {
            if (clientId != null) {
                CLIENTS.remove(clientId);
                CLIENT_GONE.remove(clientId);
            }
            CURRENT_CLIENT_ID.remove();
            CURRENT_SOCKET.remove();
            closeQuietly(socket);
            log("REQUEST route=" + route + " ms=" + (now() - started));
        }
    }

    static JSONObject modelsPayload() {
        JSONObject root = new JSONObject();
        try {
            JSONArray data = new JSONArray();
            data.put(modelEntry(DEFAULT_MODEL, "chat"));
            data.put(modelEntry(REASONER_MODEL, "reasoning"));
            data.put(modelEntry(EXPERT_MODEL, "expert"));
            root.put("object", "list");
            root.put("data", data);
        } catch (Throwable ignored) {}
        return root;
    }

    static JSONObject modelEntry(String id, String ownedBy) {
        JSONObject model = new JSONObject();
        try {
            model.put("id", id);
            model.put("object", "model");
            model.put("created", 1700000000L);
            model.put("owned_by", ownedBy);
        } catch (Throwable ignored) {}
        return model;
    }

    /** Public tunnels were dropped; the gateway only listens on the local network. */
    static String directRoot() {
        return "";
    }

    /** Base URL clients should use. Without a tunnel this is the LAN address. */
    static String publicBaseUrl() {
        return baseUrl();
    }

    /** Routes a request to the OpenAI, Anthropic or Responses handler (block 3). */
    static void dispatchRoute(String method, String path, java.util.Map<String, String> headers,
                              byte[] body, java.io.OutputStream out) throws Exception {
        if ("POST".equals(method)) {
            if ("/v1/chat/completions".equals(path) || "/chat/completions".equals(path)) {
                if (!PROTOCOL_OPENAI.equals(currentProtocol())) {
                    writeJsonError(out, 404, "not_found_error",
                            "This route is disabled because the API service is currently set to "
                                    + protocolLabel() + " format");
                    return;
                }
                handleChatCompletions(body, out);
                return;
            }
            if ("/v1/responses".equals(path) || "/responses".equals(path)) {
                if (!PROTOCOL_OPENAI.equals(currentProtocol())) {
                    writeJsonError(out, 404, "not_found_error",
                            "This route is disabled because the API service is currently set to "
                                    + protocolLabel() + " format");
                    return;
                }
                handleResponses(body, out);
                return;
            }
            if ("/v1/messages".equals(path) || "/messages".equals(path)) {
                if (!PROTOCOL_ANTHROPIC.equals(currentProtocol())) {
                    writeJsonError(out, 404, "not_found_error",
                            "This route is disabled because the API service is currently set to "
                                    + protocolLabel() + " format");
                    return;
                }
                handleMessages(body, out);
                return;
            }
            if ("/v1/messages/count_tokens".equals(path)
                    || "/messages/count_tokens".equals(path)) {
                if (!PROTOCOL_ANTHROPIC.equals(currentProtocol())) {
                    writeJsonError(out, 404, "not_found_error",
                            "This route is disabled because the API service is currently set to "
                                    + protocolLabel() + " format");
                    return;
                }
                handleCountTokens(body, out);
                return;
            }
        }
        writeJsonError(out, 404, "not_found_error", "Unknown route: " + path);
    }

    // ------------------------------------------- route: chat/completions (3a)

    static JSONObject parseBody(byte[] body) {
        if (body == null || body.length == 0) return new JSONObject();
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            throw new IllegalArgumentException("invalid_json");
        }
    }

    static void handleChatCompletions(byte[] body, java.io.OutputStream out) throws Exception {
        JSONObject request = parseBody(body);
        String model = request.optString("model", DEFAULT_MODEL);
        if (model == null || model.trim().length() == 0) {
            writeJsonError(out, 400, "invalid_request_error", "model is required");
            return;
        }
        boolean stream = request.optBoolean("stream", false);
        String completionId = "chatcmpl-" + requestId();
        long created = now() / 1000L;

        if (!HostCompat.hostReady()) {
            writeJsonError(out, 503, "server_error", "DeepSeek backend is not ready");
            return;
        }

        List<ChatTurn> turns = OpenAiToolBridge.parseOpenAiMessages(request.optJSONArray("messages"));
        String prompt = joinTurns(turns);
        if (prompt.length() == 0) {
            writeJsonError(out, 400, "invalid_request_error", "messages is required");
            return;
        }
        String reply;
        try {
            reply = HostCompat.complete(model, prompt, TOOL_SCHEMA_HINT);
        } catch (UnsupportedOperationException e) {
            writeJsonError(out, 503, "server_error",
                    "host_backend_unavailable: " + e.getMessage());
            return;
        } catch (Throwable e) {
            writeJsonError(out, 502, "upstream_error", "DeepSeek call failed: " + describe(e));
            return;
        }
        if (reply == null || reply.length() == 0) {
            writeJsonError(out, 502, "upstream_error", "DeepSeek returned an empty response");
            return;
        }
        long inTokens = estimateTokens(prompt);
        long outTokens = estimateTokens(reply);
        TOKENS_IN.addAndGet(inTokens);
        TOKENS_OUT.addAndGet(outTokens);

        java.util.List<ToolCall> calls = OpenAiToolBridge.extractToolCalls(reply);
        String visible = OpenAiToolBridge.stripToolCalls(reply);

        if (stream) {
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream; charset=utf-8\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            writeChatChunk(out, completionId, created, model, "assistant", "", null);
            for (String piece : splitForStreaming(visible)) {
                if (clientGone()) {
                    cancelRequest("POST /v1/chat/completions", "client disconnected");
                    return;
                }
                writeChatChunk(out, completionId, created, model, null, piece, null);
            }
            if (!calls.isEmpty()) {
                JSONArray toolCalls = OpenAiToolBridge.encodeOpenAiToolCalls(calls);
                writeChatChunk(out, completionId, created, model, null, "", toolCalls);
            }
            writeChatChunk(out, completionId, created, model, null, "", null);
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", inTokens);
            usage.put("completion_tokens", outTokens);
            usage.put("total_tokens", inTokens + outTokens);
            JSONObject finalChunk = new JSONObject();
            finalChunk.put("id", completionId);
            finalChunk.put("object", "chat.completion.chunk");
            finalChunk.put("created", created);
            finalChunk.put("model", model);
            finalChunk.put("choices", new JSONArray());
            finalChunk.put("usage", usage);
            writeEvent(out, finalChunk.toString());
            writeEvent(out, "[DONE]");
            return;
        }

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", visible);
        if (!calls.isEmpty()) {
            message.put("tool_calls", OpenAiToolBridge.encodeOpenAiToolCalls(calls));
        }
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", calls.isEmpty() ? "stop" : "tool_calls");
        JSONArray choices = new JSONArray();
        choices.put(choice);

        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", inTokens);
        usage.put("completion_tokens", outTokens);
        usage.put("total_tokens", inTokens + outTokens);

        JSONObject root = new JSONObject();
        root.put("id", completionId);
        root.put("object", "chat.completion");
        root.put("created", created);
        root.put("model", model);
        root.put("choices", choices);
        root.put("usage", usage);
        writeJson(out, 200, root, null);
    }

    static void writeChatChunk(java.io.OutputStream out, String id, long created, String model,
                               String role, String content, JSONArray toolCalls) throws Exception {
        JSONObject delta = new JSONObject();
        if (role != null) delta.put("role", role);
        if (content != null && content.length() > 0) delta.put("content", content);
        if (toolCalls != null && toolCalls.length() > 0) delta.put("tool_calls", toolCalls);
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", JSONObject.NULL);
        JSONArray choices = new JSONArray();
        choices.put(choice);
        JSONObject chunk = new JSONObject();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.put("choices", choices);
        writeEvent(out, chunk.toString());
    }

    static void writeEvent(java.io.OutputStream out, String data) throws Exception {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // --------------------------------------------- route: responses (3b stub)

    static void handleResponses(byte[] body, java.io.OutputStream out) throws Exception {
        JSONObject request = parseBody(body);
        String model = request.optString("model", DEFAULT_MODEL);
        if (model == null || model.trim().length() == 0) {
            writeJsonError(out, 400, "invalid_request_error", "model is required");
            return;
        }
        String prompt = OpenAiToolBridge.parseResponsesInput(request);
        if (prompt.length() == 0) {
            writeJsonError(out, 400, "invalid_request_error", "input is required");
            return;
        }
        boolean stream = request.optBoolean("stream", false);
        String responseId = "resp_" + requestId();
        String reply;
        try {
            reply = HostCompat.complete(model, prompt, TOOL_SCHEMA_HINT);
        } catch (UnsupportedOperationException e) {
            writeJsonError(out, 503, "server_error",
                    "host_backend_unavailable: " + e.getMessage());
            return;
        } catch (Throwable e) {
            writeJsonError(out, 502, "upstream_error", "DeepSeek call failed: " + describe(e));
            return;
        }
        if (reply == null || reply.length() == 0) {
            writeJsonError(out, 502, "upstream_error", "DeepSeek returned an empty response");
            return;
        }
        long inTokens = estimateTokens(prompt);
        long outTokens = estimateTokens(reply);
        TOKENS_IN.addAndGet(inTokens);
        TOKENS_OUT.addAndGet(outTokens);
        String visible = OpenAiToolBridge.stripToolCalls(reply);

        if (stream) {
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream; charset=utf-8\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            writeNamedEvent(out, "response.created", createdEvent(responseId, model, "in_progress"));
            int index = 0;
            for (String piece : splitForStreaming(visible)) {
                if (clientGone()) {
                    cancelRequest("POST /v1/responses", "client disconnected");
                    return;
                }
                writeNamedEvent(out, "response.output_text.delta",
                        deltaEvent(responseId, index++, piece));
            }
            writeNamedEvent(out, "response.completed", createdEvent(responseId, model, "completed"));
            return;
        }
        JSONObject root = createdEvent(responseId, model, "completed");
        JSONArray output = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("type", "message");
        item.put("id", "msg_" + requestId());
        item.put("role", "assistant");
        JSONArray contentArray = new JSONArray();
        JSONObject textItem = new JSONObject();
        textItem.put("type", "output_text");
        textItem.put("text", visible);
        textItem.put("annotations", new JSONArray());
        contentArray.put(textItem);
        item.put("content", contentArray);
        output.put(item);
        root.put("output", output);
        JSONObject usage = new JSONObject();
        usage.put("input_tokens", inTokens);
        usage.put("output_tokens", outTokens);
        usage.put("total_tokens", inTokens + outTokens);
        root.put("usage", usage);
        writeJson(out, 200, root, null);
    }

    static JSONObject createdEvent(String id, String model, String status) {
        JSONObject root = new JSONObject();
        try {
            root.put("id", id);
            root.put("object", "response");
            root.put("created_at", now() / 1000L);
            root.put("status", status);
            root.put("model", model);
            root.put("output", new JSONArray());
        } catch (Throwable ignored) {}
        return root;
    }

    static JSONObject deltaEvent(String id, int index, String text) {
        JSONObject root = new JSONObject();
        try {
            root.put("id", id);
            root.put("type", "response.output_text.delta");
            root.put("output_index", index);
            root.put("delta", text);
        } catch (Throwable ignored) {}
        return root;
    }

    static void writeNamedEvent(java.io.OutputStream out, String name, JSONObject payload)
            throws Exception {
        out.write(("event: " + name + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(("data: " + payload.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // --------------------------------------------- route: messages (3c stub)

    static void handleMessages(byte[] body, java.io.OutputStream out) throws Exception {
        JSONObject request = parseBody(body);
        String model = request.optString("model", DEFAULT_MODEL);
        if (request.optInt("max_tokens", -1) <= 0) {
            writeJsonError(out, 400, "invalid_request_error", "max_tokens is required");
            return;
        }
        String prompt = AnthropicAdapter.buildPrompt(request);
        boolean stream = request.optBoolean("stream", false);
        String messageId = "msg_" + requestId();
        String reply;
        try {
            reply = HostCompat.complete(model, prompt, TOOL_SCHEMA_HINT);
        } catch (UnsupportedOperationException e) {
            writeJsonError(out, 503, "server_error",
                    "host_backend_unavailable: " + e.getMessage());
            return;
        } catch (Throwable e) {
            writeJsonError(out, 502, "server_error", "DeepSeek call failed: " + describe(e));
            return;
        }
        if (reply == null || reply.length() == 0) {
            writeJsonError(out, 502, "server_error", "DeepSeek returned an empty response");
            return;
        }
        long inTokens = estimateTokens(prompt);
        long outTokens = estimateTokens(reply);
        TOKENS_IN.addAndGet(inTokens);
        TOKENS_OUT.addAndGet(outTokens);
        String visible = OpenAiToolBridge.stripToolCalls(reply);
        // 1.7.5: surface model-emitted local tool calls as Anthropic tool_use blocks so the
        // client can execute them and post the result back via messages[].content[].tool_result.
        List<ToolCall> calls = OpenAiToolBridge.extractToolCalls(reply);
        boolean hasToolUse = calls != null && !calls.isEmpty();
        String stopReason = hasToolUse ? "tool_use" : "end_turn";

        if (stream) {
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream; charset=utf-8\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            writeNamedEvent(out, "message_start", startEvent(messageId, model, inTokens));
            int blockIndex = 0;
            if (visible.length() > 0) {
                writeNamedEvent(out, "content_block_start", blockStartEvent(blockIndex));
                int index = 0;
                for (String piece : splitForStreaming(visible)) {
                    if (clientGone()) {
                        cancelRequest("POST /v1/messages", "client disconnected");
                        return;
                    }
                    writeNamedEvent(out, "content_block_delta",
                            textDeltaEvent(index++, piece));
                }
                writeNamedEvent(out, "content_block_stop", blockStopEvent(blockIndex));
                blockIndex++;
            }
            for (ToolCall call : calls) {
                if (clientGone()) {
                    cancelRequest("POST /v1/messages", "client disconnected");
                    return;
                }
                writeNamedEvent(out, "content_block_start",
                        toolUseStartEvent(blockIndex, call));
                writeNamedEvent(out, "content_block_delta",
                        toolUseInputDeltaEvent(blockIndex, call));
                writeNamedEvent(out, "content_block_stop", blockStopEvent(blockIndex));
                blockIndex++;
            }
            writeNamedEvent(out, "message_delta", messageDeltaEvent(outTokens, stopReason));
            writeNamedEvent(out, "message_stop", new JSONObject());
            return;
        }
        JSONObject root = new JSONObject();
        root.put("id", messageId);
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", model);
        JSONArray content = new JSONArray();
        if (visible.length() > 0) {
            JSONObject block = new JSONObject();
            block.put("type", "text");
            block.put("text", visible);
            content.put(block);
        }
        for (ToolCall call : calls) {
            JSONObject toolUse = new JSONObject();
            toolUse.put("type", "tool_use");
            toolUse.put("id", anthropicToolId(call.id));
            toolUse.put("name", call.name);
            toolUse.put("input", call.arguments);
            content.put(toolUse);
        }
        root.put("content", content);
        root.put("stop_reason", stopReason);
        root.put("stop_sequence", JSONObject.NULL);
        JSONObject usage = new JSONObject();
        usage.put("input_tokens", inTokens);
        usage.put("output_tokens", outTokens);
        root.put("usage", usage);
        writeJson(out, 200, root, null);
    }

    /** Anthropic requires tool ids to start with {@code toolu_}. */
    static String anthropicToolId(String id) {
        if (id == null || id.length() == 0) return "toolu_" + requestId();
        return id.startsWith("toolu_") ? id : "toolu_" + id;
    }

    static JSONObject toolUseStartEvent(int index, ToolCall call) {
        JSONObject root = new JSONObject();
        try {
            JSONObject block = new JSONObject();
            block.put("type", "tool_use");
            block.put("id", anthropicToolId(call.id));
            block.put("name", call.name);
            block.put("input", new JSONObject());
            root.put("type", "content_block_start");
            root.put("index", index);
            root.put("content_block", block);
        } catch (Throwable ignored) {}
        return root;
    }

    static JSONObject toolUseInputDeltaEvent(int index, ToolCall call) {
        JSONObject root = new JSONObject();
        try {
            JSONObject delta = new JSONObject();
            delta.put("type", "input_json_delta");
            delta.put("partial_json", call.arguments.toString());
            root.put("type", "content_block_delta");
            root.put("index", index);
            root.put("delta", delta);
        } catch (Throwable ignored) {}
        return root;
    }

    static JSONObject startEvent(String id, String model, long inTokens) {
        JSONObject message = new JSONObject();
        try {
            message.put("id", id);
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("model", model);
            message.put("content", new JSONArray());
            message.put("stop_reason", JSONObject.NULL);
            message.put("usage", new JSONObject().put("input_tokens", inTokens));
        } catch (Throwable ignored) {}
        JSONObject root = new JSONObject();
        try {
            root.put("type", "message_start");
            root.put("message", message);
        } catch (Throwable ignored) {}
        return root;
    }

    static JSONObject blockStartEvent(int index) {
        JSONObject root = new JSONObject();
        try {
            JSONObject block = new JSONObject();
            block.put("type", "text");
            block.put("text", "");
            root.put("type", "content_block_start");
            root.put("index", index);
            root.put("content_block", block);
        } catch (Throwable ignored) {}
        return root;
    }

    static JSONObject textDeltaEvent(int index, String text) {
        JSONObject root = new JSONObject();
        try {
            JSONObject delta = new JSONObject();
            delta.put("type", "text_delta");
            delta.put("text", text);
            root.put("type", "content_block_delta");
            root.put("index", index);
            root.put("delta", delta);
        } catch (Throwable ignored) {}
        return root;
    }

    static JSONObject blockStopEvent(int index) {
        JSONObject root = new JSONObject();
        try {
            root.put("type", "content_block_stop");
            root.put("index", index);
        } catch (Throwable ignored) {}
        return root;
    }

    static JSONObject messageDeltaEvent(long outTokens) {
        return messageDeltaEvent(outTokens, "end_turn");
    }

    static JSONObject messageDeltaEvent(long outTokens, String stopReason) {
        JSONObject root = new JSONObject();
        try {
            JSONObject delta = new JSONObject();
            delta.put("stop_reason", stopReason == null ? "end_turn" : stopReason);
            delta.put("stop_sequence", JSONObject.NULL);
            JSONObject usage = new JSONObject();
            usage.put("output_tokens", outTokens);
            root.put("type", "message_delta");
            root.put("delta", delta);
            root.put("usage", usage);
        } catch (Throwable ignored) {}
        return root;
    }

    static void handleCountTokens(byte[] body, java.io.OutputStream out) throws Exception {
        JSONObject request = parseBody(body);
        String prompt = AnthropicAdapter.buildPrompt(request);
        JSONObject root = new JSONObject();
        root.put("input_tokens", estimateTokens(prompt));
        writeJson(out, 200, root, null);
    }

    // ----------------------------------------------------------- helpers (3d)

    static final String TOOL_SCHEMA_HINT =
            "[[DEEKSEEP_LOCAL_TOOLS_V1]] Available local tools are described by the host. "
                    + "Only emit a tool block when a tool is actually needed.";

    static String joinTurns(List<ChatTurn> turns) {
        if (turns == null || turns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ChatTurn turn : turns) {
            if (turn == null || turn.content == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(turn.role).append(": ").append(turn.content);
        }
        return sb.toString();
    }

    static long estimateTokens(String text) {
        if (text == null || text.length() == 0) return 0L;
        long cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) cjk++;
        }
        long ascii = text.length() - cjk;
        return Math.max(1L, cjk + Math.max(1L, ascii / 4L));
    }

    static List<String> splitForStreaming(String text) {
        List<String> pieces = new ArrayList<>();
        if (text == null || text.length() == 0) return pieces;
        int step = 48;
        for (int i = 0; i < text.length(); i += step) {
            pieces.add(text.substring(i, Math.min(text.length(), i + step)));
        }
        return pieces;
    }

    /** Minimal chat turn record shared with the tool bridges. */
    static final class ChatTurn {
        final String role;
        final String content;

        ChatTurn(String role, String content) {
            this.role = role == null ? "user" : role;
            this.content = content == null ? "" : content;
        }
    }

    /** Minimal tool call record shared with the tool bridges. */
    static final class ToolCall {
        final String id;
        final String name;
        final JSONObject arguments;

        ToolCall(String id, String name, JSONObject arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments == null ? new JSONObject() : arguments;
        }
    }

    // ---------------------------------------------------- protocol helpers (4)

    static String normalizeResponseModel(String model) {
        if (model == null) return DEFAULT_MODEL;
        String m = model.trim().toLowerCase(Locale.US);
        if (m.contains("reasoner") || m.contains("reasoning") || m.contains("deep_think")) {
            return REASONER_MODEL;
        }
        if (m.contains("expert")) return EXPERT_MODEL;
        return DEFAULT_MODEL;
    }

    /** Strips the trailing AI-disclaimer line that DeepSeek appends to some replies. */
    static boolean isDisclaimer(String text) {
        return text != null && text.trim().equals(DISCLAIMER);
    }

    static String contentToText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        StringBuilder sb = new StringBuilder();
        if (content instanceof JSONArray) {
            JSONArray array = (JSONArray) content;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof JSONObject) {
                    JSONObject block = (JSONObject) item;
                    String type = block.optString("type", "text");
                    if ("text".equals(type) || "input_text".equals(type)
                            || "output_text".equals(type) || "summary_text".equals(type)) {
                        sb.append(block.optString("text", ""));
                    } else if ("image".equals(type) || "document".equals(type)) {
                        sb.append("[Image attachment]");
                    }
                } else if (item != null) {
                    sb.append(item);
                }
                if (sb.length() > 0 && i + 1 < array.length()) sb.append('\n');
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    static JSONObject errorPayload(String type, String message, String code) {
        JSONObject error = new JSONObject();
        try {
            error.put("type", type == null ? "server_error" : type);
            error.put("message", message == null ? "Unknown error" : message);
            if (code != null) error.put("code", code);
        } catch (Throwable ignored) {}
        JSONObject root = new JSONObject();
        try {
            root.put("error", error);
        } catch (Throwable ignored) {}
        return root;
    }

    static String httpErrorType(int status) {
        switch (status) {
            case 400: return "invalid_request_error";
            case 401: return "authentication_error";
            case 403: return "permission_error";
            case 404: return "not_found_error";
            case 413: return "request_too_large";
            case 429: return "rate_limit_error";
            default: return "server_error";
        }
    }

    static int positiveInt(JSONObject body, String key, int fallback) {
        if (body == null || !body.has(key) || body.isNull(key)) return fallback;
        int value = body.optInt(key, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException("invalid_" + key + " must be positive");
        }
        return value;
    }

    static String modelOrThrow(JSONObject body) {
        String model = body == null ? "" : body.optString("model", "").trim();
        if (model.length() == 0) {
            throw new IllegalArgumentException("missing_model model is required");
        }
        return model;
    }

    // ------------------------------------------------ request dedupe (4b)

    /** Tracks recently served tool-call ids so a retrying client never sees duplicates. */
    static final java.util.Set<String> RECENT_TOOL_CALLS =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    static java.util.List<ToolCall> dedupeToolCalls(java.util.List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return calls;
        java.util.List<ToolCall> fresh = new ArrayList<>();
        for (ToolCall call : calls) {
            if (call == null) continue;
            if (call.id != null && call.id.length() > 0 && !RECENT_TOOL_CALLS.add(call.id)) {
                log("TOOL_DUPLICATE_SUPPRESSED request_id=" + call.id);
                continue;
            }
            fresh.add(call);
        }
        if (RECENT_TOOL_CALLS.size() > 512) {
            java.util.Iterator<String> it = RECENT_TOOL_CALLS.iterator();
            int drop = RECENT_TOOL_CALLS.size() - 256;
            while (it.hasNext() && drop-- > 0) {
                it.next();
                it.remove();
            }
        }
        return fresh;
    }

    // -------------------------------------------------- stats (4c)

    static void pruneClients() {
        long cutoff = now() - 600000L;
        for (java.util.Map.Entry<String, Long> entry : CLIENTS.entrySet()) {
            Long seen = entry.getValue();
            if (seen == null || seen < cutoff) CLIENTS.remove(entry.getKey());
        }
    }

    // ------------------------------------------------ Anthropic adapter (5)

    /** Converts Anthropic /v1/messages payloads into a single prompt string. */
    static final class AnthropicAdapter {
        private AnthropicAdapter() {}

        static String buildPrompt(JSONObject request) {
            if (request == null) return "";
            StringBuilder sb = new StringBuilder();
            Object system = request.opt("system");
            String systemText = contentToText(system);
            if (systemText.length() > 0) sb.append("system: ").append(systemText).append('\n');
            JSONArray messages = request.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message == null) continue;
                    String role = message.optString("role", "user");
                    String text = renderAnthropicContent(message.opt("content"));
                    if (text.length() == 0) continue;
                    sb.append(role).append(": ").append(text).append('\n');
                }
            }
            JSONArray tools = request.optJSONArray("tools");
            if (tools != null && tools.length() > 0) {
                sb.append(TOOL_SCHEMA_HINT).append('\n');
            }
            return sb.toString().trim();
        }
    }

    // ------------------------------------------- anthropic tool_result (5a)

    /**
     * Renders one Anthropic {@code content} value, preserving {@code tool_use} and
     * {@code tool_result} blocks so a multi-turn tool loop survives the text flattening.
     *
     * <p>Rebuilt from the dex {@code u0()} / {@code v0()} helpers, which wrote tool results
     * back into the conversation before the next completion request.</p>
     */
    static String renderAnthropicContent(Object content) {
        if (content == null) return "";
        if (content instanceof String) return ((String) content).trim();
        if (!(content instanceof JSONArray)) return contentToText(content);
        JSONArray array = (JSONArray) content;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof JSONObject)) {
                String plain = item == null ? "" : String.valueOf(item).trim();
                if (plain.length() > 0) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(plain);
                }
                continue;
            }
            JSONObject block = (JSONObject) item;
            String type = block.optString("type", "text");
            String rendered;
            if ("tool_result".equals(type)) {
                rendered = renderToolResult(block);
            } else if ("tool_use".equals(type)) {
                rendered = renderToolUse(block);
            } else {
                rendered = contentToText(block.opt("text"));
            }
            if (rendered == null || rendered.length() == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(rendered);
        }
        return sb.toString().trim();
    }

    /** Serializes a {@code tool_result} block in the syntax the model was trained to emit. */
    static String renderToolResult(JSONObject block) {
        if (block == null) return "";
        String toolUseId = block.optString("tool_use_id", "");
        boolean isError = block.optBoolean("is_error", false);
        String body = contentToText(block.opt("content")).trim();
        if (body.length() == 0) body = isError ? "tool execution failed" : "(no output)";
        JSONObject call = new JSONObject();
        try {
            call.put("id", stripTooluPrefix(toolUseId));
            call.put("name", "");
            call.put("status", isError ? "error" : "ok");
            call.put("result", body);
        } catch (Throwable ignored) {}
        return "[[DEEKSEEP_TOOL_CALL]]\n" + call.toString() + "\n[[/DEEKSEEP_TOOL_CALL]]";
    }

    /** Re-emits a previous {@code tool_use} block so the model sees its own request. */
    static String renderToolUse(JSONObject block) {
        if (block == null) return "";
        JSONObject call = new JSONObject();
        try {
            call.put("id", stripTooluPrefix(block.optString("id", "")));
            call.put("name", block.optString("name", ""));
            JSONObject input = block.optJSONObject("input");
            call.put("arguments", input == null ? new JSONObject() : input);
        } catch (Throwable ignored) {}
        return "[[DEEKSEEP_TOOL_CALL]]\n" + call.toString() + "\n[[/DEEKSEEP_TOOL_CALL]]";
    }

    static String stripTooluPrefix(String id) {
        if (id == null) return "";
        return id.startsWith("toolu_") ? id.substring(6) : id;
    }

    // ------------------------------------------------ message parsing (5b)

    /** Maps an OpenAI-style model id onto one of the three DeepSeek backends. */
    static String resolveBackend(String requested) {
        return normalizeResponseModel(requested);
    }

    /** Returns true when the reply ends like a finished sentence and needs no auto-continue. */
    static boolean looksComplete(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.length() == 0) return false;
        if (trimmed.contains("```")) return true;
        if (trimmed.matches("(?s).*[。！？.!?；;）)】\\]}`'\\\"]\\s*$")) return true;
        String lower = trimmed.toLowerCase(Locale.US);
        return !(lower.endsWith("接着需要") || lower.endsWith("下一步需要")
                || lower.endsWith("i will ") || lower.endsWith("i'll ")
                || lower.endsWith("let me "));
    }

    static JSONObject statsPayload() {
        pruneClients();
        JSONObject root = new JSONObject();
        try {
            root.put("running", running);
            root.put("endpoint", baseUrl());
            root.put("protocol", currentProtocol());
            root.put("api_key", fingerprint(apiKey == null ? "" : apiKey));
            root.put("backend_ready", HostCompat.hostReady());
            root.put("requests", REQUESTS.get());
            root.put("errors", ERRORS.get());
            root.put("cancelled", CANCELLED.get());
            root.put("tokens_in", TOKENS_IN.get());
            root.put("tokens_out", TOKENS_OUT.get());
            root.put("clients", CLIENTS.size());
        } catch (Throwable ignored) {}
        return root;
    }

    // ------------------------------------------------------- HTTP primitives

    /** Reads up to the blank line terminating the header block; null on EOF before any byte. */
    static String readHeaderBlock(java.io.InputStream in, int limit) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(1024);
        int matched = 0;
        int b;
        boolean any = false;
        while ((b = in.read()) >= 0) {
            any = true;
            if (buffer.size() >= limit) {
                throw new IllegalArgumentException("header limit reached");
            }
            buffer.write(b);
            if (b == '\n') {
                matched++;
                if (matched >= 2) break;
            } else if (b != '\r') {
                matched = 0;
            }
        }
        if (!any) return null;
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Reads exactly content-length bytes, or an empty array when the header is absent. */
    static byte[] readBody(java.io.InputStream in, java.util.Map<String, String> headers,
                           int limit) throws Exception {
        String raw = headers.get("content-length");
        if (raw == null) return new byte[0];
        long length;
        try {
            length = Long.parseLong(raw.trim());
        } catch (Throwable t) {
            throw new IllegalArgumentException("invalid_content_length");
        }
        if (length < 0) throw new IllegalArgumentException("invalid_content_length");
        if (length > limit) {
            throw new IllegalArgumentException("request_too_large");
        }
        int size = (int) length;
        byte[] data = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = in.read(data, offset, size - offset);
            if (count < 0) throw new IllegalArgumentException("client disconnected during body");
            offset += count;
        }
        return data;
    }

    static void writeJson(java.io.OutputStream out, int status, JSONObject payload,
                          java.util.Map<String, String> requestHeaders) throws Exception {
        byte[] body = payload == null ? "{}".getBytes(StandardCharsets.UTF_8)
                : payload.toString().getBytes(StandardCharsets.UTF_8);
        writeResponse(out, status, "application/json; charset=utf-8", body, requestHeaders);
    }

    static void writeEmpty(java.io.OutputStream out, int status,
                           java.util.Map<String, String> requestHeaders) throws Exception {
        writeResponse(out, status, "text/plain; charset=utf-8", new byte[0], requestHeaders);
    }

    static void writeResponse(java.io.OutputStream out, int status, String contentType,
                              byte[] body, java.util.Map<String, String> requestHeaders)
            throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Headers: authorization, content-type, x-api-key, anthropic-version\r\n");
        head.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null && body.length > 0) out.write(body);
        out.flush();
    }

    static void writeJsonError(java.io.OutputStream out, int status, String type, String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("type", type);
            error.put("message", message == null ? "Unknown error" : message);
            error.put("code", type);
            JSONObject root = new JSONObject();
            root.put("error", error);
            writeJson(out, status, root, null);
        } catch (Throwable ignored) {}
    }

    static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "Status";
        }
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {}
    }

    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 1.7.5 client-disconnect watcher (dex {@code q()}). A single daemon thread walks every
     * socket currently owned by a worker thread once per second. A socket whose peer half has
     * closed is marked through {@link #CLIENT_GONE} so the writer aborts even mid-chunk.
     */
    static void startClientWatch() {
        if (watchThread != null && watchThread.isAlive()) return;
        watchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    pruneClients();
                    for (java.util.Map.Entry<String, Long> entry : CLIENTS.entrySet()) {
                        if (Thread.currentThread().isInterrupted()) return;
                        String id = entry.getKey();
                        Long seen = entry.getValue();
                        if (seen == null) continue;
                        if (now() - seen.longValue() > CLIENT_STALE_MS) {
                            CLIENTS.remove(id);
                            CLIENT_GONE.remove(id);
                            log("CLIENT_STALE id=" + id);
                        }
                    }
                    if (CLIENT_GONE.size() > 256) {
                        java.util.Iterator<String> it = CLIENT_GONE.keySet().iterator();
                        int drop = CLIENT_GONE.size() - 128;
                        while (it.hasNext() && drop-- > 0) {
                            it.next();
                            it.remove();
                        }
                    }
                }
            }
        }, "Deekseep-API-Client-Watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    static boolean clientGone() {
        String id = CURRENT_CLIENT_ID.get();
        if (id != null && Boolean.TRUE.equals(CLIENT_GONE.get(id))) return true;
        Socket socket = CURRENT_SOCKET.get();
        if (socket == null) return true;
        try {
            if (socket.isClosed() || socket.isInputShutdown()) {
                markGone(id);
                return true;
            }
            java.io.InputStream in = socket.getInputStream();
            if (in != null && in.available() > 0) {
                // Bytes pending after the request body means the peer either pipelined
                // another request (unsupported) or closed its half; treat both as gone.
                markGone(id);
                return true;
            }
            return false;
        } catch (Throwable t) {
            markGone(id);
            return true;
        }
    }

    /**
     * Records that the peer behind {@code id} is gone. The counter is bumped by
     * {@link #cancelRequest(String, String)} exactly once per aborted stream, so this helper
     * must stay side-effect free apart from the marker itself.
     */
    static void markGone(String id) {
        if (id != null) CLIENT_GONE.put(id, Boolean.TRUE);
    }

    static void cancelRequest(String route, String reason) {
        CANCELLED.incrementAndGet();
        log("REQUEST_CANCELLED route=" + route + " reason=" + reason);
    }
}
