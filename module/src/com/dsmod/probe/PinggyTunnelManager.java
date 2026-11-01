package com.dsmod.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pinggy SSH reverse-tunnel manager (new in 1.7.5).
 *
 * <p>Rebuilt from the APK dex (R8-obfuscated). Field names are deliberately kept short
 * where {@code LocalApiKeepAliveService} reads them directly: {@code c} (lock), {@code k}
 * (requested), {@code l} (remote id), {@code m} (state), plus {@code p(String, boolean)}
 * and {@code f()}.</p>
 */
final class PinggyTunnelManager {
    static final String TAG = "DeekseepPinggy";
    static final String PREFS = "deekseep_pinggy_tunnel";
    static final String KEY_REQUESTED = "requested";
    static final String KEY_URL = "url";
    static final String STATE_STOPPED = "stopped";
    static final String STATE_CONNECTING = "connecting";
    static final String STATE_CONNECTED = "connected";
    static final String STATE_ERROR = "error";
    static final String STATE_WAITING = "waiting_local_api";
    static final String STATE_RETRY_WAIT = "retry_wait";
    static final String LOG_FILE = "deekseep_pinggy.log";
    static final long LOG_MAX_BYTES = 0x80000L;
    static final int LOG_CAPACITY = 80;

    /** Guard for every mutable static below; referenced by the keepalive service. */
    static final Object c = new Object();
    static final ArrayDeque<String> logs = new ArrayDeque<>();

    static volatile Process process;
    static volatile long startedAt;
    static volatile long connectedAt;
    static volatile long retryAt;
    static volatile int attempt;
    static volatile boolean k;                // requested
    static volatile int l;                    // remote pid / connection id
    static volatile String m = STATE_STOPPED; // state
    static volatile String error = "";
    static volatile String url = "";
    static volatile int localPort;
    static volatile Context appContext;

    private PinggyTunnelManager() {}

    // ---------------------------------------------------------------- queries

    static boolean isRunning() {
        return STATE_CONNECTED.equals(m) && process != null;
    }

    static boolean f() {
        return isRunning();
    }

    // ------------------------------------------------------------- lifecycle

    /** Stops the tunnel. When {@code clearRequested} is set the persisted flag is cleared too. */
    static void p(String reason, boolean clearRequested) {
        stop(reason, clearRequested);
        m = STATE_STOPPED;
    }

    static void stop(String reason, boolean clearRequested) {
        Process p = process;
        process = null;
        connectedAt = 0L;
        url = "";
        if (clearRequested) {
            k = false;
            l = 0;
            Context ctx = appContext;
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS, 0).edit()
                        .putBoolean(KEY_REQUESTED, false)
                        .remove(KEY_URL)
                        .apply();
            }
        }
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
        }, "Deekseep-pinggy-stop");
        t.setDaemon(true);
        t.start();
    }

    static void setLocalPort(int port) {
        localPort = port;
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0);
    }

    static Bundle setRequested(Context context, boolean enable) {
        Bundle out = new Bundle();
        Context ctx = context.getApplicationContext();
        appContext = ctx;
        SharedPreferences prefs = prefs(ctx);
        if (enable && localPort <= 0) {
            out.putBoolean("accepted", false);
            out.putString("error", "本地 API 尚未运行，无法建立 Pinggy 隧道");
            fillStatus(ctx, out);
            return out;
        }
        prefs.edit().putBoolean(KEY_REQUESTED, enable).apply();
        synchronized (c) {
            attempt = 0;
            retryAt = 0L;
            error = "";
        }
        if (!enable) {
            p("disabled", false);
        } else {
            reconcile(ctx);
        }
        out.putBoolean("accepted", true);
        fillStatus(ctx, out);
        return out;
    }

    static void reconcile(Context context) {
        synchronized (c) {
            if (!prefs(context).getBoolean(KEY_REQUESTED, false)) {
                stop("disabled", false);
                m = STATE_STOPPED;
                return;
            }
            if (localPort <= 0) {
                stop("local API unavailable", false);
                m = STATE_WAITING;
                return;
            }
            if (process != null) return;
            if (SystemClock.elapsedRealtime() < retryAt) {
                m = STATE_RETRY_WAIT;
                return;
            }
            start(context);
        }
    }

    static void start(Context context) {
        // Pinggy exposes a reverse SSH tunnel on port 443; the public URL is printed to stdout.
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("/system/bin/sh");
            cmd.add("-c");
            cmd.add("ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=30 "
                    + "-p 443 -R0:127.0.0.1:" + localPort + " a.pinggy.io");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(context.getFilesDir());
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            process = p;
            m = STATE_CONNECTING;
            error = "";
            startedAt = System.currentTimeMillis();
            connectedAt = 0L;
            log(context, "START pinggy local=" + localPort);

            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = r.readLine()) != null) {
                            log(appContext, line);
                            String lower = line.toLowerCase(Locale.US);
                            if (lower.contains("http://") || lower.contains("https://")) {
                                int at = lower.indexOf("http");
                                url = line.substring(at).trim();
                                m = STATE_CONNECTED;
                                connectedAt = System.currentTimeMillis();
                                error = "";
                                attempt = 0;
                                retryAt = 0L;
                                Context ctx = appContext;
                                if (ctx != null) {
                                    ctx.getSharedPreferences(PREFS, 0).edit()
                                            .putString(KEY_URL, url)
                                            .apply();
                                }
                            } else if (lower.contains("permission denied")
                                    || lower.contains("connection refused")
                                    || lower.contains("could not resolve hostname")) {
                                error = "Pinggy 隧道连接失败，请检查网络后重试";
                            }
                        }
                    } catch (Throwable ignored) {}
                    synchronized (c) {
                        if (process == p) {
                            process = null;
                            if (!STATE_STOPPED.equals(m)) {
                                m = STATE_ERROR;
                                if (error == null || error.length() == 0) {
                                    error = "Pinggy 隧道已断开";
                                }
                                backoff();
                            }
                        }
                    }
                }
            }, "Deekseep-pinggy-log");
            reader.setDaemon(true);
            reader.start();
        } catch (Throwable t) {
            process = null;
            m = STATE_ERROR;
            error = "启动 Pinggy 隧道失败：" + describe(t);
            backoff();
            log(context, "START_FAILED " + error);
            Log.w(TAG, error, t);
        }
    }

    static void backoff() {
        attempt = Math.min(6, attempt + 1);
        long delay = Math.min(60000L, 3000L << Math.min(4, attempt - 1));
        retryAt = SystemClock.elapsedRealtime() + delay;
    }

    // ---------------------------------------------------------------- logging

    static void log(Context ctx, String line) {
        if (line == null) return;
        String clean = line.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "").trim();
        if (clean.length() == 0) return;
        synchronized (logs) {
            logs.addLast(clean);
            while (logs.size() > LOG_CAPACITY) logs.removeFirst();
        }
        if (ctx == null) return;
        try {
            File file = new File(ctx.getFilesDir(), LOG_FILE);
            if (file.isFile() && file.length() > LOG_MAX_BYTES) {
                File old = new File(ctx.getFilesDir(), LOG_FILE + ".old");
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

    // ---------------------------------------------------------------- status

    static void fillStatus(Context context, Bundle out) {
        SharedPreferences prefs = prefs(context);
        out.putBoolean("requested", prefs.getBoolean(KEY_REQUESTED, false));
        out.putString("state", m == null ? STATE_STOPPED : m);
        out.putString("error", error == null ? "" : error);
        out.putString("url", prefs.getString(KEY_URL, ""));
        out.putBoolean("running", isRunning());
        out.putInt("local_port", localPort);
        out.putLong("started_at", startedAt);
        out.putLong("connected_at", connectedAt);
        out.putLong("retry_in_ms", Math.max(0L, retryAt - SystemClock.elapsedRealtime()));
        StringBuilder recent = new StringBuilder();
        synchronized (logs) {
            int skip = Math.max(0, logs.size() - 12);
            int i = 0;
            for (String line : logs) {
                if (i++ < skip) continue;
                if (recent.length() > 0) recent.append('\n');
                recent.append(line);
            }
        }
        out.putString("recent_log", recent.toString());
        out.putString("log_file",
                new File(context.getFilesDir(), LOG_FILE).getAbsolutePath());
    }

    static Bundle status(Context context) {
        Bundle out = new Bundle();
        appContext = context.getApplicationContext();
        out.putBoolean("accepted", true);
        fillStatus(context, out);
        return out;
    }

    static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg != null && msg.trim().length() > 0) return msg.trim();
        return t.getClass().getSimpleName();
    }
}
