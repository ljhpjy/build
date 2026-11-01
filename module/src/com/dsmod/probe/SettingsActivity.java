package com.dsmod.probe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Original two-page standalone shell for the Deekseep module APK. */
public class SettingsActivity extends Activity {
    public static final String VERSION = BuildInfo.MODULE_VERSION;

    private static final String TARGET_PACKAGE = "com.deepseek.chat";
    private static final String REPOSITORY = "https://github.com/lllucccian/Deekseep";
    private static final int REQ_STORAGE = 0xD540;
    private static final int REQ_NOTIFICATIONS = 0xD541;
    private static final int REQ_EXPORT = 0xD542;
    private static final int REQ_IMPORT = 0xD543;

    private boolean renderedChinese;
    private boolean renderedDark;
    private boolean rendered;
    private int page;
    private int bg;
    private int card;
    private int cardAlt;
    private int text;
    private int muted;
    private int line;
    private int accent;
    private LinearLayout content;
    private TextView pageTitle;
    private TextView activationTitle;
    private TextView activationDetail;
    private TextView activationMark;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable activationStateChanged = new Runnable() {
        @Override public void run() { refreshActivationState(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiLanguage.refreshSystem(this);
        renderShell();
        ensureStoragePermission();
        ensureNotificationPermission();
        XposedActivationProvider.setStateListener(activationStateChanged);
        handler.postDelayed(activationStateChanged, 300L);
        handler.postDelayed(activationStateChanged, 1200L);
    }

    @Override protected void onResume() {
        super.onResume();
        UiLanguage.refreshSystem(this);
        boolean chinese = UiLanguage.isChinese(this);
        boolean dark = isDark();
        if (!rendered || chinese != renderedChinese || dark != renderedDark) renderShell();
        XposedActivationProvider.setStateListener(activationStateChanged);
        handler.post(activationStateChanged);
        handler.postDelayed(activationStateChanged, 500L);
        ensureNotificationPermission();
    }

    @Override protected void onDestroy() {
        XposedActivationProvider.setStateListener(null);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void renderShell() {
        rendered = true;
        renderedChinese = UiLanguage.isChinese(this);
        renderedDark = isDark();
        bg = renderedDark ? 0xFF101113 : 0xFFF4F6F8;
        card = renderedDark ? 0xFF1B1D21 : 0xFFFFFFFF;
        cardAlt = renderedDark ? 0xFF24272C : 0xFFF0F3F7;
        text = renderedDark ? 0xFFF2F3F5 : 0xFF17191D;
        muted = renderedDark ? 0xFF9AA0AA : 0xFF68707C;
        line = renderedDark ? 0xFF30343A : 0xFFE2E6EB;
        accent = renderedDark ? 0xFF7EA6FF : 0xFF315FBE;
        configureWindow();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(22), dp(16), dp(22), dp(10));
        pageTitle = text("", 27, text, Typeface.create("sans-serif-medium", 0));
        titleBar.addView(pageTitle, matchWrap());
        root.addView(titleBar, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(bottomNavigation());
        setContentView(root);
        showPage(page);
    }

    private void showPage(int next) {
        page = next == 1 ? 1 : 0;
        if (content == null) return;
        content.removeAllViews();
        if (page == 0) showHome(); else showSettings();
    }

    private void showHome() {
        pageTitle.setText("Deekseep");
        LinearLayout hero = card();
        hero.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout mark = new FrameLayout(this);
        GradientDrawable markBg = rounded(accent, 18);
        mark.setBackground(markBg);
        TextView initial = text("D", 25, Color.WHITE,
                Typeface.create("sans-serif-medium", Typeface.BOLD));
        initial.setGravity(Gravity.CENTER);
        mark.addView(initial, new FrameLayout.LayoutParams(-1, -1));
        head.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, 0, 0);
        copy.addView(text(UiLanguage.text(this, "模块运行状态", "Module status"),
                13, muted, Typeface.DEFAULT));
        activationTitle = text("", 23, text,
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        copy.addView(activationTitle);
        activationDetail = text("", 12, muted, Typeface.DEFAULT);
        copy.addView(activationDetail);
        head.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        activationMark = text("", 25, accent, Typeface.DEFAULT);
        activationMark.setGravity(Gravity.CENTER);
        head.addView(activationMark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        hero.addView(head);
        content.addView(hero, cardLp());

        TextView section = sectionTitle(UiLanguage.text(this, "运行信息", "Runtime"));
        content.addView(section);
        LinearLayout info = card();
        info.addView(infoRow(UiLanguage.text(this, "DeepSeek 版本", "DeepSeek version"), deepSeekVersion()));
        info.addView(divider());
        info.addView(infoRow(UiLanguage.text(this, "模块版本", "Module version"), BuildInfo.MODULE_VERSION));
        info.addView(divider());
        info.addView(infoRow(UiLanguage.text(this, "构建渠道", "Build channel"),
                BuildInfo.PROTECTED_BUILD ? "Closed" : "Open"));
        info.addView(divider());
        info.addView(infoRow(UiLanguage.text(this, "编译时间", "Built"), BuildInfo.BUILD_DATE));
        content.addView(info, cardLp());

        content.addView(actionCard(ModuleGlyphView.HOME,
                UiLanguage.text(this, "打开 DeepSeek", "Open DeepSeek"),
                UiLanguage.text(this, "模块设置位于 DeepSeek 设置页", "Module settings are in DeepSeek settings"),
                new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
                        if (launch != null) startActivity(launch);
                        else toast(UiLanguage.text(SettingsActivity.this, "未安装 DeepSeek", "DeepSeek is not installed"));
                    }
                }));
        content.addView(actionCard(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "进程管理", "Process manager"),
                UiLanguage.text(this, "查看、冻结或终止 DeepSeek 与模块进程",
                        "Inspect, freeze, or stop DeepSeek and module processes"),
                new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        startActivity(new Intent(SettingsActivity.this,
                                ProcessManagerActivity.class));
                    }
                }));
        refreshActivationState();
    }

    private void showSettings() {
        pageTitle.setText(UiLanguage.text(this, "设置", "Settings"));
        content.addView(sectionTitle(UiLanguage.text(this, "配置", "Configuration")));
        LinearLayout config = card();
        config.addView(actionRow(ModuleGlyphView.EXPORT,
                UiLanguage.text(this, "导出配置", "Export configuration"),
                UiLanguage.text(this, "保存当前功能开关，不包含提示词、密钥和聊天", "Save feature switches without prompts, keys or chats"),
                new View.OnClickListener() { @Override public void onClick(View v) { chooseExport(); } }));
        config.addView(divider());
        config.addView(actionRow(ModuleGlyphView.IMPORT,
                UiLanguage.text(this, "导入配置", "Import configuration"),
                UiLanguage.text(this, "从配置文件恢复功能开关", "Restore feature switches from a config file"),
                new View.OnClickListener() { @Override public void onClick(View v) { chooseImport(); } }));
        content.addView(config, cardLp());

        content.addView(sectionTitle(UiLanguage.text(this, "项目", "Project")));
        LinearLayout project = card();
        project.addView(actionRow(ModuleGlyphView.SPONSOR,
                UiLanguage.text(this, "赞助开发", "Sponsor development"),
                UiLanguage.text(this, "支持更快地维护和适配", "Help speed up maintenance and compatibility work"),
                new View.OnClickListener() { @Override public void onClick(View v) { showSponsor(); } }));
        project.addView(divider());
        project.addView(actionRow(ModuleGlyphView.REPOSITORY,
                UiLanguage.text(this, "GitHub 仓库", "GitHub repository"), REPOSITORY,
                new View.OnClickListener() { @Override public void onClick(View v) { openUrl(REPOSITORY); } }));
        content.addView(project, cardLp());

        content.addView(sectionTitle(UiLanguage.text(this, "本地 API", "Local API")));
        LinearLayout apiCard = card();
        apiCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "启动本地 API", "Start local API"),
                UiLanguage.text(this, "在设备上开启 OpenAI / Anthropic 兼容网关",
                        "Serve an OpenAI / Anthropic compatible gateway on this device"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { startLocalApi(); }
                }));
        apiCard.addView(divider());
        apiCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "停止本地 API", "Stop local API"),
                UiLanguage.text(this, "关闭网关并结束后台保活",
                        "Shut down the gateway and its foreground keepalive"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { stopLocalApi(); }
                }));
        apiCard.addView(divider());
        apiCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "本地 API 状态", "Local API status"),
                UiLanguage.text(this, "查看端口、协议、密钥指纹与统计",
                        "Inspect port, protocol, key fingerprint and counters"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showLocalApiStatus(); }
                }));
        apiCard.addView(divider());
        apiCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "轮换 API Key", "Rotate API key"),
                UiLanguage.text(this, "生成新的 sk-deekseep- 密钥",
                        "Generate a new sk-deekseep- key"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { rotateLocalApiKey(); }
                }));
        apiCard.addView(divider());
        apiCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "切换 API 协议", "Switch API protocol"),
                UiLanguage.text(this, "在 OpenAI 与 Anthropic 格式之间切换",
                        "Switch between OpenAI and Anthropic formats"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { switchLocalApiProtocol(); }
                }));
        content.addView(apiCard, cardLp());

        content.addView(sectionTitle(UiLanguage.text(this, "公网隧道", "Public tunnel")));
        LinearLayout tunnelCard = card();
        tunnelCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "Cloudflare Tunnel 配置", "Cloudflare Tunnel settings"),
                UiLanguage.text(this, "设置 token、域名、直连地址与传输方式",
                        "Configure token, domains, direct root and transport"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showCloudflareConfig(); }
                }));
        tunnelCard.addView(divider());
        tunnelCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "Cloudflare Tunnel 启停", "Toggle Cloudflare Tunnel"),
                UiLanguage.text(this, "按已保存的配置启动或停止 cloudflared",
                        "Start or stop cloudflared using the saved configuration"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleCloudflareTunnel(); }
                }));
        tunnelCard.addView(divider());
        tunnelCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "Pinggy 隧道启停", "Toggle Pinggy tunnel"),
                UiLanguage.text(this, "通过 SSH 反向隧道暴露本地 API",
                        "Expose the local API through an SSH reverse tunnel"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { togglePinggyTunnel(); }
                }));
        content.addView(tunnelCard, cardLp());

        content.addView(sectionTitle(UiLanguage.text(this, "前台保活", "Foreground keepalive")));
        LinearLayout keepAliveCard = card();
        keepAliveCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "启用前台保活", "Enable foreground keepalive"),
                UiLanguage.text(this, "保持本地 API 与 SSE 流在后台可用",
                        "Keep the local API and SSE streams alive in the background"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        toggleKeepAlive(true);
                    }
                }));
        keepAliveCard.addView(divider());
        keepAliveCard.addView(actionRow(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "停用前台保活", "Disable foreground keepalive"),
                UiLanguage.text(this, "结束后台监听与 CPU 唤醒锁",
                        "Stop background listening and release the CPU wake lock"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        toggleKeepAlive(false);
                    }
                }));
        content.addView(keepAliveCard, cardLp());
    }

    private void startLocalApi() {
        try {
            Bundle result = LocalApiGateway.start(this);
            if (result != null && result.getBoolean("accepted", false)) {
                toast(UiLanguage.text(this, "本地 API 已启动：", "Local API started: ")
                        + result.getString("endpoint", ""));
            } else {
                toast(result == null ? "" : result.getString("error", ""));
            }
        } catch (Throwable error) {
            toast(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void stopLocalApi() {
        try {
            LocalApiKeepAliveService.setRequested(this, false);
            PinggyTunnelManager.p("settings stop", true);
            PublicTunnelManager.setRequested(this, false);
            Bundle result = LocalApiGateway.stop(this);
            toast(result != null && result.getBoolean("accepted", false)
                    ? UiLanguage.text(this, "本地 API 已停止", "Local API stopped")
                    : UiLanguage.text(this, "本地 API 未在运行", "Local API was not running"));
        } catch (Throwable error) {
            toast(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void showLocalApiStatus() {
        StringBuilder body = new StringBuilder();
        try {
            Bundle api = LocalApiGateway.status(this);
            body.append(UiLanguage.text(this, "运行", "Running")).append(": ")
                    .append(api.getBoolean("running", false)).append('\n');
            body.append(UiLanguage.text(this, "端口", "Port")).append(": ")
                    .append(api.getInt("port", 0)).append('\n');
            body.append(UiLanguage.text(this, "协议", "Protocol")).append(": ")
                    .append(api.getString("protocol", "")).append('\n');
            body.append(UiLanguage.text(this, "入口", "Endpoint")).append(": ")
                    .append(api.getString("endpoint", "")).append('\n');
            body.append(UiLanguage.text(this, "密钥指纹", "Key fingerprint")).append(": ")
                    .append(api.getString("key_fingerprint", "")).append('\n');
            body.append(UiLanguage.text(this, "请求", "Requests")).append(": ")
                    .append(api.getLong("requests", 0L)).append('\n');
            body.append(UiLanguage.text(this, "错误", "Errors")).append(": ")
                    .append(api.getLong("errors", 0L)).append('\n');
            body.append(UiLanguage.text(this, "输入 tokens", "Input tokens")).append(": ")
                    .append(api.getLong("tokens_in", 0L)).append('\n');
            body.append(UiLanguage.text(this, "输出 tokens", "Output tokens")).append(": ")
                    .append(api.getLong("tokens_out", 0L)).append('\n');
            body.append(UiLanguage.text(this, "后端", "Backend")).append(": ")
                    .append(api.getString("backend", "")).append('\n');
            String err = api.getString("error", "");
            if (err != null && err.length() > 0) {
                body.append(UiLanguage.text(this, "最后错误", "Last error")).append(": ")
                        .append(err).append('\n');
            }
            Bundle cf = PublicTunnelManager.status(this);
            body.append('\n').append(UiLanguage.text(this, "Cloudflare", "Cloudflare")).append(": ")
                    .append(cf.getString("state", "")).append('\n');
            Bundle py = PinggyTunnelManager.status(this);
            body.append(UiLanguage.text(this, "Pinggy", "Pinggy")).append(": ")
                    .append(py.getString("state", ""));
        } catch (Throwable error) {
            body.append(error.getClass().getSimpleName()).append(": ").append(error.getMessage());
        }
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.text(this, "本地 API 状态", "Local API status"))
                .setMessage(body.toString())
                .setPositiveButton(UiLanguage.text(this, "完成", "Done"), null)
                .show();
    }

    private void rotateLocalApiKey() {
        try {
            String key = LocalApiGateway.rotateApiKey(this);
            new AlertDialog.Builder(this)
                    .setTitle(UiLanguage.text(this, "新的 API Key", "New API key"))
                    .setMessage(key)
                    .setPositiveButton(UiLanguage.text(this, "完成", "Done"), null)
                    .show();
        } catch (Throwable error) {
            toast(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void switchLocalApiProtocol() {
        final String[] labels = new String[]{"OpenAI", "Anthropic"};
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.text(this, "API 协议", "API protocol"))
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            Bundle in = new Bundle();
                            in.putString("protocol", which == 0 ? "openai" : "anthropic");
                            LocalApiGateway.saveSettings(SettingsActivity.this, in);
                            toast(UiLanguage.text(SettingsActivity.this, "协议已切换：",
                                    "Protocol switched: ") + labels[which]);
                        } catch (Throwable error) {
                            toast(error.getClass().getSimpleName() + ": " + error.getMessage());
                        }
                    }
                })
                .setNegativeButton(UiLanguage.text(this, "取消", "Cancel"), null)
                .show();
    }

    private void showCloudflareConfig() {
        try {
            Bundle status = PublicTunnelManager.status(this);
            StringBuilder body = new StringBuilder();
            body.append(UiLanguage.text(this, "状态", "State")).append(": ")
                    .append(status.getString("state", "")).append('\n');
            body.append(UiLanguage.text(this, "已配置 token", "Token configured")).append(": ")
                    .append(status.getBoolean("token_configured", false)).append('\n');
            body.append(UiLanguage.text(this, "域名", "Domains")).append(": ")
                    .append(status.getString("domains", "")).append('\n');
            body.append(UiLanguage.text(this, "直连地址", "Direct root")).append(": ")
                    .append(status.getString("direct_root", "")).append('\n');
            body.append(UiLanguage.text(this, "传输", "Transport")).append(": ")
                    .append(status.getString("transport", "auto")).append('\n');
            body.append(UiLanguage.text(this, "二进制", "Binary")).append(": ")
                    .append(status.getBoolean("binary_available", false)).append('\n');
            String err = status.getString("error", "");
            if (err != null && err.length() > 0) {
                body.append(UiLanguage.text(this, "错误", "Error")).append(": ")
                        .append(err).append('\n');
            }
            String log = status.getString("recent_log", "");
            if (log != null && log.length() > 0) {
                body.append('\n').append(log);
            }
            new AlertDialog.Builder(this)
                    .setTitle(UiLanguage.text(this, "Cloudflare Tunnel", "Cloudflare Tunnel"))
                    .setMessage(body.toString())
                    .setPositiveButton(UiLanguage.text(this, "完成", "Done"), null)
                    .show();
        } catch (Throwable error) {
            toast(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void toggleCloudflareTunnel() {
        try {
            Bundle status = PublicTunnelManager.status(this);
            boolean requested = status.getBoolean("requested", false);
            Bundle result = PublicTunnelManager.setRequested(this, !requested);
            if (result != null && result.getBoolean("accepted", false)) {
                toast(requested
                        ? UiLanguage.text(this, "Cloudflare Tunnel 已停止", "Cloudflare Tunnel stopped")
                        : UiLanguage.text(this, "Cloudflare Tunnel 已启动", "Cloudflare Tunnel started"));
            } else {
                toast(result == null ? "" : result.getString("error", ""));
            }
        } catch (Throwable error) {
            toast(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void togglePinggyTunnel() {
        try {
            Bundle status = PinggyTunnelManager.status(this);
            boolean requested = status.getBoolean("requested", false);
            Bundle result = PinggyTunnelManager.setRequested(this, !requested);
            if (result != null && result.getBoolean("accepted", false)) {
                toast(requested
                        ? UiLanguage.text(this, "Pinggy 隧道已停止", "Pinggy tunnel stopped")
                        : UiLanguage.text(this, "Pinggy 隧道已启动", "Pinggy tunnel started"));
            } else {
                toast(result == null ? "" : result.getString("error", ""));
            }
        } catch (Throwable error) {
            toast(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void toggleKeepAlive(boolean enable) {
        try {
            boolean accepted = LocalApiKeepAliveService.setRequested(this, enable);
            toast(accepted
                    ? (enable
                    ? UiLanguage.text(this, "前台保活已启用", "Foreground keepalive enabled")
                    : UiLanguage.text(this, "前台保活已停用", "Foreground keepalive disabled"))
                    : UiLanguage.text(this, "前台保活操作失败", "Foreground keepalive request failed"));
        } catch (Throwable error) {
            toast(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private View bottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(26), dp(8), dp(26), dp(10));
        nav.setBackgroundColor(card);
        nav.addView(navItem(ModuleGlyphView.HOME,
                UiLanguage.text(this, "首页", "Home"), 0), new LinearLayout.LayoutParams(0, dp(58), 1f));
        nav.addView(navItem(ModuleGlyphView.SETTINGS,
                UiLanguage.text(this, "设置", "Settings"), 1), new LinearLayout.LayoutParams(0, dp(58), 1f));
        return nav;
    }

    private View navItem(int icon, String label, final int target) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        ModuleGlyphView glyph = new ModuleGlyphView(this, icon, target == page ? accent : muted);
        item.addView(glyph, new LinearLayout.LayoutParams(dp(25), dp(25)));
        TextView name = text(label, 11, target == page ? accent : muted, Typeface.DEFAULT);
        item.addView(name);
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(target); renderShell(); }
        });
        return item;
    }

    private View actionCard(int icon, String title, String subtitle, View.OnClickListener listener) {
        LinearLayout shell = card();
        shell.addView(actionRow(icon, title, subtitle, listener));
        LinearLayout.LayoutParams lp = cardLp();
        lp.topMargin = dp(10);
        shell.setLayoutParams(lp);
        return shell;
    }

    private View actionRow(int icon, String title, String subtitle, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(14), dp(15));
        ModuleGlyphView glyph = new ModuleGlyphView(this, icon, accent);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(27), dp(27)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);
        labels.addView(text(title, 16, text, Typeface.create("sans-serif-medium", 0)));
        labels.addView(text(subtitle, 12, muted, Typeface.DEFAULT));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = text("›", 25, muted, Typeface.DEFAULT);
        row.addView(arrow);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(listener);
        return row;
    }

    private LinearLayout infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        row.addView(text(label, 15, muted, Typeface.DEFAULT), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView data = text(value, 14, text, Typeface.DEFAULT);
        data.setGravity(Gravity.END);
        row.addView(data);
        return row;
    }

    private void showSponsor() {
        final String sponsor = "https://afdian.com/a/lllucccian";
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.text(this, "赞助开发", "Sponsor development"))
                .setItems(new String[]{
                                UiLanguage.text(this, "通过爱发电赞助", "Sponsor via Afdian"),
                                UiLanguage.text(this, "通过微信赞助", "Sponsor via WeChat")},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    android.content.DialogInterface dialog, int which) {
                                if (which == 0) {
                                    try {
                                        startActivity(new Intent(Intent.ACTION_VIEW,
                                                Uri.parse(sponsor)));
                                    } catch (Throwable error) {
                                        Toast.makeText(SettingsActivity.this, sponsor,
                                                Toast.LENGTH_LONG).show();
                                    }
                                } else {
                                    showWechatSponsorCode();
                                }
                            }
                        })
                .setNegativeButton(UiLanguage.text(this, "取消", "Cancel"), null)
                .show();
    }

    private void showWechatSponsorCode() {
        int id = getResources().getIdentifier("sponsor_qr", "drawable", getPackageName());
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(12), dp(4), dp(12), 0);
        if (id != 0) image.setImageResource(id);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(UiLanguage.text(this, "微信赞赏码", "WeChat donation code"))
                .setMessage(UiLanguage.text(this, "感谢支持持续维护与适配。", "Thank you for supporting ongoing maintenance."))
                .setView(image)
                .setPositiveButton(UiLanguage.text(this, "完成", "Done"), null)
                .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    private void chooseExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "deekseep-config-" + BuildInfo.MODULE_VERSION + ".json");
        startActivityForResult(intent, REQ_EXPORT);
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        startActivityForResult(intent, REQ_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) requestExport(uri);
        else if (requestCode == REQ_IMPORT) requestImport(uri);
    }

    private void requestExport(final Uri uri) {
        ResultReceiver reply = new ResultReceiver(handler) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (code != ModuleConfigBridge.RESULT_OK) {
                    bridgeError(data); return;
                }
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri, "wt");
                    out.write(data.getString(ModuleConfigBridge.EXTRA_JSON, "").getBytes(StandardCharsets.UTF_8));
                    out.close();
                    toast(UiLanguage.text(SettingsActivity.this, "配置已导出", "Configuration exported"));
                } catch (Throwable error) { toast(error.getMessage()); }
            }
        };
        sendBroadcast(ModuleConfigBridge.request(ModuleConfigBridge.MODE_EXPORT, null, reply));
        toast(UiLanguage.text(this, "正在读取 DeepSeek 配置", "Reading DeepSeek configuration"));
    }

    private void requestImport(Uri uri) {
        try {
            InputStream input = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0 && output.size() <= 1024 * 1024) output.write(buffer, 0, count);
            input.close();
            final String json = new String(output.toByteArray(), StandardCharsets.UTF_8);
            ResultReceiver reply = new ResultReceiver(handler) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    if (code != ModuleConfigBridge.RESULT_OK) { bridgeError(data); return; }
                    toast(UiLanguage.text(SettingsActivity.this,
                            "配置已导入，重新打开 DeepSeek 后生效", "Configuration imported; reopen DeepSeek to apply"));
                }
            };
            sendBroadcast(ModuleConfigBridge.request(ModuleConfigBridge.MODE_IMPORT, json, reply));
        } catch (Throwable error) { toast(error.getMessage()); }
    }

    private void bridgeError(Bundle data) {
        String detail = data == null ? "" : data.getString("error", "");
        toast(UiLanguage.text(this, "请先启动一次 DeepSeek，再重试", "Launch DeepSeek once, then try again")
                + (detail.length() == 0 ? "" : "\n" + detail));
    }

    private void refreshActivationState() {
        if (activationTitle == null || isFinishing()) return;
        boolean framework = XposedActivationProvider.isFrameworkConnected();
        boolean target = XposedActivationProvider.isTargetRecentlyActive(this);
        if (framework || target) {
            activationTitle.setText(UiLanguage.text(this, "已激活", "Activated"));
            activationDetail.setText(target ? apiDisplayName() + " · DeepSeek " + deepSeekVersion()
                    : apiDisplayName() + " · " + UiLanguage.text(this, "启动 DeepSeek 生效", "Ready — launch DeepSeek"));
            activationMark.setText("✓");
        } else if (isLegacyBuild()) {
            activationTitle.setText(UiLanguage.text(this, "待验证", "Waiting for verification"));
            activationDetail.setText(apiDisplayName() + " · " + UiLanguage.text(this, "启动 DeepSeek 后确认", "Launch DeepSeek to confirm"));
            activationMark.setText("—");
        } else {
            activationTitle.setText(UiLanguage.text(this, "未激活", "Not activated"));
            activationDetail.setText(apiDisplayName() + " · " + UiLanguage.text(this, "Xposed 框架未连接", "Xposed framework not connected"));
            activationMark.setText("×");
        }
    }

    private String deepSeekVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            String name = info.versionName == null ? "" : info.versionName;
            return code > 0 ? name + " (" + code + ")" : name;
        } catch (Throwable ignored) { return UiLanguage.text(this, "未安装", "Not installed"); }
    }

    private String apiDisplayName() {
        String value = BuildInfo.API_VERSION == null ? "" : BuildInfo.API_VERSION.trim();
        return (isLegacyBuild() ? "Xposed API " : "API ") + value;
    }

    private static boolean isLegacyBuild() {
        return BuildInfo.API_VERSION != null && (BuildInfo.API_VERSION.contains("legacy")
                || BuildInfo.API_VERSION.contains("universal"));
    }

    private void configureWindow() {
        Window window = getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(bg);
        window.setNavigationBarColor(card);
        int flags = window.getDecorView().getSystemUiVisibility();
        if (!renderedDark && Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!renderedDark && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private boolean isDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private LinearLayout card() {
        LinearLayout out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        out.setBackground(rounded(card, 18));
        out.setClipToOutline(true);
        out.setElevation(dp(1));
        return out;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        return lp;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 13, muted, Typeface.create("sans-serif-medium", 0));
        title.setPadding(dp(6), dp(10), 0, dp(8));
        return title;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(.7f));
        lp.leftMargin = dp(56);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView text(String value, int sp, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Throwable error) { toast(error.getMessage()); }
    }

    private void toast(String value) { Toast.makeText(this, value == null ? "" : value, Toast.LENGTH_LONG).show(); }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }

    private void ensureStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) return;
            if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                    && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
        } catch (Throwable ignored) {}
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }
}
