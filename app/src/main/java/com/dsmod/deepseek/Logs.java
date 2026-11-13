package com.dsmod.deepseek;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Self-contained, categorized log store. Nothing is written to logcat or the
 * LSPosed log; lines are masked on write and kept in:
 *
 *   files/dsmod_log.txt      full timeline (512KB rolling)
 *   files/dsmod_errors.txt   W/E only, for quick error triage (512KB rolling)
 *
 * Categories: MAIN (injection/lifecycle), HTTP (local API), ACCOUNT (pool),
 * UPSTREAM (DeepSeek web API), CHAT (prompt/tools/response), CONFIG.
 */
public final class Logs {
    public static final String MAIN = "MAIN";
    public static final String HTTP = "HTTP";
    public static final String ACCOUNT = "ACCOUNT";
    public static final String UPSTREAM = "UPSTREAM";
    public static final String CHAT = "CHAT";
    public static final String CONFIG = "CONFIG";

    private static final int MAX_MEMORY = 800;
    private static final int MAX_CAT_MEMORY = 400;
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> RING = new ArrayDeque<>();
    private static final Map<String, ArrayDeque<String>> BY_CAT = new LinkedHashMap<>();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile Context ctx;

    private Logs() {}

    public static void init(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        ctx = app != null ? app : context;
        // Flush lines captured before the host context existed (module load / hooks).
        synchronized (LOCK) {
            if (!RING.isEmpty()) {
                try {
                    File f = new File(ctx.getFilesDir(), "dsmod_log.txt");
                    try (FileOutputStream fos = new FileOutputStream(f, true)) {
                        for (String line : RING) {
                            fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    RING.clear();
                } catch (Throwable ignore) { }
            }
        }
    }

    public static void i(String cat, String tag, String msg) { write("I", cat, tag, msg, null); }
    public static void w(String cat, String tag, String msg) { write("W", cat, tag, msg, null); }
    public static void w(String cat, String tag, String msg, Throwable t) { write("W", cat, tag, msg, t); }
    public static void e(String cat, String tag, String msg) { write("E", cat, tag, msg, null); }
    public static void e(String cat, String tag, String msg, Throwable t) { write("E", cat, tag, msg, t); }
    /** 当前线程的请求 ID；所有日志自动附带，方便按一次请求串联。 */
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    /**
     * Suppress I/D output on the current thread (W/E still pass). Background
     * prefetch work uses this so its multi-step chatter collapses into one
     * summary line instead of polluting the request view.
     */
    private static final ThreadLocal<Boolean> QUIET = new ThreadLocal<>();

    public static void setQuiet(boolean quiet) {
        if (quiet) QUIET.set(Boolean.TRUE); else QUIET.remove();
    }

    private static boolean isQuiet() {
        return Boolean.TRUE.equals(QUIET.get());
    }

    public static void setTrace(String trace) {
        if (trace == null) TRACE.remove(); else TRACE.set(trace);
    }

    public static void clearTrace() {
        TRACE.remove();
    }
    public static void d(String cat, String tag, String msg) { write("D", cat, tag, msg, null); }

    private static void write(String level, String cat, String tag, String msg, Throwable t) {
        if (isQuiet() && !"W".equals(level) && !"E".equals(level)) return;
        String time;
        synchronized (LOCK) {
            time = TIME.format(new Date());
        }
        String safeCat = (cat == null || cat.isEmpty()) ? MAIN : cat;
        String clean = sanitize(msg);
        String trace = TRACE.get();
        String prefix = (trace == null || trace.isEmpty()) ? "" : " " + trace;
        String line = time + " " + level + "/" + safeCat + "[" + tag + "]" + prefix + ": " + clean;
        if (t != null) {
            line += "\n" + sanitize(Log.getStackTraceString(t));
        }
        // Deliberately no logcat / XposedBridge output: logs stay in our own
        // masked store so private data never reaches system / LSPosed logs.
        synchronized (LOCK) {
            RING.addLast(line);
            while (RING.size() > MAX_MEMORY) RING.removeFirst();
            ArrayDeque<String> bucket = BY_CAT.get(safeCat);
            if (bucket == null) {
                bucket = new ArrayDeque<>();
                BY_CAT.put(safeCat, bucket);
            }
            bucket.addLast(line);
            while (bucket.size() > MAX_CAT_MEMORY) bucket.removeFirst();
            appendFile(line, false);
            if ("E".equals(level) || "W".equals(level)) appendFile(line, true);
        }
    }

    private static void appendFile(String line, boolean errors) {
        Context c = ctx;
        if (c == null) return;
        try {
            String name = errors ? "dsmod_errors.txt" : "dsmod_log.txt";
            File f = new File(c.getFilesDir(), name);
            if (f.length() > MAX_FILE_BYTES) {
                File old = new File(c.getFilesDir(), name + ".1");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignore) { }
    }

    /** Last N lines for a category (null / "" / "ALL" = everything). */
    public static String tail(String cat, int lines) {
        int n = Math.max(1, lines);
        String filter = (cat == null || cat.isEmpty() || "ALL".equalsIgnoreCase(cat))
                ? null : ("/" + cat + "[");
        Context c = ctx;
        if (c != null) {
            try {
                File cur = new File(c.getFilesDir(), "dsmod_log.txt");
                if (cur.isFile() && cur.length() > 0) {
                    String[] all = new String(readAll(cur), StandardCharsets.UTF_8).split("\n", -1);
                    ArrayDeque<String> picked = new ArrayDeque<>();
                    for (int i = all.length - 1; i >= 0 && picked.size() < n; i--) {
                        String line = all[i];
                        if (line.isEmpty()) continue;
                        if (filter != null && !line.contains(filter)) continue;
                        picked.addFirst(line);
                    }
                    if (!picked.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (String line : picked) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(line);
                        }
                        return sb.toString();
                    }
                    if (filter == null) return "";
                }
            } catch (Throwable ignore) { }
        }
        synchronized (LOCK) {
            ArrayDeque<String> src = filter == null ? RING : BY_CAT.get(cat);
            if (src == null) return "";
            int start = Math.max(0, src.size() - n);
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (String line : src) {
                if (i++ < start) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /** Full (or per-category) log for export: rotated file + current file. */
    public static String export(String cat) {
        String filter = (cat == null || cat.isEmpty() || "ALL".equalsIgnoreCase(cat))
                ? null : ("/" + cat + "[");
        StringBuilder sb = new StringBuilder();
        Context c = ctx;
        if (c != null) {
            appendFileContent(sb, new File(c.getFilesDir(), "dsmod_log.txt.1"), filter, "rotated log");
            appendFileContent(sb, new File(c.getFilesDir(), "dsmod_log.txt"), filter, "current log");
        }
        if (sb.length() == 0) sb.append(tail(cat, Integer.MAX_VALUE));
        return sb.toString();
    }

    private static void appendFileContent(StringBuilder sb, File f, String filter, String label) {
        try {
            if (f.isFile() && f.length() > 0) {
                sb.append("===== ").append(label).append(" =====\n");
                String[] lines = new String(readAll(f), StandardCharsets.UTF_8).split("\n", -1);
                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    if (filter != null && !line.contains(filter)) continue;
                    sb.append(line).append('\n');
                }
            }
        } catch (Throwable ignore) { }
    }

    /**
     * Everything logged for one request id, so a single bad turn can be
     * exported without the noise of the whole file.
     */
    public static String exportRid(String rid) {
        StringBuilder sb = new StringBuilder();
        if (rid == null || rid.isEmpty()) return sb.toString();
        String needle = "rid=" + rid;
        Context c = ctx;
        if (c != null) {
            appendFileContent(sb, new File(c.getFilesDir(), "dsmod_log.txt.1"), needle, "rotated " + rid);
            appendFileContent(sb, new File(c.getFilesDir(), "dsmod_log.txt"), needle, "current " + rid);
        }
        if (sb.length() == 0) {
            // The file may already have rolled over; fall back to the ring.
            for (String line : tail(null, Integer.MAX_VALUE).split("\n", -1)) {
                if (line.contains(needle)) sb.append(line).append('\n');
            }
            if (sb.length() > 0) sb.insert(0, "===== memory " + rid + " =====\n");
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            RING.clear();
            BY_CAT.clear();
            Context c = ctx;
            if (c != null) {
                try {
                    new File(c.getFilesDir(), "dsmod_log.txt").delete();
                    new File(c.getFilesDir(), "dsmod_log.txt.1").delete();
                    new File(c.getFilesDir(), "dsmod_errors.txt").delete();
                    new File(c.getFilesDir(), "dsmod_errors.txt.1").delete();
                } catch (Throwable ignore) { }
            }
        }
    }

    public static String[] categories() {
        return new String[]{MAIN, HTTP, ACCOUNT, UPSTREAM, CHAT, CONFIG};
    }

    /** Mask tokens / bearer headers / long opaque ids. */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = text;
        s = s.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._\\-+/=]{8,}", "$1****");
        s = s.replaceAll(
                "(?i)((?:token|authorization|api[_-]?key|cookie|set-cookie)(?:[\\\"']?\\s*[:=]\\s*[\\\"']?))([A-Za-z0-9._\\-+/=]{6,})",
                "$1****");
        s = s.replaceAll("sk-[A-Za-z0-9]{10,}", "sk-****");
        // Tool names look like mcp__server__tool and must survive masking:
        // they are the only way to tell which tool the model really called.
        s = s.replaceAll("([A-Za-z0-9_]+__[A-Za-z0-9_]+)", "\u0001$1\u0001");
        s = s.replaceAll("[A-Za-z0-9_\\-]{28,}", "****");
        s = s.replaceAll("\u0001", "");
        return s;
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
}
