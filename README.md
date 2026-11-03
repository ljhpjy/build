# fuckds APK

把 [fuckds](https://github.com/xingling66666/fuckds)（把 chat.deepseek.com 网页版包装成 OpenAI / Anthropic 兼容 API 的 Node 服务）
打包成**带 UI 的 Android APK**：内置 Node.js 运行时、Go PoW 求解器和原版网页控制台，
不需要 Termux / root / 电脑 / 命令行。装好打开后环境自动准备，随后用原生向导粘贴一次
DeepSeek `userToken` 即可使用。

> 本目录是独立工程，可以直接作为 GitHub 仓库根目录使用；
> `app/` 是上游源码快照，改动见 [app/ANDROID.md](app/ANDROID.md)。

---

## 这个 APK 能做什么

- OpenAI 兼容：`POST /v1/chat/completions`（流式 / 非流式 / tools / 图片 / `reasoning_content`）
- Anthropic 兼容：`POST /v1/messages`、`count_tokens`（流式 / tools / thinking）
- 超长上下文自动转 `.txt` 附件；图片自动上传
- 多账号号池、限流冷却、自动换号重试
- 完整的网页控制台：概览 / 账号 / 测试 / 日志 / 设置 / 文档
- **原生首启向导**：环境就绪后自动引导填 token，保存后自动重启服务并等待登录态加载完成
- 前台服务常驻（通知栏可停止 / 打开控制台），崩溃自动拉起，可选开机自启

**与桌面版的唯一功能差异**：`browser` 引擎（Playwright + 桌面 Chromium）没有打包进来，
因为它需要一套完整的 Chromium；默认的 `direct` 引擎功能是完整的，在 APK 里也会强制使用 `direct`。

---

## 安装 & 首次使用

1. 从 Releases 下载对应 CPU 架构的 APK：
   - 绝大多数手机：`arm64-v8a`
   - 模拟器 / 少部分 x86 设备：`x86_64`
2. 安装并打开（首次启动会解压 Node 运行时，需要 1～2 分钟，请耐心等通知栏/界面提示）。
3. 环境准备完成、主页显示「服务运行中」后，App 会**自动弹出原生「配置 DeepSeek 登录态」向导**；
   如果错过了，点主页「立即配置登录态」，或右上角菜单 →「配置登录态」。
4. 按向导提示粘贴 `userToken`，点「保存并重启服务」，向导会自动等待服务加载登录态：
   - 电脑浏览器打开 <https://chat.deepseek.com> 并登录
   - F12 → Application → Local Storage → `https://chat.deepseek.com` → 键 `userToken`
   - 值是 `{"value":"xxxxx","__version":"0"}`，复制 `value` 部分（整段 JSON 也可以直接粘）
5. 完成后就可以用下面的地址接入客户端；需要校验 token 时可在控制台「账号」页点校验。

### 客户端配置

| 客户端 | base_url | API Key | 模型 |
| --- | --- | --- | --- |
| OpenAI SDK / 兼容客户端 | `http://127.0.0.1:8787/v1` | 任意（除非你配了 `API_KEY`） | `deepseek-flash` / `deepseek-reasoner` 等 |
| Anthropic SDK | `http://127.0.0.1:8787` | 同上 | 同上 |

> 服务只监听 `127.0.0.1`：**同一台手机上**的其他 App（如酒馆、各种客户端）可以访问，
> 局域网里的电脑/手机访问不到。这是有意的安全默认值。

---

## 界面

- **主页**：服务状态、接口地址、号池可用数、Node 版本；启动 / 停止 / 重启 / 打开控制台 / 日志。
- **配置向导**：环境就绪且未配置 token 时自动弹出；粘贴 token、保存并自动重启服务，加载成功后自动关闭。
- **控制台**：就是原项目的 `/admin` 网页，WebView 加载；账号、设置、测试、日志全都在。
- **日志页**：直接读 `data/server.log` 尾巴，服务起不来时用它排障。
- **设置**：开机自启、息屏保持运行、忽略电池优化、监听端口（服务起不来/端口冲突时可直接改）。

---

## 工作原理

```
┌──────────────────────────── Android App ──────────────────────────────┐
│ MainActivity / WebView 控制台                                          │
│        │ HTTP (127.0.0.1)                                            │
│ ServerService（前台服务，负责拉起/看护）                                  │
│        │ exec                                                         │
│ runtime/bin/node  src/index.mjs  ◀── assets 里解压出来的 Termux Node     │
│        │ spawn                                                         │
│ runtime/bin/ds-pow（Go 交叉编译的 PIE）                                   │
│        │ HTTPS + PoW                                                  │
│ chat.deepseek.com                                                     │
└───────────────────────────────────────────────────────────────────────┘
```

- **Node 运行时**：构建时从 Termux 官方仓库下载 `nodejs` 及其依赖的 `.deb`，按 `DT_NEEDED`
  闭包裁剪出 `bin/node` + `lib/*.so` + CA 证书，打进 `assets/runtime.zip`。不是自己交叉编译，
  所以版本新、和 Android/bionic ABI 完全匹配。
- **PoW**：上游 `cmd/powd` 用 `GOOS=android` 交叉编译成 PIE，随运行时一起打包。
- **targetSdk = 28**：Android 10+ 会对 `targetSdk >= 29` 的应用禁止执行私有目录里的文件；
  Termux 至今保持 28 也是这个原因。这是刻意设置，**不要随手改高**。
- **登录态向导**：环境健康、`/healthz` 可达但还没有 token 时，主页自动打开 `TokenSetupActivity`；
  写入 `data/auth.json` 后重启服务，并轮询 `/healthz` 确认新进程已加载登录态。
- **重启**：控制台的「重启」在 Android 上让 Node 进程 `exit(0)`，前台服务监听退出事件后自动拉起。
- **数据目录**：`/data/data/com.fuckds.android/files/fuckds/app/data`
  （token、设备 ID、日志都在这里；卸载即清空）。

---

## 构建

### GitHub Actions（推荐）

仓库自带 [.github/workflows/build.yml](.github/workflows/build.yml)，推送到 `main` / `master`
或手动 `workflow_dispatch` 即可，会同时产出 `arm64-v8a` 和 `x86_64` 两个 APK。

推 tag（如 `v1.0.0`）会自动创建 Release 并附上 APK。

**签名**：

- 不配 secrets 时，CI 每次生成一个临时 keystore 并用它签名（能直接安装，但**不能覆盖升级**，
  因为下次的签名不同；需要稳定升级请配置下面的 secrets）。
- 配置仓库 Secrets 使用固定签名：
  - `SIGNING_KEYSTORE_BASE64`：keystore 文件的 base64（`base64 -w0 release.jks`）
  - `SIGNING_STORE_PASSWORD`
  - `SIGNING_KEY_ALIAS`
  - `SIGNING_KEY_PASSWORD`

构建机要求的工具链（workflow 里都装好了）：Node 22、Go 1.23+、JDK 17、Android SDK 35、
`zip` / `xz` / `dpkg-deb` / `readelf`。

### 本地构建

```bash
# 1. 准备资源（下载 Termux Node + 编译 Go PoW）
node tools/prepare.mjs --abi arm64-v8a

# 2. 编译 APK
gradle -p android :app:assembleArm64Release \
  -Pfuckds.versionCode=1 \
  -Pfuckds.versionName=1.0.0

# 产物：android/app/build/outputs/apk/arm64/release/app-arm64-release.apk
```

模拟器用 `--abi x86_64` + `:app:assembleX64Release`。

如果 `node tools/prepare.mjs` 卡在下载，可以手动换镜像：编辑
[tools/termux-runtime.mjs](tools/termux-runtime.mjs) 顶部的 `REPOS` 顺序。

---

## 目录结构

```
fuckds-apk/
├─ app/                     # 上游 Node 源码快照（含 Android 适配）
│  ├─ src/                  # 服务本体
│  ├─ web/dist/             # 已构建的控制台（React + Vite + mdui）
│  ├─ pow-src/              # Go PoW 源码（构建用，不进 APK）
│  └─ .env.android          # 首次启动的默认配置
├─ android/                 # Android 工程（Kotlin + Material 3 + WebView + 原生 token 向导）
├─ tools/
│  ├─ prepare.mjs           # 总入口：下运行时、编 PoW、打 assets
│  ├─ termux-runtime.mjs    # Termux 包解析 / 依赖闭包 / 裁剪
│  └─ build-pow.mjs         # Go 交叉编译
└─ .github/workflows/build.yml
```

---

## 常见问题

**Q：安装后还要装 Termux / Node / root 吗？**
A：都不需要。APK 里已经带了 Android 版 Node、全部动态库、CA 证书和 Go PoW；
Termux 只是运行时的来源，不是依赖。App 用普通应用权限运行，`targetSdk 28` 保证私有目录里的
可执行文件能在 Android 10+ 上启动，不需要 root、Magisk 或额外的命令行环境。

**Q：token 必须配置吗？**
A：必须。它是你的 DeepSeek 账号登录态，不能预置（否则等于公开账号）；没有 token 时服务和控制台
仍能起来，但对话接口会报「未配置」。原生向导就是专门用来完成这一步的。

**Q：APK 为什么几十 MB？**
A：里面带了完整的 Node.js + ICU（约 100MB 解压后，压缩进 APK 约 30～50MB），以及 Go PoW。
这是“不装 Termux 也能跑 Node”的代价。

**Q：安装时提示 targetSdk 过低 / 应用为旧版 Android 构建？**
A：`targetSdk 28` 是为了让 Node 可执行文件能在私有目录里跑（和 Termux 一样），不是漏改。
Android 14/15 仍可正常安装；只有 Google Play 上架会拒绝，本项目不面向 Play。

**Q：服务启动失败怎么办？**
A：先看 App 里的「日志」页；常见原因：
- 存储空间不足（首次需要 180MB 以上）；
- APK 架构和手机不匹配（arm64 手机装成了 x86_64，或反之）；
- 系统禁止了应用私有目录执行（极少数深度定制 ROM；targetSdk 必须保持 28）。

**Q：息屏后接口不通？**
A：设置里开启「息屏保持运行」，并在系统里允许「忽略电池优化」；国产 ROM 还需要在
最近任务里给本应用加锁 / 允许自启动。

**Q：能改成局域网访问吗？**
A：控制台 → 设置 → 监听地址改成 `0.0.0.0`（重启生效）。但任何局域网设备都能访问你的
DeepSeek 登录态，**强烈建议同时设置 `API_KEY`**。

**Q：token 存在哪里？安全吗？**
A：`data/auth.json`（应用私有目录，其他 App 读不到）。但 `127.0.0.1` 上的接口本机所有
App 都能访问，建议设置 `API_KEY`，尤其是装了来源不明的 App 时。

**Q：能换 Node 版本吗？**
A：CI 每次都取 Termux 仓库当前 `nodejs` 包。要固定版本可以改
[tools/termux-runtime.mjs](tools/termux-runtime.mjs)，从 `Packages` 索引里按 `Version` 选。

---

## 许可

上游 `fuckds` 使用 **AGPL-3.0**，本工程作为衍生作品同样使用 AGPL-3.0，见 [LICENSE](LICENSE)。
APK 内同时包含 Node.js（MIT）、OpenSSL（Apache-2.0）、ICU（Unicode License）等第三方组件，
其许可证由 Termux 包随附；再分发时请保留 [app/LICENSE](app/LICENSE) 与本说明。
