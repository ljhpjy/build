# Android 版对上游源码做的改动

这个目录是 [xingling66666/fuckds](https://github.com/xingling66666/fuckds) 的快照，
只改了下面几处（其余文件与上游一致）：

| 文件 | 改动 | 原因 |
| --- | --- | --- |
| `src/engine.mjs` | `createEngine` 改为 async；browser 驱动改为动态 import；Android 上强制 direct | APK 里没有 playwright / Chromium；direct 模式仍要完整可用 |
| `src/index.mjs` | `await createEngine(...)`；启动提示适配 Android | 同上 |
| `src/admin/routes.mjs` | 重启接口在 Android 上改为 `process.exit(0)`；设置页隐藏 browser 选项；overview 增加 `platform` | 由前台服务自动拉起；避免用户选到不可用引擎 |
| `src/direct/client.mjs` | 未配置 token 时的错误提示改为「去控制台配置」 | APK 里没有 `npm run auth` |
| `src/server.mjs` | 首页登录态提示适配 Android | 同上 |

新增：

- `.env.android`：首次启动复制为 `app/.env` 的默认配置；
- `pow-src/`：从上游根目录复制过来的 Go PoW 源码，构建 APK 时交叉编译成 Android PIE 可执行文件。

## 功能差异

- `direct` 引擎：**完整保留**（OpenAI / Anthropic、流式、tools、思考、图片、超长转附件、号池、控制台、日志、配置、重启）。
- `browser` 引擎：需要桌面版 Chromium + Playwright，APK 中没有，选到会自动回退 `direct`。
- 原仓库的 `npm run up/down/status`、`npm run auth`、`scripts/*` 这些命令行工具没有打包进 APK；
  对应能力都在网页控制台里（`/admin`）。
