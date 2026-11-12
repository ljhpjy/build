package com.dsmod.deepseek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Posts an ongoing notification from inside the host process.
 *
 * A module Service cannot live in the host process (the host manifest does
 * not declare it), so startService would throw there. Notify() does not
 * require a manifest entry promptly: a host can post its own notifications
 * with its own uid. This keeps the API status visible to the user and is
 * removed when the host process exits.
 */
public final class Notifier {
    private static final String CHANNEL = "dsmod_api";
    private static final int NOTIFY_ID = 0x44534D4F;
    private static final String HOST_PACKAGE = "com.deepseek.chat";

    private Notifier() {}

    @SuppressWarnings("deprecation")
    public static void show(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            NotificationManager nm =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = nm.getNotificationChannel(CHANNEL);
                if (channel == null) {
                    channel = new NotificationChannel(CHANNEL, "DeepSeek Local API",
                            NotificationManager.IMPORTANCE_LOW);
                    channel.setDescription("Local OpenAI / Anthropic compatible API endpoint");
                    nm.createNotificationChannel(channel);
                }
            }

            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL)
                    : new Notification.Builder(app);
            builder.setContentTitle("DeepSeek Local API")
                    .setContentText("Listening on 0.0.0.0:" + Config.port)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setOngoing(true)
                    .setShowWhen(false);

            PendingIntent contentIntent = launchIntent(app);
            if (contentIntent != null) builder.setContentIntent(contentIntent);

            nm.notify(NOTIFY_ID, builder.build());
        } catch (Throwable ignore) { }
    }

    public static void hide(Context context) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFY_ID);
        } catch (Throwable ignore) { }
    }

    private static PendingIntent launchIntent(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(HOST_PACKAGE);
            if (intent == null) return null;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getActivity(context, 0, intent, flags);
        } catch (Throwable t) {
            return null;
        }
    }
}
