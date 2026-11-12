package com.dsmod.deepseek;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Status page: probes the in-host local API and offers quick actions. */
public class SettingsActivity extends Activity {
    private static final String HOST_PACKAGE = "com.deepseek.chat";

    private TextView statusView;
    private volatile boolean probing = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            probe();
            handler.postDelayed(this, 3000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("DeepSeek 本地 API");
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(14f);
        statusView.setPadding(0, pad / 2, 0, pad / 2);
        statusView.setText("检测中…");
        root.addView(statusView);

        addButton(root, "打开 /admin 控制台", new Runnable() {
            @Override
            public void run() {
                openUrl("http://127.0.0.1:" + Config.port + "/admin");
            }
        });

        addButton(root, "打开 DeepSeek", new Runnable() {
            @Override
            public void run() {
                openHost();
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

        TextView help = new TextView(this);
        help.setTextSize(12f);
        help.setPadding(0, pad, 0, 0);
        help.setTextIsSelectable(true);
        help.setText("监听地址：0.0.0.0:" + Config.port + "（所有网卡）\n"
                + "本机客户端：\n"
                + "  OpenAI    http://127.0.0.1:" + Config.port + "/v1\n"
                + "  Anthropic http://127.0.0.1:" + Config.port + "\n"
                + "局域网设备：http://<手机 IP>:" + Config.port + "\n\n"
                + "配置与号池：\n"
                + "  http://127.0.0.1:" + Config.port + "/admin\n\n"
                + "账号池：\n"
                + "  · 无需在本 App 登录，自动读取 DeepSeek 的登录态\n"
                + "  · 在 DeepSeek 内切换账号，5 秒内自动加入号池\n"
                + "  · 在 /admin 里可添加 / 删除 / 验证 token\n\n"
                + "注意：\n"
                + "  1. 首次使用请打开一次 DeepSeek 并登录。\n"
                + "  2. 修改端口 / API key 后需重启 DeepSeek 生效。\n"
                + "  3. 本页状态通过 localhost 实时探测。\n"
                + "  4. 日志：files/dsmod_api.log");
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void addButton(LinearLayout root, String text, final Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                try {
                    action.run();
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, String.valueOf(t), Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(button);
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
                String text;
                try {
                    text = fetchHealth();
                } finally {
                    probing = false;
                }
                final String finalText = text;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (statusView != null) statusView.setText(finalText);
                    }
                });
            }
        }, "dsmod-health");
        thread.setDaemon(true);
        thread.start();
    }

    private String fetchHealth() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + Config.port + "/healthz");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                String body = out.toString("UTF-8");
                if (body.length() > 240) body = body.substring(0, 240) + "...";
                return "● 运行中（HTTP 200）\n" + body;
            }
            return "● 进程已启动，接口返回 HTTP " + code;
        } catch (Throwable t) {
            return "○ 未运行\n请确认：LSPosed 已启用本模块、作用域勾选 com.deepseek.chat，并打开过 DeepSeek 完成登录。";
        } finally {
            if (conn != null) conn.disconnect();
        }
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

    private void openHost() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(HOST_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(this, "未安装 DeepSeek", Toast.LENGTH_SHORT).show();
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
