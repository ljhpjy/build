# DSMod DeepSeek Local API

把 **DeepSeek 官方 App（com.deepseek.chat）** 包装成 **OpenAI / Anthropic 双协议兼容** 的本地 API 服务。

本项目是一个 **Xposed 模块**，注入 DeepSeek 官方 App 进程后：

- 完整实现 **direct 引擎**：自己建会话、解 PoW、直连 /api/v0/chat/completion 的 SSE JSON-patch 流；
- 复用官方 App 已有的登录态（MMKV 的 `key_user_info`）与 Rust 原生 PoW 求解器（可选加速）；
- 上下文无状态：每条请求都把历史重拼成一条 prompt，一次全新对话；
- OpenAI 与 Anthropic 两条协议链路共享同一套执行逻辑。

实现参考 [fuckds](../fuckds)（Node 版）的 direct 引擎语义。

---

## 特性

- **OpenAI 兼容**：`POST /v1/chat/completions`（非流式 + SSE 流式）、`POST /v1/completions`
- **Anthropic 兼容**：`POST /v1/messages`（流式 / 非流式 / tool_use / thinking）、`POST /v1/messages/count_tokens`
- **上下文无状态**：每条请求 = 一条 prompt = 一次全新网页对话
- **段落拼接顺序固定**：客户端 system 最上面 → 工具声明 → 自带协议提示词（中间）→ 对话历史（最后）
- **原生 DSML 工具调用**：解析成标准 `tool_calls` / `tool_use`，并从 `content` / SSE 里剔除
- **流式协议过滤**：SSE 逐行过滤 DSML / RESULT / END / 伪造段标题，协议内容不会漏给客户端
- **自包含日志**：`files/dsmod_log.txt`（512KB 滚动，自动去敏），`/admin` 可查看/复制/导出；**不写入 logcat / LSPosed 日志**
- **冷启动序列**：进程内每账号跑一次 settings / check_device，降低"非网页"画像
- **人类输入停顿**：发送前 120–480ms 随机延迟（`humanTiming`）
- **`/admin` 控制台**：浏览器打开 `http://127.0.0.1:8765/admin` 查看状态 / 号池（可编辑保存）/ 配置 / 日志；填 API key 后自动带上鉴权
- **历史工具调用只写 JSON 行**（`## Tool_calls`），绝不回写 DSML，模型没有可照抄的样板
- **工具结果独立成段**（`## Tool_call_result`），不并进 `## Assistant`
- **RESULT 只清洗不打断**：模型自造的工具结果从正文中剔除，不中止生成
- **思考**：深度思考内容走 `reasoning_content` / `thinking` 块，且先于正文
- **工具参数类型纠正**：字符串化的数字 / 布尔 / 数组 / 对象按声明还原
- **图片输入**：`image_url` / `image` 块 → 上传成网页附件 → `ref_file_ids`
- **超长上下文自动转附件**：超过 10 万字时把较早段落拆成 `.txt` 附件（`ref_file_ids`），自带协议提示词永远留在正文
- **PoW / 会话预热**：后台预解下一个 PoW、预建下一个会话，降低首字延迟
- **号池**：多 token 轮询 + 限流冷却（`dsmod_accounts.json`）
- **完整 direct 引擎**：会话创建 / PoW challenge / SSE patch 流 / 40301 重试 / 空流重试 / 超时

---

## 目录结构

```
dsmod-deepseek/
├── .github/workflows/build.yml        # GitHub Actions：JDK 17 + Gradle 构建 + 签名 + 上传 APK
├── build.gradle / settings.gradle / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml       # Xposed 模块声明（无 Service / Receiver）
        ├── assets/xposed_init        # com.dsmod.deepseek.Main
        ├── res/values/arrays.xml     # xposedscope = com.deepseek.chat
        └── java/com/dsmod/deepseek/
            ├── Main.java             # Xposed 入口（Application + ActivityThread 三重 hook）
            ├── Config.java           # 默认配置
            ├── Logs.java             # 自包含日志（去敏 / 复制 / 导出）
            ├── Notifier.java         # 宿主进程常驻通知
            ├── AdminPage.java        # /admin 深色控制台
            ├── Accounts.java         # token 读取（MMKV）+ 号池
            ├── Pow.java              # DeepSeekHashV1 纯 Java 求解 + 官方 native 加速
            ├── DeepSeekClient.java   # direct 引擎（会话/PoW/SSE/上传）
            ├── Prompt.java           # ## System 段落拼接 + 协议提示词
            ├── ToolKit.java          # DSML 解析 + 协议清洗 + 参数纠正
            ├── ChatRunner.java       # 协议归一化 + 单轮执行
            ├── LocalApiServer.java   # 本地 HTTP server + 双协议输出
            ├── Notifier.java            # 宿主进程常驻状态通知（点击回到 DeepSeek）
            └── SettingsActivity.java
```

---

## 编译

### GitHub Actions（推荐）

仓库 push 后自动运行 `.github/workflows/build.yml`：

1. checkout
2. JDK 17（Temurin）
3. Gradle 8.9（`gradle/actions/setup-gradle`）
4. keytool 生成临时签名（`app/build.gradle` 自动读取 `KEYSTORE_FILE` 环境变量）
5. `gradle :app:assembleRelease`
6. 产物作为 artifact `dsmod-deepseek-apk` 上传

### 本地

```bash
# JDK 17 + Android SDK
gradle :app:assembleRelease
# 不配置签名时产出未签名 APK；配置方式：
# export KEYSTORE_FILE=/path/ks.jks KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...
```

---

## 版本号（单一来源）

`version.properties` 是唯一真相，构建时自动注入到三处，不会再出现「改了版本号不生效」：

```properties
VERSION_NAME=1.1.2
VERSION_CODE=10102   # 可省略，自动由 VERSION_NAME 推导（1.1.2 -> 10102）
```

| 位置 | 来源 |
|---|---|
| `AndroidManifest.xml` 的 versionName / versionCode | Gradle 从 `version.properties` 注入（manifest 里**不再硬编码**） |
| APK 文件名 | workflow 读取后重命名为 `DeepSeek-本地API_<VERSION_NAME>.apk` |
| `/healthz` 的 `version` 字段、设置页显示 | `BuildConfig.VERSION_NAME`（编译期注入） |

**自动递增**：workflow 最后一步在「构建 + 重命名 + 上传」全部成功后，把 patch 号 +1 并提交回分支（commit message 带 `[skip ci]`）。
构建失败时该步骤不会执行，所以版本号不会前进。

- 只在 `push` 到 `main` / `master` 或 `workflow_dispatch` 时递增
- PR 构建、tag 构建不递增（tag 的版本由 tag 决定）
- 用 `GITHUB_TOKEN` 推送不会触发新的 workflow run，不会死循环
- 需要仓库 Settings → Actions → Workflow permissions 允许 **Read and write**（workflow 已声明 `permissions: contents: write`）

## 使用

1. 安装模块 APK；
2. 在 LSPosed 中启用，作用域勾选 **com.deepseek.chat**；
3. 打开 DeepSeek 官方 App 并登录；
4. 服务在宿主进程内监听 `0.0.0.0:8765`（所有网卡，局域网可访问；可用 `dsmod_config.json` 改）；本机客户端直接连 `http://127.0.0.1:8765`，局域网设备用手机 IP；
5. OpenAI 客户端：`base_url = http://127.0.0.1:8765/v1`
   Anthropic 客户端：`base_url = http://127.0.0.1:8765`

```bash
curl http://127.0.0.1:8765/healthz

curl -N http://127.0.0.1:8765/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-v4.1-flash-1017",
    "stream": true,
    "messages": [{"role":"user","content":"用一句话解释什么是 QUIC"}]
  }'

curl -N http://127.0.0.1:8765/v1/messages \
  -H "Content-Type: application/json" \
  -H "x-api-key: any" \
  -d '{
    "model": "deepseek-v4.1-flash-1017",
    "max_tokens": 1024,
    "stream": true,
    "messages": [{"role":"user","content":"你好"}]
  }'
```

---

## API

| 接口 | 协议 | 说明 |
| --- | --- | --- |
| `POST /v1/chat/completions` | OpenAI | 流式 / 非流式，tools，reasoning_content |
| `POST /v1/completions` | OpenAI | 旧版补全 |
| `POST /v1/messages` | Anthropic | 流式 / 非流式，tool_use，thinking |
| `POST /v1/messages/count_tokens` | Anthropic | 返回 `input_tokens` |
| `GET /v1/models` | 通用 | 模型列表 |
| `POST /v1/responses` | OpenAI | Responses API，流式 / 非流式，function_call |
| `GET /healthz` | 通用 | 状态 |
| `GET /admin/api/overview` | 管理 | 状态 + 号池（token 打码） |
| `GET/POST /admin/api/accounts` | 管理 | 读号池（打码+冷却）/ 整体保存 `{tokens:[...]}` |
| `GET/POST /admin/api/config` | 管理 | 读 / 更新配置（写回 dsmod_config.json） |
| `GET /admin/api/logs` | 管理 | 读日志尾部 300 行（自动去敏） |
| `GET /admin/api/logs/export` | 管理 | 下载完整日志（含滚动历史，已去敏） |
| `POST /admin/api/logs/clear` | 管理 | 清空日志 |
| `GET /` | 通用 | 说明页 |
| `GET /admin` | 管理 | 简易控制台（状态 / 号池 / 配置 / 日志） |
| `POST /v1/embeddings` | OpenAI | 501 友好报错（网页后端不支持） |

鉴权：`dsmod_config.json` 里的 `apiKey` 非空时要求 `Authorization: Bearer <key>` 或 `x-api-key: <key>`。

### 错误分类（客户端可区分）

| HTTP | 含义 | 响应内容 |
| --- | --- | --- |
| 429 | **上游限流 / 触发门限** | `{"error":{"type":"rate_limit_error","code":"rate_limited","message":"上游限流（rate_limited）：…"}}`，并带 `Retry-After: 60` |
| 502 | **上游接口失败** | `code:"upstream_error"` / `session_failed` / `empty_stream` 等，message 以“上游接口失败”开头 |
| 401 | 登录失效 | `code:"auth_expired"`，该号自动停用 24h |
| 503 | 账号不可用 | `code:"account_unavailable"`（未登录 / 全部号停用） |
| 413 | 上下文过长 | `code:"prompt_too_long"` |

多账号：同一请求内遇到 401/429/5xx 会自动换下一个号重试（fuckds 行为）；命中限流的号冷却 60s。单账号命中限流时立即返回 429 + `Retry-After: 60`，不傻等。默认并发 1（与 fuckds `CONCURRENCY=1` 一致），管理接口不受并发闸门影响。

### PoW 求解

以下结论来自 **DeepSeek 2.7.5 新版 App 的 smali 分析**（不是照抄旧版模块）：

- `ServerChatChallenge`（Kotlin 序列化器 `Lrs9`）字段：`algorithm, challenge, salt, signature, difficulty, expire_at, expire_after, target_path`。
- 请求头 payload `AuthedPowSolution`（序列化器 `Lff0`）字段：`algorithm, challenge, salt, signature, answer, target_path`（base64 后作为 `X-DS-PoW-Response`）。
- 官方求解（`Lcf0.z`）调用 `PowCalculator.calculateDeepSeekHashV1Pow(prefix, challenge, difficulty)`，其中 **`prefix = salt + "_" + expire_at + "_"`**；`PowCalculator.a(J,S,S)` 的 smali 为 `native(p3, p4, p1)`，因此等价调用是 **`a(difficulty, prefix, challenge)`**。
- 模块默认直接调用宿主该方法（`Config.powNative=true`），带 1.5s 超时；超时后本次运行禁用原生并回退纯 Java（同样输入 `salt_expire_at_nonce`，前 3 字节为 0 即答案）。
- 原生答案会用 Java 算法交叉校验：日志 `pow verify ok` / `pow verify mismatch hash=…`。

### 请求统计与日志降噪

- `/healthz` 的 `requests` 只统计 `/v1/*` 上游请求，`/admin`、`/healthz` 及后台轮询不计入。
- 访问日志只记录 `/v1/*`、`/admin` 页面和 POST 管理操作，轮询不再刷屏。

### 一次聊天请求的完整日志（示例）

日志采用 logcat 风格：`时间 级别/分类[模块]: rid=请求ID 事件 键=值`。
每条都带同一个 `rid=`，搜索 `rid=` 即可串联一次请求；后台预取线程带 `bg=prefetch`，不属于任何请求。

```
08:12:05.100 I/HTTP[DSMOD]     rid=req_abc: recv POST /v1/chat/completions
08:12:05.120 I/ACCOUNT[DSMOD]  rid=req_abc: account pick #2/3 token=pxEK****dYL8 skip=[#1:authFailed #3:cooldown42s]
08:12:05.180 D/CONFIG[DSMOD]   rid=req_abc: tool decls compacted lines=70 full_chars=98000 compact_at=1350000
08:12:05.185 I/CHAT[DSMOD]     rid=req_abc: prompt built chars=21221 messages=20 tools=70 overflow=false
08:12:05.400 I/UPSTREAM[DSMOD] rid=req_abc: completion req model=default thinking=true search=false files=0
08:12:05.410 I/UPSTREAM[DSMOD] rid=req_abc: completion tx attempt=1 retries=0
08:12:06.200 I/UPSTREAM[DSMOD] rid=req_abc: completion rx http=200 content_type=text/event-stream; charset=utf-8
08:12:09.300 D/UPSTREAM[DSMOD] rid=req_abc: stream fragments=1 content=0 reasoning=931
08:12:12.400 D/UPSTREAM[DSMOD] rid=req_abc: stream fragments=3 content=214 reasoning=2366
08:12:13.600 I/UPSTREAM[DSMOD] rid=req_abc: completion done content=73 reasoning=2366 ttft_ms=5841 total_ms=10154
08:12:13.650 D/CHAT[DSMOD]     rid=req_abc: strip content=73->0 reasoning=2366->2296 removed=143 tool_source=content
08:12:13.660 I/CHAT[DSMOD]     rid=req_abc: chat done content=0 reasoning=2296 tools=2 [get_time_info(2B),eval_javascript(48B)] src=reasoning fabricated=false elapsed=10255ms cold=0 session=583 pow=190
08:12:13.670 D/CHAT[DSMOD]     rid=req_abc: tool args eval_javascript: {"code":"const a=[1,2,3]…"}
08:12:13.680 I/HTTP[DSMOD]     rid=req_abc: done POST /v1/chat/completions elapsed_ms=10314
```

**读法**：

| 字段 | 含义 |
|---|---|
| `rid=req_xxx` | 同一请求的所有日志都带它；`bg=prefetch` 是后台预取，不属于任何请求 |
| `skip=[...]` | 为什么跳过了其他账号：`authFailed` 已失效 / `cooldown42s` 冷却中 |
| `attempt=1 retries=0` | 上游尝试次数；重试会额外出现 `completion retry reason=...` |
| `stream fragments=3 content=214 reasoning=2366` | 片段数 + 已收正文/思考字符数（只在结构变化或 15s 心跳时打） |
| `strip ... removed=143 tool_source=content` | 清洗掉多少字符、工具调用从哪个流提取 |
| `chat done ... cold/session/pow` | 收尾一行：内容量 + 工具名 + 各阶段耗时（毫秒） |
| `tool args ...` | D 级：工具参数脱敏预览，排查参数错误用 |

**排障定位**（看最后一条停在哪里）：

| 最后一条 | 结论 |
|---|---|
| `recv …` | 请求已进入，但业务未开始（并发排队 / 无线程） |
| `completion tx attempt=…` | 已发出，上游不响应（网络 / 风控），120s 后报 `completion error` |
| `stream fragments` 中断 | 上游断流，报 `stream_failed` |
| `completion retry reason=…` | 触发重试：`pow` / `upstream_5xx` / `empty_stream` |
| `completion rx http=429` | 上游限流（`rate_limited`） |
| `completion rx http=401` | 登录失效（`auth_expired`） |
| `pow native timeout` / `pow java exhausted` | PoW 失败（参数 / 算法异常） |
鉴权：`dsmod_config.json` 里的 `apiKey` 非空时要求 `Authorization: Bearer <key>` 或 `x-api-key: <key>`。

---

## prompt 拼接

一次请求 = 一次全新网页对话，模型只能靠拼出来的这一条 prompt 判断上下文：

```
# Messages:
## System:
  ...客户端传入的 system / developer（最上面）...
## Tool_functions:
  {"name":"exec_shell","description":"...","parameters":{...},"required":[...]}
## System:
  ...自带 DSML / 特殊 token 协议提示词（夹在中间）...
## User:
  ...对话历史直到最后一问...
## Assistant:
  ...模型历史回答... < | | END | | >
## Tool_calls:
  {"name":"exec_shell","args":{"command":"uname -a"}}
## Tool_call_result:
  {"name":"exec_shell","result":"Linux ..."}
```

模型输出工具调用走原生 DSML：

```
<｜DSML｜ calls>
    <｜DSML｜ invoke name="exec_shell">
        <｜DSML｜ parameter name="command" string="true">uname -a</｜DSML｜ parameter>
    </｜DSML｜ invoke>
</｜DSML｜ calls>
```

解析后转成标准 `tool_calls` / `tool_use`，并从正文和 SSE 里剔除；历史里只回写 `## Tool_calls` 的 JSON 行，绝不写 DSML。

---

## direct 引擎实现对照（fuckds）

| fuckds 能力 | 本模块 |
| --- | --- |
| `/api/v0/chat_session/create` 每次新会话 | ✅ `DeepSeekClient.createSession` |
| `/api/v0/chat/create_pow_challenge` | ✅ `DeepSeekClient.fetchChallenge` |
| DeepSeekHashV1（Keccak-f 跳过 round 0） | ✅ `Pow.hashV1`（纯 Java）+ 官方 `PowCalculator` 反射加速 |
| PoW 搜索 `[0,difficulty)` | ✅ `Pow.solveJava`（默认 144000） |
| `x-ds-pow-response` base64(JSON) | ✅ `Pow.buildHeader` |
| `/api/v0/chat/completion` SSE JSON-patch | ✅ `DeepSeekClient.readStream/applyPatch`（THINK/RESPONSE 片段、status、usage） |
| 40301 INVALID_POW 重试 | ✅ 重新取 challenge + 重新求解一次 |
| 空流重试（限流探测） | ✅ 最多 2 次短等重试 |
| 登录态失效识别（INVALID_TOKEN 等） | ✅ `isAuthInvalid` |
| 号池轮询 + 冷却 | ✅ `Accounts`（MMKV 当前账号 + `dsmod_accounts.json`） |
| 浏览器指纹头 | ✅ `applyHeaders`（sec-ch-ua / sec-fetch / x-client-*） |
| 工具声明（full JSON） | ✅ `Prompt.renderToolDeclarations` |
| DSML 解析（全角/半角、单双竖线、截断） | ✅ `ToolKit.scanTags/parseCalls` |
| `## Tool_calls` 历史 JSON 行 | ✅ `Prompt.renderToolCall` |
| `## Tool_call_result` 独立段落 | ✅ `Prompt.build` |
| RESULT / 伪造段标题清洗 | ✅ `ToolKit.stripMarkup` |
| 思考先于正文（reasoning_content / thinking） | ✅ 回调与 SSE 顺序 |
| 超长 prompt 拒绝（413） | ✅ `maxPromptChars` |
| 图片上传 → ref_file_ids | ✅ `ChatRunner.loadImage` + `DeepSeekClient.uploadFile` |
| 超长 prompt 转附件 | ✅ `Overflow` + `ChatRunner`（`## CONTEXT_ATTACHMENT`） |
| PoW / 会话预热 | ✅ `DeepSeekClient` 静态缓存 + 后台预取 |
| 工具参数类型纠正 | ✅ `ToolKit.coerceArguments` |
| OpenAI / Anthropic 双协议 | ✅ `LocalApiServer` |

---

## 日志系统

- **自包含**：`Logs.java`（内存环形 + 文件），不写 logcat / LSPosed 日志
- **分类**：`MAIN`（注入/生命周期）/ `HTTP`（本地请求）/ `ACCOUNT`（号池）/ `UPSTREAM`（DeepSeek 接口）/ `CHAT`（prompt/工具/响应）/ `CONFIG`
- **分类文件**：`files/dsmod_log.txt`（全量时间线）+ `files/dsmod_errors.txt`（仅 W/E，便于定位报错），均 512KB 滚动
- **自动去敏**：`Bearer`、`token`、`apiKey`、`cookie`、≥28 位长串统一打码为 `****`
- **面板筛选**：分类 chips + 关键字过滤；「复制 / 导出」按当前分类生效
- **接口**：`GET /admin/api/logs?cat=HTTP&lines=300`、`GET /admin/api/logs/export?cat=HTTP`
- **清空**：面板「清空日志」或 `POST /admin/api/logs/clear`

## 配置

写入 `/data/data/com.deepseek.chat/files/dsmod_config.json`（改后重启宿主 App 生效）：

```json
{
  "host": "0.0.0.0",
  "port": 8765,
  "apiKey": "",
  "thinking": true,
  "search": false,
  "maxRounds": 5,
  "concurrency": 2,
  "maxAttachmentChars": 2000000,
  "overflowToAttachment": true,
  "thinkingMode": "always"
}
```

号池（可选）：`/data/data/com.deepseek.chat/files/dsmod_accounts.json`

```json
{ "tokens": ["token1", "token2"] }
```

设备 ID 持久化：`dsmod_device_id.txt`（首次自动生成）。

## 账号池（多账号轮询）

- **登录即入池**：打开 DeepSeek 官方 App 登录后，模块反射 MMKV 的 `key_user_info` 自动取到 token，放在号池第 1 位（★ 标记）。**无需在模块里再登录**。
- **添加更多账号**：在 `http://127.0.0.1:8765/admin` 的 Accounts 区粘贴 userToken 点「添加」；接口 `POST /admin/api/accounts/add {"token":"..."}`。
- **整体替换**：面板 textarea 一行一个 →「整体替换」；接口 `POST /admin/api/accounts {"mode":"replace","tokens":[...]}`。
- **删除**：面板每行「删除」；接口 `POST /admin/api/accounts/remove {"index":1}`（1 起）。
- **验证登录态**：「验证全部」逐个调 `GET /api/v0/users/current`；接口 `POST /admin/api/accounts/verify`，返回 `{valid,total,results:[{index,ok,detail}]}`。
- **轮询规则**：按池顺序轮询，跳过冷却中的号；每号记录 `requests` 次数、`lastUsedAt`、`cooldownMs`、`lastError`。
- **当前选中谁**：`GET /healthz` 的 `last_picked`（1 起，0 = 尚未使用）；面板 Accounts 表 `last picked` 与行首 ★（★ = 宿主登录号）。
- **日志证据**：`files/dsmod_log.txt`（或 /admin 日志卡片）里每次选中都有一行 `pick #2 of 3`。

> 注意：只有「官方 App 当前登录的账号」能自动入池；其它账号的 token 需要你登录对应账号时从 App 里导出一次（清 localStorage/cookies 换号，避免吊销旧 token）。

---

## 常见问题

**Q：API 端口连不上？**
确认 LSPosed 作用域勾选了 `com.deepseek.chat`，并先打开一次 DeepSeek App（模块在宿主进程内启动服务）。`/healthz` 可看状态。

**Q：没有 token / 登录失效？**
先在 DeepSeek App 内登录。token 从 MMKV 的 `key_user_info` 读取，换号后自动跟上。

**Q：PoW 很慢？**
优先用官方 `com.deepseek.crypto.PowCalculator`（Rust native，约 20–30ms）；类不存在时回退纯 Java 求解器（较慢），日志见 `dsmod_log.txt`（标签 DSMOD）。

**Q：为什么多轮工具调用慢 / 429？**
限流是账号级“窗口内消息条数”，每次工具调用 = 一次完整请求；号池或多等一会儿。

---

## 免责声明

本项目只做协议封装，不是 DeepSeek 官方产品。请遵守其服务条款；账号风险（限流、风控、封禁）由使用者自行承担。
