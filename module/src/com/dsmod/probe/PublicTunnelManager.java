package com.dsmod.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cloudflare Tunnel (cloudflared) management.
 *
 * <p>Rebuilt from the 1.7.5 APK dex (R8-obfuscated). Original symbol names are unknown;
 * names here are chosen from observed behaviour and string constants.</p>
 */
final class PublicTunnelManager {
    static final String TAG = "DeekseepPublicTunnel";
    static final String PREFS = "deekseep_public_tunnel";
    static final String KEY_TOKEN_CIPHER = "cloudflare_token_cipher";
    static final String KEY_REQUESTED = "requested";
    static final String KEY_DOMAINS = "domains";
    static final String KEY_DIRECT_ROOT = "direct_root";
    static final String KEY_TRANSPORT = "transport";
    static final String STATE_STOPPED = "stopped";
    static final String STATE_CONNECTING = "connecting";
    static final String STATE_CONNECTED = "connected";
    static final String STATE_ERROR = "error";
    static final String STATE_WAITING = "waiting_local_api";
    static final String STATE_RETRY_WAIT = "retry_wait";
    static final int DEFAULT_PORT = 0x223D;
    static final String CLOUDFLARED_VERSION = "2026.6.0";
    static final int LOG_CAPACITY = 80;
    static final long LOG_MAX_BYTES = 0x80000L;

    /** Guard for every mutable static below. */
    static final Object LOCK = new Object();
    static final ArrayDeque<String> LOG = new ArrayDeque<>();

    static volatile Process process;
    static volatile File tokenFile;
    static volatile String state = STATE_STOPPED;
    static volatile String error = "";
    static volatile boolean requested;
    static volatile int port;
    static volatile long startedAt;
    static volatile long connectedAt;
    static volatile long retryAt;
    static volatile int attempt;
    static volatile Context appContext;

    private PublicTunnelManager() {}

    // ---------------------------------------------------------------- logging

    static String redact(String line) {
        if (line == null) return "";
        String s = line.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "").trim();
        s = s.replaceAll("eyJ[A-Za-z0-9_+=/.-]{48,}", "<redacted-token>");
        if (s.length() > 1200) s = s.substring(0, 1200).concat("…");
        return s;
    }

    static void log(Context ctx, String line) {
        String clean = redact(line);
        if (clean.length() == 0) return;
        synchronized (LOG) {
            LOG.addLast(clean);
            while (LOG.size() > LOG_CAPACITY) LOG.removeFirst();
        }
        if (ctx == null) return;
        try {
            File file = new File(ctx.getFilesDir(), "deekseep_cloudflared.log");
            if (file.isFile() && file.length() > LOG_MAX_BYTES) {
                File old = new File(ctx.getFilesDir(), "deekseep_cloudflared.log.old");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            Writer w = new FileWriter(file, true);
            try {
                w.write(System.currentTimeMillis() + " " + clean + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {}
    }

    static void log(String line) {
        log(appContext, line);
    }

    static File binary(Context ctx) {
        String dir = ctx.getApplicationInfo().nativeLibraryDir;
        if (dir == null) dir = "";
        return new File(dir, "libcloudflared.so");
    }

    // ------------------------------------------------------- process log lines

    static void onLine(Context ctx, Process proc, String raw) {
        String clean = redact(raw);
        if (clean.length() == 0) return;
        log(ctx, clean);
        String lower = clean.toLowerCase(Locale.US);
        synchronized (LOCK) {
            if (process != proc) return;
            if (lower.contains("registered tunnel connection")
                    || lower.contains("tunnel connection registered")) {
                state = STATE_CONNECTED;
                connectedAt = System.currentTimeMillis();
                error = "";
                attempt = 0;
                retryAt = 0L;
            } else if (lower.contains("hard_fail=true")
                    || lower.contains("environment has critical failures")) {
                state = STATE_CONNECTING;
                error = "当前网络无法连接 Cloudflare Tunnel 边缘：请允许出站 UDP/TCP 7844，或切换网络/传输方式";
            } else if (lower.contains("unable to establish connection with cloudflare edge")) {
                if (!STATE_CONNECTED.equals(state)) state = STATE_CONNECTING;
                if (lower.contains("tls handshake") || lower.contains("eof")) {
                    error = "连接 Cloudflare 边缘时 TLS 握手被网络中断（通常是 7844 端口被拦截）";
                } else if (lower.contains("quic")) {
                    error = "QUIC 连接失败，可在高级设置中尝试 HTTP/2";
                } else {
                    error = "暂时无法连接 Cloudflare 边缘，cloudflared 正在自动重试";
                }
            } else if (lower.contains("invalid tunnel token")
                    || lower.contains("failed to parse tunnel token")
                    || lower.contains("unauthorized")) {
                state = STATE_ERROR;
                error = "Cloudflare 拒绝了 Tunnel token，请重新复制连接器 token";
            }
        }
    }

    // ------------------------------------------------------------- AES-GCM key

    static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        String alias = "deekseep_public_tunnel_secret_v1";
        if (ks.containsAlias(alias)) {
            KeyStore.Entry e = ks.getEntry(alias, null);
            if (e instanceof KeyStore.SecretKeyEntry) {
                SecretKey k = ((KeyStore.SecretKeyEntry) e).getSecretKey();
                if (k != null) return k;
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }

    static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[1 + iv.length + ct.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    static String decrypt(String cipherText) throws Exception {
        if (cipherText == null || cipherText.length() == 0) {
            throw new IllegalArgumentException("尚未保存 token");
        }
        byte[] data = Base64.decode(cipherText, Base64.NO_WRAP);
        if (data.length == 0) throw new IllegalArgumentException("加密凭据已损坏");
        int ivLen = data[0] & 0xFF;
        if (ivLen < 12 || data.length <= ivLen + 1) {
            throw new IllegalArgumentException("加密凭据已损坏");
        }
        byte[] iv = new byte[ivLen];
        byte[] ct = new byte[data.length - 1 - ivLen];
        System.arraycopy(data, 1, iv, 0, ivLen);
        System.arraycopy(data, 1 + ivLen, ct, 0, ct.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }

    static boolean hasToken(SharedPreferences prefs) {
        String v = prefs.getString(KEY_TOKEN_CIPHER, "");
        return v != null && v.length() > 20;
    }

    // ----------------------------------------------------------- input parsing

    static String unquote(String s) {
        if (s == null) s = "";
        s = s.trim();
        while (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                s = s.substring(1, s.length() - 1).trim();
            } else {
                break;
            }
        }
        return s;
    }

    static String extractToken(String text) {
        if (text == null) throw new IllegalArgumentException("token 为空");
        String s = text.trim();
        if (s.length() == 0) throw new IllegalArgumentException("token 为空");
        s = s.replace('\r', ' ').replace('\n', ' ').trim();
        String[] parts = s.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if ("--token".equals(unquote(parts[i])) && i + 1 < parts.length) {
                return unquote(parts[i + 1]);
            }
        }
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = unquote(parts[i]);
            if (p.length() >= 80 && p.indexOf('-') != 0) return p;
        }
        return unquote(s);
    }

    static void validateToken(String token) {
        if (token == null || token.length() < 80 || token.length() > 4096) {
            throw new IllegalArgumentException("token 长度不正确");
        }
        byte[] raw;
        try {
            raw = Base64.decode(token, Base64.DEFAULT);
        } catch (Throwable t) {
            raw = Base64.decode(token, Base64.URL_SAFE);
        }
        try {
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            boolean legacy = o.optString("a", "").length() > 0
                    && o.optString("t", "").length() > 0
                    && o.optString("s", "").length() > 0;
            boolean modern = o.optString("accountTag", "").length() > 0
                    && o.optString("tunnelID", "").length() > 0
                    && o.optString("tunnelSecret", "").length() > 0;
            if (!legacy && !modern) {
                throw new IllegalArgumentException("不是 Cloudflare Tunnel connector token");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalArgumentException("不是 Cloudflare Tunnel connector token");
        }
    }

    static String normalizeDomains(String input) {
        String s = input == null ? "" : input.replace(',', '\n').replace(';', '\n');
        String[] lines = s.split("\\r?\\n");
        Set<String> out = new LinkedHashSet<>();
        for (String line : lines) {
            String d = line == null ? "" : line.trim();
            if (d.length() == 0) continue;
            if (d.startsWith("http://") || d.startsWith("https://")) {
                try {
                    d = URI.create(d).getHost();
                } catch (Throwable t) {
                    throw new IllegalArgumentException("域名格式不正确：" + line);
                }
            }
            if (d == null) throw new IllegalArgumentException("域名格式不正确：" + line);
            int slash = d.indexOf('/');
            if (slash >= 0) d = d.substring(0, slash);
            int lastColon = d.lastIndexOf(':');
            if (lastColon > 0 && d.indexOf(':') == lastColon) d = d.substring(0, lastColon);
            d = d.trim();
            if (d.startsWith("*.")) {
                throw new IllegalArgumentException("请填写可直接访问的完整域名，不要填写通配符：" + d);
            }
            String ascii;
            try {
                ascii = IDN.toASCII(d, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.US);
            } catch (Throwable t) {
                throw new IllegalArgumentException("域名格式不正确：" + d);
            }
            if (ascii.length() < 3 || ascii.length() > 253 || ascii.indexOf('.') <= 0) {
                throw new IllegalArgumentException("域名格式不正确：" + d);
            }
            for (String label : ascii.split("\\.")) {
                if (label.length() == 0 || label.length() > 63
                        || label.startsWith("-") || label.endsWith("-")
                        || !label.matches("[a-z0-9-]+")) {
                    throw new IllegalArgumentException("域名格式不正确：" + d);
                }
            }
            out.add(ascii);
            if (out.size() > 16) throw new IllegalArgumentException("最多保存 16 个域名");
        }
        StringBuilder sb = new StringBuilder();
        for (String d : out) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(d);
        }
        return sb.toString();
    }

    static String normalizeDirectRoot(String input) {
        String s = input == null ? "" : input.trim();
        if (s.length() == 0) return "";
        if (!s.contains("://")) s = "http://" + s;
        try {
            URI u = URI.create(s);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("公网直连地址只支持 http:// 或 https://");
            }
            if (u.getHost() == null || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null) {
                throw new IllegalArgumentException("公网 IP/直连地址格式不正确");
            }
            String path = u.getPath() == null ? "" : u.getPath();
            if ("/v1".equals(path) || "/v1/".equals(path)) path = "";
            if (path.length() > 0 && !"/".equals(path)) {
                throw new IllegalArgumentException("直连地址不能带自定义路径；OpenAI 的 /v1 会自动补上");
            }
            String host = u.getHost();
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            return scheme + "://" + host + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalArgumentException("公网 IP/直连地址格式不正确");
        }
    }

    static String normalizeTransport(String v) {
        if ("http2".equalsIgnoreCase(v)) return "http2";
        if ("quic".equalsIgnoreCase(v)) return "quic";
        return "auto";
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0);
    }

    // ---------------------------------------------------------- configuration

    static Bundle saveConfig(Context context, Bundle in) {
        Bundle out = new Bundle();
        Context ctx = context.getApplicationContext();
        appContext = ctx;
        try {
            String domains = normalizeDomains(in == null ? "" : in.getString(KEY_DOMAINS, ""));
            String direct = normalizeDirectRoot(
                    in == null ? "" : in.getString(KEY_DIRECT_ROOT, ""));
            String transport = normalizeTransport(
                    in == null ? "auto" : in.getString(KEY_TRANSPORT, "auto"));

            SharedPreferences prefs = prefs(ctx);
            boolean changed = !domains.equals(prefs.getString(KEY_DOMAINS, ""))
                    || !direct.equals(prefs.getString(KEY_DIRECT_ROOT, ""))
                    || !transport.equals(prefs.getString(KEY_TRANSPORT, "auto"));

            SharedPreferences.Editor editor = prefs.edit()
                    .putString(KEY_DOMAINS, domains)
                    .putString(KEY_DIRECT_ROOT, direct)
                    .putString(KEY_TRANSPORT, transport);

            String token = in == null ? "" : in.getString("token", "");
            if (token != null && token.trim().length() > 0) {
                String clean = extractToken(token);
                validateToken(clean);
                editor.putString(KEY_TOKEN_CIPHER, encrypt(clean));
                changed = true;
            }
            editor.apply();

            synchronized (LOCK) {
                attempt = 0;
                retryAt = 0L;
                error = "";
                if (changed && process != null) {
                    stop("configuration changed");
                    if (prefs.getBoolean(KEY_REQUESTED, false)) {
                        retryAt = SystemClock.elapsedRealtime() + 800L;
                        state = requested ? STATE_CONNECTING : STATE_WAITING;
                    }
                }
            }
            reconcile(ctx);
            out.putBoolean("accepted", true);
            fillStatus(ctx, out);
            return out;
        } catch (Throwable t) {
            out.putBoolean("accepted", false);
            out.putString("error", "Tunnel token 无效或无法安全保存：" + describe(t));
            fillStatus(ctx, out);
            return out;
        }
    }

    static Bundle setRequested(Context context, boolean enable) {
        Bundle out = new Bundle();
        Context ctx = context.getApplicationContext();
        appContext = ctx;
        SharedPreferences prefs = prefs(ctx);
        if (enable) {
            if (!hasToken(prefs)) {
                out.putBoolean("accepted", false);
                out.putString("error", "请先粘贴并保存 Cloudflare Tunnel token");
                fillStatus(ctx, out);
                return out;
            }
            if (prefs.getString(KEY_DOMAINS, "").trim().length() == 0) {
                out.putBoolean("accepted", false);
                out.putString("error", "请至少填写一个已绑定到该 Tunnel 的域名");
                fillStatus(ctx, out);
                return out;
            }
            if (!binary(ctx).isFile()) {
                out.putBoolean("accepted", false);
                out.putString("error", "当前安装包缺少与设备 ABI 匹配的 cloudflared");
                fillStatus(ctx, out);
                return out;
            }
        }
        prefs.edit().putBoolean(KEY_REQUESTED, enable).apply();
        synchronized (LOCK) {
            attempt = 0;
            retryAt = 0L;
            error = "";
        }
        reconcile(ctx);
        out.putBoolean("accepted", true);
        fillStatus(ctx, out);
        return out;
    }

    // ------------------------------------------------------------- lifecycle

    static void reconcile(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            if (!prefs.getBoolean(KEY_REQUESTED, false)) {
                stop("disabled");
                state = STATE_STOPPED;
                return;
            }
            if (!requested || port <= 0) {
                stop("local API unavailable");
                state = STATE_WAITING;
                return;
            }
            if (process != null) return;
            if (SystemClock.elapsedRealtime() < retryAt) {
                state = STATE_RETRY_WAIT;
                return;
            }
            start(context);
        }
    }

    static void start(Context context) {
        SharedPreferences prefs = prefs(context);
        File bin = binary(context);
        if (!bin.isFile()) {
            state = STATE_ERROR;
            error = "安装包中没有适用于当前设备架构的 cloudflared";
            return;
        }
        String token;
        try {
            token = decrypt(prefs.getString(KEY_TOKEN_CIPHER, ""));
            validateToken(token);
        } catch (Throwable t) {
            state = STATE_ERROR;
            error = "无法读取 Tunnel token：" + describe(t);
            return;
        }
        try {
            File dir = new File(context.getNoBackupFilesDir(), "deekseep_public_tunnel");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建私有凭据目录");
            }
            dir.setReadable(false, false);
            dir.setWritable(false, false);
            dir.setExecutable(false, false);
            dir.setReadable(true, true);
            dir.setWritable(true, true);
            dir.setExecutable(true, true);

            File tf = new File(dir, "cloudflare.token");
            FileOutputStream fos = new FileOutputStream(tf, false);
            try {
                fos.write(token.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Throwable ignored) {}
            } finally {
                fos.close();
            }
            tf.setReadable(false, false);
            tf.setWritable(false, false);
            tf.setExecutable(false, false);
            tf.setReadable(true, true);
            tf.setWritable(true, true);
            tokenFile = tf;

            String transport = normalizeTransport(prefs.getString(KEY_TRANSPORT, "auto"));
            List<String> cmd = new ArrayList<>();
            cmd.add(bin.getAbsolutePath());
            cmd.add("tunnel");
            cmd.add("--no-autoupdate");
            cmd.add("--grace-period");
            cmd.add("2s");
            cmd.add("--loglevel");
            cmd.add("info");
            cmd.add("--transport-loglevel");
            cmd.add("warn");
            cmd.add("--metrics");
            cmd.add("127.0.0.1:0");
            cmd.add("--edge-ip-version");
            cmd.add("auto");
            if (!"auto".equals(transport)) {
                cmd.add("--protocol");
                cmd.add(transport);
            }
            cmd.add("run");
            cmd.add("--token-file");
            cmd.add(tf.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(context.getFilesDir());
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            process = p;
            state = STATE_CONNECTING;
            error = "";
            startedAt = System.currentTimeMillis();
            connectedAt = 0L;
            log(context, "START cloudflared=" + CLOUDFLARED_VERSION + " transport=" + transport
                    + " origin=http://127.0.0.1:" + port);

            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = r.readLine()) != null) onLine(appContext, p, line);
                    } catch (Throwable ignored) {}
                }
            }, "Deekseep-cloudflared-log");
            reader.setDaemon(true);
            reader.start();
        } catch (Throwable t) {
            process = null;
            deleteTokenFile();
            state = STATE_ERROR;
            error = "启动 cloudflared 失败：" + describe(t);
            backoff();
            log(context, "START_FAILED " + error);
            Log.w(TAG, error, t);
        }
    }

    static void stop(String reason) {
        Process p = process;
        process = null;
        connectedAt = 0L;
        deleteTokenFile();
        if (p == null) return;
        log("STOP reason=" + reason);
        try {
            p.destroy();
        } catch (Throwable ignored) {}
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    p.waitFor();
                } catch (Throwable ignored) {}
            }
        }, "Deekseep-cloudflared-stop");
        t.setDaemon(true);
        t.start();
    }

    static void deleteTokenFile() {
        File f = tokenFile;
        tokenFile = null;
        if (f != null) {
            try {
                if (f.exists() && !f.delete()) f.deleteOnExit();
            } catch (Throwable ignored) {}
        }
    }

    static void backoff() {
        attempt = Math.min(6, attempt + 1);
        long delay = Math.min(60000L, 3000L << Math.min(4, attempt - 1));
        retryAt = SystemClock.elapsedRealtime() + delay;
    }

    // ---------------------------------------------------------------- status

    static void fillStatus(Context context, Bundle out) {
        SharedPreferences prefs = prefs(context);
        out.putBoolean("requested", prefs.getBoolean(KEY_REQUESTED, false));
        out.putBoolean("token_configured", hasToken(prefs));
        out.putBoolean("configured", hasToken(prefs)
                && prefs.getString(KEY_DOMAINS, "").trim().length() > 0);
        out.putString("provider", "cloudflare");
        String domains = prefs.getString(KEY_DOMAINS, "");
        out.putString("domains", domains);
        String primary = "";
        if (domains != null && domains.trim().length() > 0) {
            int nl = domains.indexOf('\n');
            primary = "https://" + (nl < 0 ? domains : domains.substring(0, nl)).trim();
        }
        out.putString("primary_root", primary);
        String direct = prefs.getString(KEY_DIRECT_ROOT, "");
        out.putString("direct_root", direct == null ? "" : direct);
        out.putString("transport", normalizeTransport(prefs.getString(KEY_TRANSPORT, "auto")));
        out.putString("state", state);
        out.putString("error", error == null ? "" : error);

        StringBuilder recent = new StringBuilder();
        synchronized (LOG) {
            int skip = Math.max(0, LOG.size() - 12);
            int i = 0;
            for (String line : LOG) {
                if (i++ < skip) continue;
                if (recent.length() > 0) recent.append('\n');
                recent.append(line);
            }
        }
        out.putString("recent_log", recent.toString());
        out.putBoolean("gateway_running", requested);
        out.putInt("gateway_port", port);
        out.putString("origin", "http://127.0.0.1:" + (port > 0 ? port : DEFAULT_PORT));
        out.putBoolean("binary_available", binary(context).isFile());
        out.putString("cloudflared_version", CLOUDFLARED_VERSION);
        out.putLong("started_at", startedAt);
        out.putLong("connected_at", connectedAt);
        out.putLong("retry_in_ms", Math.max(0L, retryAt - SystemClock.elapsedRealtime()));
        out.putString("log_file",
                new File(context.getFilesDir(), "deekseep_cloudflared.log").getAbsolutePath());
    }

    static Bundle status(Context context) {
        Bundle out = new Bundle();
        appContext = context.getApplicationContext();
        out.putBoolean("accepted", true);
        fillStatus(context, out);
        return out;
    }

    /** Called by {@code LocalApiGateway} whenever the gateway starts or stops. */
    static void setGatewayState(boolean running, int listenPort) {
        requested = running;
        port = listenPort;
        if (appContext != null) reconcile(appContext);
    }

    static String describe(Throwable t) {
        String m = t.getMessage();
        if (m != null && m.trim().length() > 0) return redact(m);
        return t.getClass().getSimpleName();
    }
}
