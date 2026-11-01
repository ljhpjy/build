# Deekseep 1.7.5 源码恢复说明

基线：`project/Deekseep-1.7.4-fix.zip`（旧版源码，188 文件 / 59 目录）
目标：`project/1.7.5.apk`（versionCode 34，classes 2941，177 个 `com.dsmod.probe` 类）

## 恢复方式

以旧版工程为基线，按 1.7.5 APK 的 dex 反编译结果逐类对齐：

| 类型 | 处理 |
|---|---|
| 具名类新增 | 按 Smali 重建 Java，字段/方法签名与 dex 对齐 |
| 具名类改动 | 在旧版类体内补新增方法，保留原有实现 |
| 混淆短名类 | 未生成桩；重建代码只引用工程内已有类与标准库 |
| 已移除类 | 删除 `com.dsmod.relay.ExpertRelayGate` 及其测试 |

## 当前验证状态（务必先读）

**javac 从未执行过。** 本目录所有结论均来自文本级结构核对（`mt_file_read_text` /
`mt_file_search_text`），不是编译或运行验证。所谓"已恢复"只表示代码已写入且引用关系在
文本上自洽，不代表能编译通过或功能可用。

唯一的验证方式：

```sh
cd project/Deekseep-1.7.5-restored/module-legacy && bash build.sh
cd ../module-universal && bash build.sh
```

## 已恢复文件

### 新增类

- `LocalApiGateway.java` — 本地 OpenAI/Anthropic 兼容网关（协议、HTTP、chat/completions、responses、messages、count_tokens、去重、统计、TLS 监听、断连监视、direct_root 状态）
- `LocalApiKeepAliveService.java` — 前台保活服务（通知、WakeLock、PublicTunnel/Pinggy 联动）
- `LocalApiKeepAliveActivity.java` — `deekseep-module://local-api-keepalive` 跳板
- `PublicTunnelManager.java` — Cloudflare Tunnel（AES-GCM token、cloudflared 进程、80 条环形日志、指数退避）
- `PinggyTunnelManager.java` — Pinggy SSH 反向隧道
- `OpenAiToolBridge.java` — OpenAI/Anthropic 工具调用桥
- `OmniRouteToolBridge.java` — OmniRoute 工具调用桥

### 既有类增量

- `UiLanguage.java` — 新增 `o(Context,String,String)`、`o(String,String)`、`e(Context)`
- `HostCompat.java` — 新增 `hostReady()`、`complete(String,String,String)`（反射调用 `Main.completeViaHost`）
- `Main.java` — 删除 `ExpertRelayGate` import 与调用，改为内联判断；新增 `completeViaHost`、`extractSessionId(String)`、`handleLoadPackage` 本地 API 自动恢复钩子
- `SettingsActivity.java` — 新增本地 API / 公网隧道 / 前台保活三块面板共 10 个入口
- `QqMusicSessionAccessService.java` — 补 `onListenerConnected` 与 `manager()`

### 工程文件

- `module-legacy/AndroidManifest.xml`、`module-universal/AndroidManifest.xml` — 版本 35/1.7.5，补 6 组件、5 权限
- `res/values/strings.xml`、`res/values-zh/strings.xml`（两个渠道）— 补本地 API/音频/隧道状态条目
- `module-legacy/build.sh`、`module-universal/build.sh` — 删除 `find ../module/src/com/dsmod/relay` 行，版本字符串改为 1.7.5
- `module/` 下没有 `AndroidManifest.xml`（旧版即如此，由各渠道 build.sh 使用自己的 Manifest）

## 已知边界与运行时前提（重要）

1. **`HostCompat.complete` → `Main.completeViaHost`**：依赖 `liveR92`/`liveQ71` 被宿主对话捕获。冷启动后直接调用本地 API 会返回 503 `host_transport_not_ready`；需先在 DeepSeek 里正常发一条消息。`ew0` 构造器参数是按"首参非 int、第 3 参 String"推测填充的，**未在真机验证**。
2. **TLS 监听**：代码读取 `deekseep_local_api_server.p12`（PKCS12，口令 `deekseep`）。工程内**没有生成该 keystore 的脚本**，因此 TLS 实际永不启用，只有明文 HTTP。
3. **Anthropic 工具结果回填**：输出 `tool_use` 块已实现，`renderToolResult` 将回传结果序列化为 `[[DEEKSEEP_TOOL_CALL]]` 文本。模型能否据此续轮**未验证**。
4. **断连监视**：`CLIENT_GONE` 已从 `ThreadLocal` 改为 `ConcurrentHashMap<String, Boolean>`（按 clientId 共享），`clientGone()` 读取该标记并回退到 socket 状态检查。仍未真机验证。
5. **`direct_root`**：只影响 `fillStatus` 输出的 `endpoint` 字符串与 `GET /status` 响应，**不做任何请求转发**。
6. **混淆短名类**（`gb`、`xc0`、`zc0`、`t3`、`l2`、`m2`、`c3`、`y1`、`w1`、`uh`、`nh0`、`a01`、`bd0`、`cd0`、`p9`、`ed0`、`v1`、`t9`、`y4`、`g`）未生成桩。若 `Main` 反射路径触及 `qw0`/`nx0` 之外的短名类会 `ClassNotFoundException`。

## 未恢复项

1. 受保护构建 payload（`PROTECTED_BUILD=false`）
2. `.gradle.kts` 与 CI 脚本的 1.7.5 增量（沿用旧版）
3. TLS keystore 生成脚本
4. `AgentSettingsUi` / `ChatSearchUi` / `SpatialMotion*` 未逐方法比对 1.7.5 dex（仅确认文件存在）
5. `HostCompat.complete` 在真机上的实际可用性

## 编译前已修的阻断项

- `CLIENT_STALE_MS` 曾误落于 `startClientWatch()` 方法体内（Java 不允许），已移至类字段区第 74 行
- `AnthropicAdapter` 类曾被提前闭合，已恢复为 `buildPrompt` 后才闭合
- `Main` 曾引用不存在的 `invokeStatic(...)` 与 `LocalApiKeepAliveService.isRequested(Context)`，均已修正/补齐
- 三处 `HostCompat.complete(...)` 已包 try/catch，返回 503/502 而非 500

## 编译验证建议

```sh
cd project/Deekseep-1.7.5-restored/module-legacy && bash build.sh 2>&1 | tee /sdcard/build-legacy.log
```

若 `javac` 报错，优先检查：

1. `LocalApiGateway` 内 `ToolCall`/`ChatTurn` 的可见性与泛型
2. `Main.completeViaHost` 对 `expertRelaySessionError`、`liveR92`、`liveQ71` 的引用
3. `HostCompat.complete` 的反射签名与 `describeCause`
4. `PublicTunnelManager.PREFS` / `KEY_DIRECT_ROOT` 的跨类可见性（`LocalApiGateway.directRoot()` 引用）
