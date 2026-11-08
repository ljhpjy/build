package com.fuckds.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 参考 IDAdroid 的原生管理界面（五页签）：
 *   环境：导入 rootfs.tar.xz（SAF + 进度条）
 *   服务：启停 / 状态 / 端口 / 网页控制台
 *   账号：token 号池增删校验（/admin/api/accounts*）
 *   配置：ENGINE / API_KEY / CONCURRENCY 读写 + 重启（/admin/api/config|restart）
 *   日志：服务日志（API）与本地启动日志
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_ROOTFS = 42;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private View panelEnv, panelSvc, panelAcct, panelCfg, panelLog;
    private Button tabEnv, tabSvc, tabAcct, tabCfg, tabLog;
    private TextView envInfo, svcInfo, acctList, cfgInfo, logView;
    private TextView importPct;
    private ProgressBar importProgress;
    private EditText acctInput, acctIndex, cfgEngine, cfgApiKey, cfgConcurrency;
    private EditText cfgHost, cfgPort;

    private int currentPort = 18787;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        panelEnv = findViewById(R.id.panel_env);
        panelSvc = findViewById(R.id.panel_svc);
        panelAcct = findViewById(R.id.panel_acct);
        panelCfg = findViewById(R.id.panel_cfg);
        panelLog = findViewById(R.id.panel_log);

        tabEnv = findViewById(R.id.tab_env);
        tabSvc = findViewById(R.id.tab_svc);
        tabAcct = findViewById(R.id.tab_acct);
        tabCfg = findViewById(R.id.tab_cfg);
        tabLog = findViewById(R.id.tab_log);

        envInfo = findViewById(R.id.env_info);
        envInfo.setMovementMethod(new ScrollingMovementMethod());
        importPct = findViewById(R.id.import_pct);
        importProgress = findViewById(R.id.import_progress);
        importProgress.setMax(1000);
        svcInfo = findViewById(R.id.svc_info);
        svcInfo.setMovementMethod(new ScrollingMovementMethod());
        acctList = findViewById(R.id.acct_list);
        acctList.setMovementMethod(new ScrollingMovementMethod());
        cfgInfo = findViewById(R.id.cfg_info);
        cfgInfo.setMovementMethod(new ScrollingMovementMethod());
        logView = findViewById(R.id.log_view);
        logView.setMovementMethod(new ScrollingMovementMethod());

        acctInput = findViewById(R.id.acct_input);
        acctIndex = findViewById(R.id.acct_index);
        cfgEngine = findViewById(R.id.cfg_engine);
        cfgApiKey = findViewById(R.id.cfg_apikey);
        cfgConcurrency = findViewById(R.id.cfg_concurrency);
        cfgHost = findViewById(R.id.cfg_host);
        cfgPort = findViewById(R.id.cfg_port);

        tabEnv.setOnClickListener(v -> selectTab(0));
        tabSvc.setOnClickListener(v -> selectTab(1));
        tabAcct.setOnClickListener(v -> selectTab(2));
        tabCfg.setOnClickListener(v -> selectTab(3));
        tabLog.setOnClickListener(v -> selectTab(4));

        findViewById(R.id.btn_import).setOnClickListener(v -> pickRootfs());
        findViewById(R.id.btn_start).setOnClickListener(v -> startBoot());
        findViewById(R.id.btn_stop).setOnClickListener(v -> stopBoot());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refreshSvc());
        findViewById(R.id.btn_open).setOnClickListener(v -> openConsole());
        findViewById(R.id.btn_copy).setOnClickListener(v -> copyApiAddress());

        findViewById(R.id.acct_replace).setOnClickListener(v -> saveAccounts(false));
        findViewById(R.id.acct_append).setOnClickListener(v -> saveAccounts(true));
        findViewById(R.id.acct_verify).setOnClickListener(v -> verifyAccounts());
        findViewById(R.id.acct_remove).setOnClickListener(v -> removeAccount());
        findViewById(R.id.acct_refresh).setOnClickListener(v -> refreshAccts());

        findViewById(R.id.cfg_load).setOnClickListener(v -> loadConfig());
        findViewById(R.id.cfg_save).setOnClickListener(v -> saveConfig());
        findViewById(R.id.cfg_restart).setOnClickListener(v -> restartServer());

        findViewById(R.id.log_server).setOnClickListener(v -> loadServerLog());
        findViewById(R.id.log_local).setOnClickListener(v -> loadLocalLog());

        selectTab(0);
        refreshEnv();
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.postDelayed(poll, 500);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /* ------------------------- 页签与轮询 ------------------------- */

    private void selectTab(int i) {
        panelEnv.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        panelSvc.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        panelAcct.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        panelCfg.setVisibility(i == 3 ? View.VISIBLE : View.GONE);
        panelLog.setVisibility(i == 4 ? View.VISIBLE : View.GONE);
        if (i == 0) refreshEnv();
        if (i == 1) refreshSvc();
        if (i == 2) refreshAccts();
        if (i == 3) loadConfig();
        if (i == 4) loadServerLog();
    }

    private String lastStatus = "";

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            String s = prefs().getString("status", "");
            if (s.startsWith("服务运行中")) {
                currentPort = prefs().getInt("port", 18787);
            }
            if (!s.equals(lastStatus)) {
                lastStatus = s;
                if (panelSvc.getVisibility() == View.VISIBLE) {
                    svcInfo.setText(s);
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    /* ------------------------- 环境页 ------------------------- */

    private void refreshEnv() {
        File base = new File(getFilesDir(), "linux");
        File rootfs = new File(base, "rootfs");
        File ready = new File(base, ".rootfs-ready");
        StringBuilder sb = new StringBuilder();
        sb.append("fuckds 安卓版（IDAdroid 架构）\n\n");
        sb.append("rootfs 目录：").append(rootfs.getAbsolutePath()).append('\n');
        sb.append("已导入：").append(ready.exists() ? "是 ✓" : "否 ✗").append('\n');
        if (rootfs.isDirectory()) {
            sb.append("目录大小：").append(humanSize(dirSize(rootfs))).append('\n');
        }
        sb.append('\n').append(ready.exists()
                ? "环境已就绪，去「服务」页启动。"
                : "把 fuckds-rootfs.tar.xz 放到手机任意目录后点下方按钮导入。");
        envInfo.setText(sb.toString());
    }

    private void pickRootfs() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_PICK_ROOTFS);
        } catch (Exception e) {
            toast("无法打开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ROOTFS || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        setImporting(true);
        new Thread(() -> {
            try {
                File rootfs = new File(getFilesDir(), "linux/rootfs");
                Importer.importRootfs(this, uri, rootfs, (pct, name) -> runOnUiThread(() -> {
                    if (pct >= 0f) {
                        importProgress.setIndeterminate(false);
                        importProgress.setProgress(Math.max(0, Math.min(1000, (int) (pct * 1000f))));
                        importPct.setText(String.format(Locale.US, "%.1f%%", pct * 100f));
                        envInfo.setText(String.format(Locale.US,
                                "正在解压 rootfs… %.1f%%\n%s", pct * 100f, name));
                    } else {
                        importProgress.setIndeterminate(true);
                        importPct.setText("计算中…");
                        envInfo.setText("正在解压 rootfs…\n" + name);
                    }
                }));
                new File(getFilesDir(), "linux/.rootfs-ready").createNewFile();
                prefs().edit().putString("status", "rootfs 导入完成，去「服务」页启动").apply();
                runOnUiThread(() -> {
                    importProgress.setIndeterminate(false);
                    importProgress.setProgress(1000);
                    importPct.setText("100.0%");
                });
                runOnUiThread(() -> {
                    setImporting(false);
                    refreshEnv();
                    toast("rootfs 导入完成");
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                prefs().edit().putString("status", "导入失败：" + msg).apply();
                runOnUiThread(() -> {
                    setImporting(false);
                    envInfo.setText(envInfo.getText() + "\n\n❌ 导入失败：" + msg
                            + "\n请检查剩余存储空间，或重新选择文件重试。");
                    toast("导入失败：" + msg);
                });
            }
        }).start();
    }

    private void setImporting(boolean busy) {
        findViewById(R.id.btn_import).setEnabled(!busy);
        if (busy) {
            importProgress.setProgress(0);
            importPct.setText("0.0%");
        }
    }

    /* ------------------------- 服务页 ------------------------- */

    private void startBoot() {
        Intent it = new Intent(this, BootService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(it);
        } else {
            startService(it);
        }
    }

    private void stopBoot() {
        startService(new Intent(this, BootService.class).setAction(BootService.ACTION_STOP));
    }

    private void refreshSvc() {
        String status = prefs().getString("status", "尚未启动");
        svcInfo.setText(status);
        fetch("GET", "/admin/api/overview", null, (ok, body) -> {
            if (ok) {
                svcInfo.setText(body + "\n\n" + status);
            }
        });
    }

    private void openConsole() {
        int port = prefs().getInt("port", currentPort);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://127.0.0.1:" + port + "/admin/")));
        } catch (Exception e) {
            toast("没有可用的浏览器：" + e.getMessage());
        }
    }

    private void copyApiAddress() {
        int port = prefs().getInt("port", currentPort);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("fuckds-api", "http://127.0.0.1:" + port + "/v1"));
        }
        toast("已复制 OpenAI 地址：http://127.0.0.1:" + port + "/v1\n"
                + "Anthropic 客户端用：http://127.0.0.1:" + port + "\n"
                + "（手机本机使用；局域网设备访问请先在配置页改 HOST 为 0.0.0.0）");
    }

    /** 保存类操作成功后提示立即重启服务（token/配置都是重启后生效） */
    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle("已保存")
                .setMessage("token / 配置是重启服务后生效的，现在重启吗？")
                .setPositiveButton("立即重启", (d, w) -> {
                    fetch("POST", "/admin/api/restart", null, (ok, resp) -> {
                        toast(ok ? "已发送重启请求，稍等片刻…" : "重启失败：" + resp);
                        selectTab(1);
                        refreshSvc();
                    });
                })
                .setNegativeButton("稍后", (d, w) -> toast("已保存，记得去「配置」页重启服务"))
                .show();
    }

    /* ------------------------- 账号页 ------------------------- */

    private String[] inputTokens() {
        String raw = acctInput.getText().toString().trim();
        if (raw.isEmpty()) {
            return new String[0];
        }
        return raw.split("[\\s,;]+");
    }

    private void saveAccounts(boolean append) {
        String[] tokens = inputTokens();
        if (tokens.length == 0) {
            toast("先粘贴至少一个 userToken");
            return;
        }
        String body = Api.tokensJson(tokens).replaceFirst("}$",
                ",\"mode\":\"" + (append ? "append" : "replace") + "\"}");
        fetch("POST", "/admin/api/accounts", body, (ok, resp) -> {
            acctList.setText(ok ? "操作成功：\n" + resp : "失败：" + resp);
            if (ok) {
                refreshAccts();
                confirmRestart();
            }
        });
    }

    private void verifyAccounts() {
        String[] tokens = inputTokens();
        if (tokens.length == 0) {
            toast("先粘贴要校验的 userToken");
            return;
        }
        fetch("POST", "/admin/api/accounts/verify", Api.tokensJson(tokens), (ok, resp) ->
                acctList.setText(ok ? "校验结果：\n" + resp : "失败：" + resp));
    }

    private void removeAccount() {
        String idx = acctIndex.getText().toString().trim();
        if (idx.isEmpty()) {
            toast("填写要删除的序号");
            return;
        }
        fetch("POST", "/admin/api/accounts/remove",
                "{\"index\":" + idx + "}", (ok, resp) -> {
                    acctList.setText(ok ? "已删除：\n" + resp : "失败：" + resp);
                    if (ok) {
                        refreshAccts();
                        confirmRestart();
                    }
                });
    }

    private void refreshAccts() {
        fetch("GET", "/admin/api/accounts", null, (ok, body) ->
                acctList.setText(ok ? "号池：\n" + body : "服务未就绪：" + body));
    }

    /* ------------------------- 配置页 ------------------------- */

    private void loadConfig() {
        fetch("GET", "/admin/api/config", null, (ok, body) -> {
            if (!ok) {
                cfgInfo.setText("服务未就绪：" + body);
                return;
            }
            cfgEngine.setText(extractJson(body, "ENGINE"));
            cfgApiKey.setText(extractJson(body, "API_KEY"));
            cfgConcurrency.setText(extractJson(body, "CONCURRENCY"));
            cfgHost.setText(extractJson(body, "HOST"));
            cfgPort.setText(extractJson(body, "PORT"));
            cfgInfo.setText("当前 .env 配置已加载（可修改后保存）。\n"
                    + "说明：PORT 留空=自动模式（18787 起，冲突自动随机换）；\n"
                    + "填了端口=固定端口（不自动换，被占用会报错）；\n"
                    + "HOST 改 0.0.0.0 可让局域网其它设备访问。");
        });
    }

    private void saveConfig() {
        StringBuilder sb = new StringBuilder("{\"values\":{");
        sb.append("\"ENGINE\":\"").append(Api.jsonEscape(cfgEngine.getText().toString().trim())).append("\",");
        sb.append("\"HOST\":\"").append(Api.jsonEscape(cfgHost.getText().toString().trim())).append("\",");
        sb.append("\"PORT\":\"").append(Api.jsonEscape(cfgPort.getText().toString().trim())).append("\",");
        sb.append("\"API_KEY\":\"").append(Api.jsonEscape(cfgApiKey.getText().toString().trim())).append("\",");
        sb.append("\"CONCURRENCY\":\"").append(Api.jsonEscape(cfgConcurrency.getText().toString().trim())).append("\"");
        sb.append("}}");
        final String portText = cfgPort.getText().toString().trim();
        fetch("PUT", "/admin/api/config", sb.toString(), (ok, resp) -> {
            cfgInfo.setText(ok ? "已保存：" + resp + "\n（需要重启服务生效）" : "失败：" + resp);
            if (ok) {
                // 同步本地端口偏好：手动端口写 port_manual，并更新界面按钮使用的端口
                SharedPreferences.Editor ed = prefs().edit();
                if (!portText.isEmpty()) {
                    try {
                        int p = Integer.parseInt(portText);
                        if (p > 0 && p < 65536) {
                            ed.putInt("port", p).putInt("port_manual", p);
                            currentPort = p;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                } else {
                    ed.putInt("port", 18787).putInt("port_manual", 0);
                    currentPort = 18787;
                }
                ed.apply();
                confirmRestart();
            }
        });
    }

    private void restartServer() {
        fetch("POST", "/admin/api/restart", null, (ok, resp) ->
                cfgInfo.setText(ok ? "重启中：" + resp : "失败：" + resp));
    }

    /* ------------------------- 日志页 ------------------------- */

    private void loadServerLog() {
        fetch("GET", "/admin/api/logs?lines=300", null, (ok, body) ->
                logView.setText(ok ? body : "服务未就绪：" + body));
    }

    private void loadLocalLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== logs/container.log（proot 进程） =====\n")
                .append(tailFile(new File(getFilesDir(), "linux/logs/container.log"), 300))
                .append("\n===== rootfs/opt/fuckds/logs/node.log（服务） =====\n")
                .append(tailFile(new File(getFilesDir(), "linux/rootfs/opt/fuckds/logs/node.log"), 300));
        logView.setText(sb.toString());
    }

    /* ------------------------- 工具 ------------------------- */

    private void fetch(String method, String path, String json, Callback cb) {
        new Thread(() -> {
            try {
                int port = prefs().getInt("port", currentPort);
                String body = Api.http(method, "http://127.0.0.1:" + port + path, json);
                final String b = body;
                runOnUiThread(() -> cb.onResult(true, b));
            } catch (Exception e) {
                final String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> cb.onResult(false, m));
            }
        }).start();
    }

    private interface Callback {
        void onResult(boolean ok, String body);
    }

    /** 从 {"values":{"KEY":"VALUE",...}} 里取配置值（必须锚定 values 段，避免误匹配 schema 里的同名 key） */
    private static String extractJson(String json, String key) {
        int vi = json.indexOf("\"values\"");
        String sub = vi >= 0 ? json.substring(vi) : json;
        int i = sub.indexOf('"' + key + '"');
        if (i < 0) {
            return "";
        }
        int v = sub.indexOf('"', i + key.length() + 2);
        if (v < 0) {
            return "";
        }
        int e = sub.indexOf('"', v + 1);
        if (e < 0) {
            return "";
        }
        return sub.substring(v + 1, e)
                .replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    private static String tailFile(File f, int maxLines) {
        if (f == null || !f.isFile()) {
            return "（文件不存在：" + f + "）";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                count++;
                if (count > maxLines) {
                    int nl = sb.indexOf("\n");
                    if (nl >= 0) {
                        sb.delete(0, nl + 1);
                    }
                }
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            sb.append("（读取失败：").append(e.getMessage()).append("）\n");
        }
        return sb.toString();
    }

    private static long dirSize(File dir) {
        long total = 0;
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        for (File k : kids) {
            if (k.isFile()) {
                total += k.length();
            } else if (k.isDirectory()) {
                // 符号链接目录不递归（lib -> usr/lib 等会重复计数甚至成环）
                boolean sym = false;
                try {
                    sym = !k.getAbsolutePath().equals(k.getCanonicalPath());
                } catch (Exception ignored) {
                }
                if (!sym) {
                    total += dirSize(k);
                }
            }
        }
        return total;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1048576f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("fuckds", 0);
    }
}
