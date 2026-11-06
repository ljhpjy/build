package com.fuckds.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.TimeZone;

/**
 * 前台服务：把 APK 里打包的完整 Linux 环境（Ubuntu rootfs + Node + Chromium）
 * 解压到应用私有目录，再用 proot 启动 fuckds 服务（browser / direct 双引擎完整版）。
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
        try {
            updateNotification("正在解压运行环境（仅首次，需几分钟）…", true);
            File base = preparePayload();

            updateNotification("正在启动 DeepSeek 代理服务…", true);
            spawn(base);

            updateNotification("服务运行中 · 127.0.0.1:8787 · 点击打开控制台", false);
            int code = proc.waitFor();
            updateNotification("服务已停止（退出码 " + code + "）", false);
        } catch (Exception e) {
            updateNotification("启动失败：" + shortMessage(e), false);
        } finally {
            stopping = true;
            if (proc != null) {
                proc.destroy();
            }
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }

    /** 首次安装解压 assets，之后带版本标记直接复用；升级时保留 data/（token）与 .env */
    private File preparePayload() throws IOException {
        File linux = new File(getFilesDir(), "linux");
        File app = new File(linux, "app");
        File marker = new File(linux, ".payload-version-" + BuildConfig.VERSION_CODE);
        if (marker.exists()) {
            return linux;
        }

        File backup = new File(getCacheDir(), "userdata-backup");
        deleteRecursively(backup);
        backup.mkdirs();
        if (linux.exists()) {
            move(new File(app, "data"), new File(backup, "data"));
            move(new File(app, ".env"), new File(backup, ".env"));
            deleteRecursively(linux);
        }
        linux.mkdirs();

        updateNotification("正在解压 Linux 环境（约 5~10 分钟，仅首次）…", true);
        try (InputStream is = getAssets().open("payload/rootfs.tar.gz")) {
            TarGz.extract(is, new File(linux, "rootfs"));
        }

        updateNotification("正在解压应用文件…", true);
        try (InputStream is = getAssets().open("payload/app.tar.gz")) {
            TarGz.extract(is, new File(linux, "app"));
        }

        File proot = new File(linux, "proot");
        try (InputStream is = getAssets().open("payload/proot");
             FileOutputStream os = new FileOutputStream(proot)) {
            copy(is, os);
        }

        chmodX(proot);
        chmodX(new File(linux, "app/android-run.sh"));
        chmodX(new File(linux, "app/bin/ds-pow"));
        chmodX(new File(linux, "rootfs/opt/chromium/chrome"));
        chmodX(new File(linux, "rootfs/usr/local/node/bin/node"));

        if (backup.exists()) {
            move(new File(backup, "data"), new File(app, "data"));
            move(new File(backup, ".env"), new File(app, ".env"));
        }
        marker.createNewFile();
        return linux;
    }

    private void spawn(File linux) throws IOException {
        File app = new File(linux, "app");
        File proot = new File(linux, "proot");
        File rootfs = new File(linux, "rootfs");

        // proot 里的 glibc 解析 DNS 需要一个常规 resolv.conf，从宿主文件绑定进去
        File resolv = new File(linux, "resolv.conf");
        try (FileOutputStream os = new FileOutputStream(resolv)) {
            os.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n"
                    .getBytes("UTF-8"));
        }

        File logDir = new File(app, "logs");
        logDir.mkdirs();

        ProcessBuilder pb = new ProcessBuilder(
                proot.getAbsolutePath(),
                "-r", rootfs.getAbsolutePath(),
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", app.getAbsolutePath() + ":/opt/fuckds",
                "-b", resolv.getAbsolutePath() + ":/etc/resolv.conf",
                "-w", "/opt/fuckds",
                "/bin/sh", "/opt/fuckds/android-run.sh"
        );
        Map<String, String> env = pb.environment();
        env.put("HOME", "/opt/fuckds");
        env.put("TMPDIR", "/tmp");
        env.put("PATH", "/usr/local/node/bin:/usr/bin:/bin");
        env.put("TZ", TimeZone.getDefault().getID());
        // Termux 版 proot 是 bionic 动态链接，若需要配套 .so 就从 payload 目录找（无害）
        env.put("LD_LIBRARY_PATH", linux.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir, "container.log")));
        proc = pb.start();
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

    private static void move(File src, File dst) {
        if (src == null || !src.exists()) {
            return;
        }
        if (dst.exists()) {
            deleteRecursively(dst);
        }
        File parent = dst.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        if (!src.renameTo(dst)) {
            deleteRecursively(src);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String shortMessage(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }
}
