package com.fuckds.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 参考 IDAdroid 架构重写的后台服务：
 *   - proot 二进制来自 APK 小 asset（首次复制，chmod）
 *   - rootfs 由主界面导入（filesDir/linux/rootfs），不再内嵌大 payload
 *   - 端口冲突自动随机换端口重试（最多 10 次），healthz 探活通过才报“运行中”
 *   - 状态写入 SharedPreferences，主界面实时展示
 */
public class BootService extends Service {

    public static final String ACTION_STOP = "com.fuckds.app.STOP";

    private static final String CHANNEL_ID = "fuckds-service";
    private static final int NOTIF_ID = 1;

    private volatile Process proc;
    private Thread worker;
    private volatile boolean stopping;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::run, "fuckds-boot");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (!stopping) {
            stopEverything();
        }
        super.onDestroy();
    }

    /* ------------------------------------------------------------------ */

    private void run() {
        stopping = false;
        acquireWakeLock();
        setStatus("正在准备运行环境…");
        try {
            updateNotification("正在准备运行环境…", true);
            File base = new File(getFilesDir(), "linux");
            File rootfs = new File(base, "rootfs");

            if (!new File(base, ".rootfs-ready").exists()) {
                String msg = "还没有导入 rootfs，请回主界面点「1. 导入 fuckds-rootfs.tar.xz」";
                updateNotification(msg, false);
                setStatus(msg);
            } else {
                ensureProot(base);
                // 用户在配置页手动指定了端口时不注入 PORT 环境变量，
                // 服务会读 .env 里的 PORT；否则走自动模式（默认 18787 + 冲突随机重试）
                int manualPort = prefs().getInt("port_manual", 0);
                int port = manualPort > 0 ? manualPort : prefs().getInt("port", 18787);
                int attempts = 0;
                while (true) {
                    String s = "正在启动 DeepSeek 代理服务…（端口 " + port + "）";
                    updateNotification(s, true);
                    setStatus(s);
                    spawn(base, port, manualPort);

                    if (waitForServer(port, 45)) {
                        prefs().edit().putInt("port", port).apply();
                        s = "服务运行中 · http://127.0.0.1:" + port + " · 点通知或主界面打开控制台";
                        updateNotification(s, false);
                        setStatus(s);
                        int code = proc.waitFor();
                        s = "服务已停止（退出码 " + code + "）";
                        updateNotification(s, false);
                        setStatus(s);
                        break;
                    }

                    if (!isAlive(proc)) {
                        // 进程已退出且未监听 → 大概率端口冲突
                        proc.destroy();
                        proc = null;
                        if (manualPort > 0) {
                            s = "启动失败：指定端口 " + port + " 不可用（被占用？），请回配置页换一个端口";
                            updateNotification(s, false);
                            setStatus(s);
                            break;
                        }
                        attempts++;
                        if (attempts >= 10) {
                            s = "启动失败：连续 10 次端口冲突，请关闭占用端口的其它本地服务后重试";
                            updateNotification(s, false);
                            setStatus(s);
                            break;
                        }
                        port = 20000 + ThreadLocalRandom.current().nextInt(20000);
                        continue;
                    }

                    proc.destroy();
                    proc = null;
                    String s = "启动失败：服务监听超时（45 秒），请查看日志";
                    updateNotification(s, false);
                    setStatus(s);
                    break;
                }
            }
        } catch (Exception e) {
            String msg = "启动失败：" + shortMessage(e);
            updateNotification(msg, false);
            setStatus(msg);
        } finally {
            stopping = true;
            if (proc != null) {
                proc.destroy();
            }
            releaseWakeLock();
            stopForeground(false);   // 保留通知，让错误/退出码可读
            stopSelf();
        }
    }

    private void ensureProot(File base) throws IOException {
        File proot = new File(base, "proot");
        if (!proot.exists() || proot.length() < 1000) {
            try (InputStream is = getAssets().open("payload/proot");
                 FileOutputStream os = new FileOutputStream(proot)) {
                copy(is, os);
            }
            chmodX(proot);
        }
    }

    private void spawn(File base, int port, int manualPort) throws IOException {
        File rootfs = new File(base, "rootfs");
        File proot = new File(base, "proot");

        File resolv = new File(base, "resolv.conf");
        try (FileOutputStream os = new FileOutputStream(resolv)) {
            os.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n"
                    .getBytes("UTF-8"));
        }

        File logDir = new File(base, "logs");
        logDir.mkdirs();

        ProcessBuilder pb = new ProcessBuilder(
                proot.getAbsolutePath(),
                "-r", rootfs.getAbsolutePath(),
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                // 合并 usr 的关键目录（个别 ROM 限制 symlink，bind 兜底）
                "-b", rootfs.getAbsolutePath() + "/usr/bin:/bin",
                "-b", rootfs.getAbsolutePath() + "/usr/sbin:/sbin",
                "-b", rootfs.getAbsolutePath() + "/usr/lib:/lib",
                "-b", rootfs.getAbsolutePath() + "/usr/lib64:/lib64",
                "-b", resolv.getAbsolutePath() + ":/etc/resolv.conf",
                "-w", "/opt/fuckds",
                "/bin/sh", "/opt/fuckds/android-run.sh"
        );
        Map<String, String> env = pb.environment();
        env.put("HOME", "/opt/fuckds");
        env.put("TMPDIR", "/tmp");
        env.put("PATH", "/usr/local/node/bin:/usr/bin:/bin");
        env.put("TZ", TimeZone.getDefault().getID());
        env.put("LD_LIBRARY_PATH", base.getAbsolutePath());
        if (manualPort <= 0) {
            // 自动模式：注入端口（冲突时由本服务换端口重试）；
            // 手动模式不注入，让服务读 .env 里的 PORT，保证用户配置生效
            env.put("PORT", String.valueOf(port));
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir, "container.log")));
        proc = pb.start();
    }

    /** 轮询 /healthz 直到服务真正就绪（最长 seconds 秒） */
    private boolean waitForServer(int port, int seconds) {
        for (int i = 0; i < seconds; i++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/healthz")
                        .openConnection();
                conn.setConnectTimeout(800);
                conn.setReadTimeout(800);
                if (conn.getResponseCode() == 200) {
                    return true;
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
        return false;
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /* ------------------------- 生命周期辅助 ------------------------- */

    private void stopEverything() {
        stopping = true;
        Process p = proc;
        proc = null;
        if (p != null) {
            p.destroy();
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
                p.destroyForcibly();
            });
            t.start();
        }
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "fuckds 服务", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTIF_ID, buildNotification("正在启动…", true));
    }

    private Notification buildNotification(String text, boolean indeterminate) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, BootService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_fuckds)
                .setContentTitle("fuckds · DeepSeek 代理")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(0, "停止服务", stopPi);
        if (indeterminate) {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void updateNotification(String text, boolean indeterminate) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text, indeterminate));
    }

    private void setStatus(String text) {
        prefs().edit().putString("status", text).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("fuckds", 0);
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fuckds:server");
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(4 * 60 * 60 * 1000L);
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    /* --------------------------- 文件辅助 --------------------------- */

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static void chmodX(File f) {
        if (f != null && f.exists()) {
            f.setExecutable(true, false);
            f.setReadable(true, false);
        }
    }

    private static String shortMessage(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }
}
