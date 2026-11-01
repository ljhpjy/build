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

GitHub Action `Build and test source edition` 已经跑过编译链路：

- `javac`、`d8`、`aapt2`、`zipalign`、`apksigner` 在两个渠道上全部通过，
  `ds-probe-universal.apk` 与 `ds-probe-universal-google-play.apk` 均已产出并签名。
- 当前阻断点在**回归测试**（`scripts/build-all.sh` 的测试段），不在编译。

也就是说"能编译"已由 CI 证实；"功能可用"仍未经真机验证。本地完整复核：

```sh
cd project/Deekseep-1.7.5-restored && bash scripts/build-all.sh
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

## GitHub Action 排障记录

### 第一轮：javac（10 个错误）

| 错误 | 修复 |
|---|---|
| `LocalApiGateway.modelsPayload()` 未捕获 `JSONException` | 整段包 `try/catch (Throwable ignored)` |
| `PublicTunnelManager` 用了 `KeyGenParameterSpec.PURPOSE_*` | 改用 `KeyProperties.PURPOSE_*` / `BLOCK_MODE_GCM` / `ENCRYPTION_PADDING_NONE`，并补 import |
| `PublicTunnelManager.pid` 不存在 | 改为 `PublicTunnelManager.process = null` |
| `PinggyTunnelManager.LOCK/requested/pid/state` 不存在 | 改用实际字段短名 `c`/`k`/`l`/`m`，常量走 `PREFS`/`KEY_REQUESTED`/`STATE_*` |
| `NotificationIcons.newBuilder(...)` 不存在 | 改为 `new Notification.Builder(this, CHANNEL_ID)` |
| `PinggyTunnelManager` 缺长名别名 | 补 `LOCK = c` 与 `isRequested()`/`setRequested()`/`getPid()`/`getState()` |

### 第二轮：回归测试（1 个断言 + 2 处死引用）

| 问题 | 修复 |
|---|---|
| `HostCompatRegressionTest:254` 断言 `hasV236ChatViewModel(testClassLoader)` 为真，但测试 classpath 里没有 `td1` 类，恒为假 | 拆出 `HostCompat.hasV236ChatViewModelShape(Class<?>)`；测试改用自带的 `V236ChatViewModel` / `V236Continuation` 形状桩：正例必中、`kd1` 反例不得命中 |
| `scripts/build-all.sh` 仍调用已删除的 `module/test-expert-relay-regression.sh`（1.7.5 已移除 `com.dsmod.relay`） | 删掉该步骤，并删除脚本本身 |
| `SettingsActivity` 新增的中文文案没进 `UiLanguageCatalog`，`test-language-catalog.sh` 会判失败 | 补 53 条本地 API / 公网隧道 / 前台保活中英对照 |

### 尚未在 CI 中跑到的段落

上一轮日志里，`HostCompatRegressionTest` 之后的 12 个 java 回归测试、
`./test-language-catalog.sh`、`test-adapter-regression.sh` 以及末尾的签名/一致性校验都还没执行过。


## 功能移除记录（用户要求）

### 公网隧道（整体删除）

- 删除 `PublicTunnelManager.java`、`PinggyTunnelManager.java`
- `SettingsActivity`：移除「公网隧道」整块 UI（Cloudflare 配置/启停、Pinggy 启停）与三个处理方法
- `LocalApiGateway`：去掉 `setGatewayState`/`setLocalPort` 调用；`directRoot()` 恒返回空、`publicBaseUrl()` 退回局域网地址
- `LocalApiKeepAliveService`：`onDestroy` 去掉隧道清理；通知文案去掉「公网入口」分支
- `Main`：自动恢复钩子不再拉起隧道
- `UiLanguageCatalog`：删除 12 条隧道词条

### Agent · 实验性（功能 + UI 全部移除，复用部分已迁移）

已删除类：`AgentSettingsUi`、`AgentRunUi`、`AgentQuestionUi`、`AgentDeviceBridge`、
`AgentToolConfig`、`AgentDelayActivity`、`AgentDelayReceiver`

迁移（心跳仍在复用）：

- `ToolResult.java`（新文件）—— 承接原 `AgentDeviceBridge.ToolResult`（6 个 final 字段 + 同签名构造器）
- `HeartbeatToolProtocol` 新增：`PROMPT_STRENGTH_BASIC/ENHANCED/IMMERSIVE`、`hideToolLogs()`、
  `enabledFast()`、`promptStrength()`、`isKnownTool()`、`isHeartbeatTool()`、`allows()`、
  `heartbeatTools(boolean)`、`knownToolNames()`

`Main` 改动：

- 工具分发链保留 8 个分支（6 个心跳 + `get_current_time` + `render_rich_panel`），其余 5 组
  （ask_user / delay / music / 界面操作 / 文件·Shell·网络）统一返回「已在当前版本中移除」
- 删除 `queueAgentQuestion`、`queueAgentDataTool`、`queueAgentUiTool`、`queueAgentMusic`、`queueAgentDelay`
- 16 处 `AgentToolConfig.*` 改指 `HeartbeatToolProtocol.*`（`enabledFast`×10、`effectiveTools`×2、
  `allows`、`isHeartbeatTool`、`promptStrength`、`load().enabled`）
- `probeConfiguredAgentBackendOnce` 改为空实现（保留调用点，Root/Shizuku 后端随之移除）
- `handleAgentDelayComplete` 的 4 个 `EXTRA_*` 常量改为字面量

测试调整：删除「Agent 设置序列化/迁移」整段；`AgentQuestionUi.buildVisibleAnswer` 相关断言删除；
提示词强度测试改指 `HeartbeatToolProtocol.knownToolNames()`；`test-thinking-regression.sh` 的编译清单
去掉 3 个已删文件。

保留件（与 Agent 无关）：`AgentRunStore`（结果队列，心跳在用）、`DeekseepTools`（导出/统计/备份）、
`TaskExecutionUi`（通用任务弹窗）。

### 影响

- 心跳仍可用：6 个自调度工具 + `get_current_time` + `render_rich_panel`
- 不再向模型提供设备类工具（文件、Shell、网络、截屏、点击、滑动、音乐、延时、提问）
- 设置页不再有「Agent · 实验性」「公网隧道」入口
