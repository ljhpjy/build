package com.dsmod.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Foreground service that keeps the Deekseep local API gateway and its SSE streams alive.
 *
 * <p>Rebuilt from the 1.7.5 APK dex (R8-obfuscated). Symbol names of the original source are
 * unknown; these names were chosen to match the observed behaviour and string constants.</p>
 */
public final class LocalApiKeepAliveService extends Service {
    static final String TAG = "DeekseepKeepAlive";
    static final String PREFS = "deekseep_local_api_keepalive";
    static final String KEY_REQUESTED = "requested";
    static final String ACTION_START = "com.dsmod.probe.action.START_LOCAL_API_KEEPALIVE";
    static final String ACTION_CONTROL = "com.dsmod.probe.action.LOCAL_API_CONTROL";
    static final String EXTRA_TOKEN = "deekseep_control_token";
    static final String CONTROL_TOKEN = "deekseep-local-api-keepalive-v1";
    static final String HOST_PACKAGE = "com.deepseek.chat";
    static final String HOST_RECEIVER = "com.deepseek.chat.system.ShareResultReceiver";
    static final String CHANNEL_ID = "deekseep_local_api";
    static final int NOTIFICATION_ID = 0xD5A1;

    public static volatile boolean running;
    public static volatile long startedAtMs;
    public static volatile long lastBroadcastAtMs;
    public static volatile long lastAckAtMs;
    public static volatile boolean gatewayRunning;
    public static volatile String lastError = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable keepAliveTick = new Runnable() {
        @Override
        public void run() {
            // Placeholder: original tick refreshes the gateway heartbeat and reposts itself.
        }
    };
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
            acquireCpuLock();
            running = true;
            startedAtMs = SystemClock.elapsedRealtime();
            lastError = "";
            handler.removeCallbacks(keepAliveTick);
            handler.post(keepAliveTick);
            Log.i(TAG, "local API foreground keepalive started");
        } catch (Throwable t) {
            lastError = "前台保活初始化失败：" + describe(t);
            Log.e(TAG, lastError, t);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (!isRequested()) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        } else if (!ACTION_START.equals(intent.getAction())
                || !CONTROL_TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN))) {
            lastError = "拒绝了无效的保活启动请求";
            SharedPreferences prefs = getSharedPreferences(PREFS, 0);
            prefs.edit().putBoolean(KEY_REQUESTED, false).apply();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_REQUESTED, true).apply();
        if (!isRequested()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(keepAliveTick);
        handler.post(keepAliveTick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        gatewayRunning = false;
        startedAtMs = 0L;
        handler.removeCallbacks(keepAliveTick);
        synchronized (PublicTunnelManager.LOCK) {
            PublicTunnelManager.requested = false;
            PublicTunnelManager.pid = 0;
            PublicTunnelManager.log("foreground service stopped");
            boolean tunnelRequested = getSharedPreferences("deekseep_public_tunnel", 0)
                    .getBoolean(KEY_REQUESTED, false);
            PublicTunnelManager.state = tunnelRequested ? "waiting_local_api" : "stopped";
        }
        synchronized (PinggyTunnelManager.LOCK) {
            Context app = getApplicationContext();
            PinggyTunnelManager.requested = false;
            PinggyTunnelManager.pid = 0;
            if (app != null) {
                app.getSharedPreferences("deekseep_pinggy_tunnel", 0).edit()
                        .putBoolean(KEY_REQUESTED, false).apply();
            }
            PinggyTunnelManager.stop("foreground service stopped", true);
            PinggyTunnelManager.state = "stopped";
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        wakeLock = null;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        Log.i(TAG, "local API foreground keepalive stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isRequested() {
        return getSharedPreferences(PREFS, 0).getBoolean(KEY_REQUESTED, false);
    }

    /** Static counterpart used by {@code Main} when resuming the gateway at package load. */
    static boolean isRequested(Context context) {
        if (context == null) return false;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, 0).getBoolean(KEY_REQUESTED, false);
    }

    private void acquireCpuLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService("power");
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Deekseep:LocalApiKeepAlive");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable t) {
            lastError = "CPU 保活不可用：" + describe(t);
            Log.w(TAG, lastError, t);
        }
    }

    private Notification buildNotification() {
        PendingIntent contentIntent = null;
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(HOST_PACKAGE);
            if (launch != null) {
                contentIntent = PendingIntent.getActivity(this, 0, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
        } catch (Throwable ignored) {}
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? NotificationIcons.newBuilder(this) : new Notification.Builder(this);
        builder.setSmallIcon(NotificationIcons.smallIcon(this));
        builder.setContentTitle(UiLanguage.text(this,
                "DeepSeek 本地 API 正在运行", "DeepSeek Local API is running"));
        boolean publicTunnel = "connected".equals(PublicTunnelManager.state)
                && PublicTunnelManager.process != null;
        boolean pinggyTunnel = PinggyTunnelManager.isRunning();
        String text = (publicTunnel || pinggyTunnel)
                ? UiLanguage.text(this, "本地、局域网与公网入口均在运行",
                        "Local, LAN, and public endpoints are active")
                : UiLanguage.text(this, "正在保持后台监听与流式响应稳定",
                        "Keeping background listening and streaming responses stable");
        builder.setContentText(text);
        builder.setCategory("service");
        builder.setOngoing(true);
        builder.setOnlyAlertOnce(true);
        builder.setShowWhen(false);
        if (contentIntent != null) builder.setContentIntent(contentIntent);
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService("notification");
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                UiLanguage.text(this, "DeepSeek 本地 API", "DeepSeek Local API"),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(UiLanguage.text(this,
                "保持本地 API 与 SSE 流在后台可用",
                "Keeps the local API and SSE streams available in the background"));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /** Starts or stops the keepalive service; returns false only when the IPC call failed. */
    public static boolean setRequested(Context context, boolean requested) {
        Context app = context.getApplicationContext();
        app.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_REQUESTED, requested).apply();
        Intent intent = new Intent(app, LocalApiKeepAliveService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TOKEN, CONTROL_TOKEN);
        try {
            if (requested) {
                if (Build.VERSION.SDK_INT >= 26) {
                    app.startForegroundService(intent);
                } else {
                    app.startService(intent);
                }
            } else {
                app.stopService(intent);
            }
            return true;
        } catch (Throwable t) {
            lastError = (requested ? "启动" : "停止") + "前台保活失败：" + describe(t);
            Log.w(TAG, lastError, t);
            return false;
        }
    }

    /** Broadcasts the current protocol to the DeepSeek host receiver. */
    public static void notifyHost(LocalApiKeepAliveActivity activity, String protocol) {
        Intent intent = new Intent(ACTION_CONTROL);
        intent.setComponent(new ComponentName(HOST_PACKAGE, HOST_RECEIVER));
        intent.putExtra(EXTRA_TOKEN, CONTROL_TOKEN);
        intent.putExtra("protocol", protocol);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.sendBroadcast(intent);
    }

    /** Fills a status bundle describing the current keepalive state. */
    public static void describeStatus(Bundle out) {
        long now = SystemClock.elapsedRealtime();
        out.putBoolean("running", running);
        out.putBoolean("requested", running || startedAtMs > 0L);
        out.putBoolean("gateway_running", gatewayRunning);
        out.putLong("last_broadcast_age_ms",
                lastBroadcastAtMs <= 0L ? -1L : Math.max(0L, now - lastBroadcastAtMs));
        out.putLong("last_ack_age_ms",
                lastAckAtMs <= 0L ? -1L : Math.max(0L, now - lastAckAtMs));
        out.putString("error", lastError);
    }

    static String describe(Throwable t) {
        String message = t.getMessage();
        if (message != null && message.length() > 0) return message;
        return t.getClass().getSimpleName();
    }
}
