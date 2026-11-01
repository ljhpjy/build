package com.dsmod.probe;

import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;

/**
 * Grants the companion process access to active media sessions.
 *
 * <p>1.7.5 added this notification-listener service so the Agent music tools can find the
 * QQ Music / NetEase Cloud Music session even when the host app is not in the foreground. The
 * original class carried a single method; here it simply publishes the session manager so
 * {@link QqMusicPlaybackService} can query active controllers.</p>
 */
public final class QqMusicSessionAccessService extends NotificationListenerService {
    static volatile MediaSessionManager sessionManager;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            sessionManager = (MediaSessionManager) getSystemService("media_session");
        } catch (Throwable ignored) {
            sessionManager = null;
        }
    }

    /** Returns the cached manager, or null when the listener has not connected yet. */
    static MediaSessionManager manager() {
        return sessionManager;
    }
}
