package com.dsmod.deepseek;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Dark, minimal status console for the module (parses /healthz into cards). */
public class SettingsActivity extends Activity {
    private static final String HOST_PACKAGE = "com.deepseek.chat";

    private static final int BG = 0xFF060912;
    private static final int PANEL = 0xE611192A;
    private static final int LINE = 0x2A7AB6FF;
    private static final int TEXT = 0xFFE8F2FF;
    private static final int MUTED = 0xFF8194B6;
    private static final int GREEN = 0xFF34D399;
    private static final int RED = 0xFFF87171;

    private TextView dot;
    private TextView state;
    private TextView mPort;
    private TextView mVer;
    private TextView mReq;
    private TextView mErr;
    private TextView mAcc;
    private TextView mUp;
    private TextView errorText;

    private volatile boolean probing = false;
    private volatile String lastFetchError = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            probe();
            handler.postDelayed(this, 3000L);
        }
    };

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("DeepSeek 本地 API");
        title.setTextSize(20);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(13);
        dot.setTextColor(MUTED);
        header.addView(dot);
        state = new TextView(this);
        state.setText(" 检测中");
        state.setTextSize(13);
        state.setTextColor(MUTED);
        header.addView(state);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("监听 0.0.0.0:" + Config.port + " · 本机 http://127.0.0.1:" + Config.port);
        sub.setTextSize(12);
        sub.setTextColor(MUTED);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        LinearLayout row1 = metricsRow(root);
        mPort = addCell(row1, "端口");
        mVer = addCell(row1, "版本");
        LinearLayout row2 = metricsRow(root);
        mReq = addCell(row2, "请求数");
        mErr = addCell(row2, "异常数");
        LinearLayout row3 = metricsRow(root);
        mAcc = addCell(row3, "在线账号");
        mUp = addCell(row3, "运行时长");

        errorText = new TextView(this);
        errorText.setTextSize(12);
        errorText.setTextColor(RED);
        errorText.setPadding(dp(2), dp(6), dp(2), 0);
        errorText.setVisibility(View.GONE);
        root.addView(errorText);

        addButton(root, "打开 /admin 控制台", new Runnable() {
            @Override
            public void run() {
                openUrl("http://127.0.0.1:" + Config.port + "/admin");
            }
        });
        addButton(root, "打开 DeepSeek", new Runnable() {
            @Override
            public void run() {
                openPackage(HOST_PACKAGE);
            }
        });
        addButton(root, "打开 LSPosed", new Runnable() {
            @Override
            public void run() {
                openPackage("org.lsposed.manager");
            }
        });
        addButton(root, "复制 OpenAI base_url", new Runnable() {
            @Override
            public void run() {
                copy("http://127.0.0.1:" + Config.port + "/v1");
            }
        });
        addButton(root, "复制 Anthropic base_url", new Runnable() {
            @Override
            public void run() {
                copy("http://127.0.0.1:" + Config.port);
            }
        });

        TextView info = new TextView(this);
        info.setTextSize(12);
        info.setTextColor(MUTED);
        info.setTextIsSelectable(true);
        info.setPadding(0, dp(18), 0, 0);
        info.setText("账号池\n"
                + "· 无需在本 App 登录，自动读取 DeepSeek 登录态\n"
                + "· 在 DeepSeek 内切换账号，5 秒内自动入池\n"
                + "· 可在 /admin 添加 / 删除 / 验证 token\n\n"
                + "提示\n"
                + "· 修改端口或 API key 后需重启 DeepSeek 生效\n"
                + "· DeepSeek 进程被系统清理时 API 会停止，请关闭电池优化");
        root.addView(info);

        setContentView(scroll);
    }

    private LinearLayout metricsRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(row, lp);
        return row;
    }

    private TextView addCell(LinearLayout row, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), LINE);
        cell.setBackground(bg);
        cell.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(10);
        row.addView(cell, lp);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11);
        l.setTextColor(MUTED);
        cell.addView(l);
        TextView v = new TextView(this);
        v.setText("—");
        v.setTextSize(18);
        v.setTextColor(TEXT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        cell.addView(v);
        return v;
    }

    private void addButton(LinearLayout parent, String text, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x3322D3EE, 0x338B5CF6});
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), LINE);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(10);
        parent.addView(b, lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    action.run();
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, String.valueOf(t), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    private void probe() {
        if (probing) return;
        probing = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final String body = fetch();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        applyHealth(body);
                        probing = false;
                    }
                });
            }
        }, "dsmod-health");
        thread.setDaemon(true);
        thread.start();
    }

    private String fetch() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + Config.port + "/healthz");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                lastFetchError = "HTTP " + code;
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            lastFetchError = "";
            return out.toString("UTF-8");
        } catch (Throwable t) {
            lastFetchError = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void applyHealth(String body) {
        try {
            JSONObject o = new JSONObject(body);
            boolean ok = "ok".equals(o.optString("status"));
            dot.setTextColor(ok ? GREEN : RED);
            state.setText(ok ? " 运行中" : " 已停止");
            state.setTextColor(ok ? GREEN : RED);
            mPort.setText(String.valueOf(o.optInt("port", Config.port)));
            mVer.setText(o.optString("version", "-"));
            mReq.setText(String.valueOf(o.optInt("requests")));
            mErr.setText(String.valueOf(o.optInt("errors")));
            mAcc.setText(o.optInt("accounts_active") + " / " + o.optInt("accounts"));
            mUp.setText(fmtUp(o.optLong("uptime_sec")));
            String tokenError = o.optString("token_error", "");
            String lastError = o.optString("last_error", "");
            String err = !tokenError.isEmpty() ? "Token: " + tokenError
                    : (!lastError.isEmpty() ? "最近错误: " + lastError : "");
            if (!err.isEmpty()) {
                errorText.setText(err);
                errorText.setVisibility(View.VISIBLE);
            } else {
                errorText.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            dot.setTextColor(RED);
            state.setText(" 未运行");
            state.setTextColor(RED);
            mPort.setText(String.valueOf(Config.port));
            mVer.setText("-");
            mReq.setText("-");
            mErr.setText("-");
            mAcc.setText("-");
            mUp.setText("-");
            errorText.setText("无法连接 127.0.0.1:" + Config.port
                    + (lastFetchError.isEmpty() ? "" : "（" + lastFetchError + "）")
                    + "\n请确认：LSPosed 已启用本模块、作用域勾选 com.deepseek.chat、打开 DeepSeek 登录并保持后台"
                    + "\n日志：/admin 可用时点「导出日志」；不可用时用 root 文件管理器查看"
                    + " /data/data/com.deepseek.chat/files/dsmod_log.txt");
            errorText.setVisibility(View.VISIBLE);
        }
    }

    private String fmtUp(long sec) {
        if (sec <= 0) return "-";
        long d = sec / 86400;
        long h = (sec % 86400) / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("天 ");
        if (h > 0) sb.append(h).append("时 ");
        sb.append(m).append("分 ").append(s).append("秒");
        return sb.toString();
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("base_url", text));
            Toast.makeText(this, "已复制: " + text, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void openPackage(String packageName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(this, "未安装 " + packageName, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }
}
