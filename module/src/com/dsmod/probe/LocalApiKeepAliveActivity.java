package com.dsmod.probe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Trampoline activity for {@code deekseep-module://local-api-keepalive}.
 *
 * <p>Rebuilt from the 1.7.5 APK dex (R8-obfuscated). The original class had four methods:
 * a constructor, {@code onCreate}, and two small helpers used to read the protocol from the
 * launch URI and to relay it to the DeepSeek host process.</p>
 */
public final class LocalApiKeepAliveActivity extends Activity {
    static final String HOST = "local-api-keepalive";
    static final String EXTRA_PROTOCOL = "protocol";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent intent = getIntent();
            String protocol = protocolFrom(intent == null ? null : intent.getData());
            if (protocol == null || protocol.length() == 0) {
                protocol = LocalApiGateway.currentProtocol();
            }
            LocalApiKeepAliveService.notifyHost(this, protocol);
            boolean accepted = LocalApiKeepAliveService.setRequested(this, true);
            if (!accepted) {
                Toast.makeText(this,
                        UiLanguage.text(this, "无法启动本地 API 前台保活",
                                "Unable to start the local API foreground keepalive"),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, LocalApiKeepAliveService.describe(t), Toast.LENGTH_SHORT).show();
        } finally {
            finish();
        }
    }

    /** Reads {@code ?protocol=} from the launch URI; null when absent or malformed. */
    static String protocolFrom(Uri uri) {
        if (uri == null) return null;
        try {
            if (!HOST.equals(uri.getHost())) return null;
            String value = uri.getQueryParameter(EXTRA_PROTOCOL);
            if (value == null) return null;
            value = value.trim().toLowerCase(java.util.Locale.US);
            if ("anthropic".equals(value) || "openai".equals(value)) return value;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
