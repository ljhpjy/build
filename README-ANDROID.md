# fuckds Android APK（完整 Linux 环境）

把 fuckds **完整**打包成 Android APK：proot 挂载 Ubuntu 22.04 arm64 rootfs，
里面跑 Node.js 20 + Playwright Chromium（linux-arm64 官方构建）+ Go 交叉编译的
`bin/ds-pow`。**browser / direct 双引擎全部保留，项目源码零改动**。

安装到任意 arm64 手机，打开即用：

```
┌─────────────── APK ───────────────┐
│ MainActivity（WebView → /admin）   │
│ BootService（前台服务 + 通知栏）     │
│  filesDir/linux/                   │
│   ├─ proot（aarch64 静态）          │
│   ├─ rootfs/（Ubuntu22.04+Node+Chromium）
│   └─ app/（fuckds 完整项目，可写）    │
│        proot -r rootfs -b app:/opt/fuckds → node src/index.mjs
└───────────────────────────────────┘
```

## 一、GitHub Actions 打包

1. 把整个项目推到 GitHub 仓库（含 `.github/`、`android-shell/`、`scripts/ci/`、`scripts/android/`）。
2. 仓库页 → **Actions** → 左侧工作流 **fuckds Android APK（完整 Linux 环境）** → **Run workflow**
   （可指定 Node 版本，默认 20.18.1）。推 `v*` 标签也会自动触发。
3. 约 20~40 分钟后，Job 底部 **Artifacts** 里下载 `fuckds-android-apk`，
   解压得到 `fuckds-android-<时间戳>.apk`。

构建内容（全自动）：

| 步骤 | 说明 |
| --- | --- |
| 依赖 | `npm ci`（跳过浏览器下载）+ `npm --prefix web ci && run build` 生成 web/dist |
| PoW | `CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build ./cmd/powd` → `bin/ds-pow`（原生速度，和电脑上一致） |
| app.tar.gz | 项目 + node_modules + ds-pow + android-run.sh + 预置 .env |
| rootfs | QEMU 模拟 arm64 容器：Ubuntu 22.04 + Node 20（官方 arm64 包）+ `npx playwright install --with-deps chromium`（linux-arm64 构建）+ 中文字体等全套依赖库 |
| proot | proot-me 官方 aarch64 静态二进制 |
| 签名 | 首次自动生成 keystore，GitHub cache 持久化 → 升级覆盖安装不会丢数据 |

APK 约 250~400MB（Chrome 本体占大头）。

### 本地（Linux 机器）打包

装好 `node/go/docker/keytool/gradle/Android SDK(build-tools 34.0.0)` 后：

```bash
bash scripts/ci/build-apk.sh
```

## 二、安装与使用

1. 侧载安装 APK（允许未知来源）。仅支持 **arm64** 手机，Android 7.0+。
2. 打开 App → 首次启动会在通知栏显示进度（解压约 5~10 分钟，仅第一次）→
   自动打开 `http://127.0.0.1:8787/admin/` 控制台。
3. 在控制台「账号」页粘贴 DeepSeek 网页版 `userToken`（网页 F12 →
   `JSON.parse(localStorage.getItem('userToken')).value`），保存并校验。
4. 立即可用：
   - 手机本机：OpenAI 客户端 `base_url=http://127.0.0.1:8787/v1`，Anthropic 客户端
     `base_url=http://127.0.0.1:8787`
   - 局域网其他设备：把控制台设置里的 `HOST` 改为 `0.0.0.0` 后重启，用
     `http://<手机IP>:8787/v1` 调用
5. 通知栏有「停止服务」按钮；想彻底关掉也可以在系统设置里强行停止 App。

引擎行为（预置 `ENGINE=auto`）：配好 token 后默认 **direct 直连**（~1.2–1.9s/轮）；
没有 token 或关掉 ds-pow 时自动回退 **browser 引擎**（Chromium 完整可用，~2.2–2.9s/轮）。
控制台里可随时切换 `direct / auto / browser`。

## 三、目录与数据（手机内部）

- 环境与项目：`/data/data/com.fuckds.app/files/linux/`（rootfs、app、proot）
- token 号池：`…/linux/app/data/auth.json`；`.env` 配置：`…/linux/app/.env`
- 日志：`…/linux/app/logs/node.log`（服务）与 `logs/container.log`（proot 进程）
- **覆盖安装新版 APK 时会保留 data/ 与 .env**；卸载或清除数据才会丢失。

## 四、常见问题

- **首次启动慢**：rootfs 200MB+ 解压，通知栏有进度；之后秒开。
- **WebView 一直转圈**：等通知栏变成「服务运行中」；仍不行就清后台后重开 App，
  或看日志 `logs/node.log`。
- **DNS 不通**：App 启动时写入 `223.5.5.5 / 119.29.29.29 / 8.8.8.8` 并绑定进 rootfs；
  特殊网络可自行改 `resolv.conf`（源码 `BootService.spawn()` 里）。
- **browser 引擎慢**：proot + 无 GPU，Chromium 启动/页面加载比桌面慢属正常；
  日常使用推荐 direct 引擎。
- **targetSdk=28 的原因**：Android 10 起禁止 targetSdk≥29 的应用执行私有目录里的
  二进制；28 才能 exec proot/node。侧载安装不受影响。
- **想改固定配置**：改 `scripts/android/env.android` 后重新打包；运行时用 `/admin` 改。

## 五、目录说明

```
.github/workflows/build-android-apk.yml  GitHub Actions 工作流
android-shell/                           Android 壳工程（纯框架 API，无第三方依赖）
  app/src/main/java/com/fuckds/app/
    MainActivity.java                    WebView 打开 /admin
    BootService.java                     前台服务：解压 + proot 启动 + 通知栏控制
    TarGz.java                           tar.gz 流式解包器（ustar/GNU L/PAX/链接）
scripts/ci/
  build-apk.sh                           总流水线（本地 / CI 通用）
  make-app-tarball.sh                    组装 app.tar.gz
  build-rootfs.sh                        docker arm64 rootfs 构建
  rootfs-inner.sh                        rootfs 内部安装脚本
  sign-apk.sh                            zipalign + apksigner
scripts/android/
  android-run.sh                         proot 内的运行时入口（随 APK 分发）
  env.android                            预置 .env（完整双引擎）
```
