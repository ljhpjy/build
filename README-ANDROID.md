# fuckds Android（参考 IDAdroid 架构重写）

把 fuckds **完整**跑在安卓上：proot 挂载 Ubuntu 22.04 arm64 rootfs，
内含 Node.js 20 + Playwright Chromium（linux-arm64）+ Go 交叉编译的 `bin/ds-pow` +
完整项目（**browser / direct 双引擎，源码零改动**）。

架构与 IDAdroid 一致：**小 APK + 独立 rootfs 大文件**，安装后由 App 内导入。

## 一、GitHub Actions 打包（产物两个文件）

1. 把仓库推到 GitHub；Actions → **fuckds Android APK（完整 Linux 环境）** → Run workflow。
2. Job 完成后在 **Artifacts** 下载 `fuckds-android.zip`，解压得到：
   - `fuckds-android-<时间戳>.apk`（几 MB，壳 + proot）
   - `fuckds-rootfs.tar.xz`（约 450MB，完整 Linux 环境 + 项目）

| 步骤 | 说明 |
| --- | --- |
| 依赖 | `npm ci`（服务端）+ web 前端重建锁文件并构建（vite8/rolldown 原生绑定跨平台坑） |
| PoW | `GOOS=linux GOARCH=arm64 go build ./cmd/powd` → 原生速度 |
| rootfs | QEMU/arm64 容器：Ubuntu 22.04 + Node 20 + Chromium（linux-arm64）+ 字体/依赖库 + **/opt/fuckds 完整项目** → `docker export | xz -9` |
| proot | 三路回退下载 aarch64 proot（GitHub API / 固定 URL / Termux deb），打进 APK 小 asset |
| APK | Gradle 构建（commons-compress + tukaani xz 解压依赖），zipalign + apksigner |

## 二、安装与使用（App 五页签原生 UI）

1. 安装 APK，把 `fuckds-rootfs.tar.xz` 放到手机任意目录（下载目录即可）；
2. **环境页**：点「导入 fuckds-rootfs.tar.xz」→ 文件选择器选文件 →
   应用内百分比进度条解压（约 5~15 分钟，仅首次）→ 完成后提示；
3. **服务页**：点「启动服务」→ 状态实时显示端口（冲突自动随机换端口重试）→
   变“服务运行中”后：
   - 「刷新运行详情」看引擎/账号数/运行时长；
   - 「复制 API 地址」给 OpenAI/Anthropic 客户端用；
   - 「打开网页控制台」浏览器打开完整版 /admin（Playground、完整日志等）；
4. **账号页**：粘贴 DeepSeek 网页版 userToken（多个用空格/换行分隔）→
   替换全部/追加/校验/删除 → 保存后弹窗**一键重启服务**生效；
5. **配置页**：ENGINE（direct/auto/browser）、HOST、PORT、API_KEY、CONCURRENCY
   读取/保存 → 同样弹窗重启生效。
   - **改 IP/端口**：HOST 改 `0.0.0.0` 可让局域网其它设备访问；
     PORT 留空=自动模式（默认 18787，冲突自动随机换端口）；
     PORT 填具体值=固定端口（被占用会直接报错提示换一个）；
6. **日志页**：服务日志（API）与本地启动日志（服务没起来时也能诊断）。

- 通知栏有常驻通知（含“停止服务”）；App 全部管理功能都是原生界面；
- 服务不会开机自启：重启手机后打开 App → 服务页点启动即可（rootfs 已导入不用重来）；
- 局域网使用：配置页把 HOST 改为 `0.0.0.0` 并重启，再用「复制 API 地址」
  里的端口号拼 `http://<手机IP>:<端口>/v1` 调用。

## 三、目录说明

```
.github/workflows/build-android-apk.yml  GitHub Actions 工作流
android-shell/                           安卓壳工程（Java，无 androidx）
  MainActivity.java                       导入 rootfs（SAF+进度条）/ 启动 / 打开控制台
  BootService.java                        前台服务：proot 启动 + 随机端口重试 + healthz 探活
  Importer.java                           tar.xz/tar.gz 解压（commons-compress + tukaani）
scripts/ci/
  build-apk.sh                            总流水线
  build-rootfs.sh                         arm64 rootfs 构建 + 导出 xz
  rootfs-inner.sh                         rootfs 内：依赖 + Node + Chromium + /opt/fuckds
  sign-apk.sh                             zipalign + apksigner
scripts/android/
  android-run.sh                          proot 内运行时入口（随 rootfs 分发）
  env.android                             预置 .env（ENGINE=auto 完整双引擎）
```

## 四、常见问题

- **解压很慢**：xz 压缩比高、解压慢属正常，进度条有百分比；请勿退出 App。
- **服务启动失败**：界面上会一直显示原因（如端口冲突重试、超时），照提示处理；
- **控制台打不开**：确认状态是“服务运行中”，或用手机浏览器直接访问状态里显示的地址；
- **换端口**：冲突时自动随机重试 10 次；成功后记住端口，下次复用。
