package com.fuckds.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * 打开即用：先拉起 BootService（proot + node 服务），
 * 再用 WebView 打开本地 http://127.0.0.1:8787/admin/ 管理台。
 * 服务没就绪时自动重试加载。
 */
public class MainActivity extends Activity {

    private static final String CONSOLE_URL = "http://127.0.0.1:8787/admin/";
    private static final int MAX_RETRIES = 100;

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retries = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    scheduleReload();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // 仅低版本兜底，API 23+ 走上面的重载
                scheduleReload();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent it = new Intent(this, BootService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(it);
        } else {
            startService(it);
        }
        retries = 0;
        handler.postDelayed(() -> web.loadUrl(CONSOLE_URL), 500);
    }

    private void scheduleReload() {
        if (retries >= MAX_RETRIES) {
            if (retries == MAX_RETRIES) {
                retries++;
                Toast.makeText(this,
                        "服务还没就绪（首次解压需要几分钟），请留意通知栏进度",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        retries++;
        handler.postDelayed(() -> web.loadUrl(CONSOLE_URL), 1500);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
