# fuckds · Termux 原生包（aarch64）

把 fuckds 完整跑在 **Termux 原生环境**里：不装 Ubuntu、不用 proot、不需要 `npm install`、不需要 glibc。
产物是一套 **aarch64 原生二进制运行时 + 完整项目**：

```
fuckds/
  fuckds                 启动器（install/up/down/restart/status/logs/web/auth/smoke/doctor/test/uninstall）
  install-termux.sh      一键安装
  uninstall-termux.sh    一键卸载
  runtime/bin/node       Termux 官方 Node 运行时（aarch64，Bionic ABI）
  runtime/lib/*.so       Node 依赖的 Termux 库（已解引用符号链接）
  bin/ds-pow             Go 交叉编译的 PoW 求解器（aarch64 静态二进制）
  src/ scripts/ test/    完整源码（OpenAI / Anthropic 双协议、工具、图片、附件、号池、后台）
  node_modules/          Playwright 等运行时依赖（已装好）
  web/dist/              网页控制台构建产物（/admin）
  bundled-debs/          Chromium + 依赖的离线 .deb（bundle_chromium=true 时）
  .env                   Termux 预置配置
```

> **为什么不是单个文件？** Node + Playwright + Chromium 不是静态链接的 CLI，没法塞进一个 ELF；
> 这里做的是「自带 Node 运行时的原生目录」，等价于把二进制和资源一起发布。
> 目录必须在 Termux `$HOME` 下运行（`/sdcard` 是 noexec 挂载，不能直接执行）。

## 零、GitHub Actions 构建

仓库里的 `.github/workflows/build.yml` 会在 `ubuntu-latest` 上：

1. `npm ci` + 构建网页控制台 `web/dist`；
2. `go test ./...` + 交叉编译 **aarch64 静态 `bin/ds-pow`**；
3. 从 Termux 官方仓库解包 **aarch64 Node 运行时 + 全部依赖 .so**；
4. （默认 `bundle_chromium=true`）下载 Chromium + 全部 Termux 依赖 `.deb` 到 `bundled-debs/`；
5. 把完整源码、`node_modules`(Playwright)、`web/dist`、`test`、启动器打包成 `fuckds-termux-aarch64.tar.gz`；
6. 做 Node ELF 架构/解释器/缺失库检查，再上传 Artifacts。

手动触发：仓库 **Actions** → **Build Termux native bundle (aarch64)** → **Run workflow**。

| 输入 | 默认 | 说明 |
| --- | --- | --- |
| `termux_node_package` | `nodejs-lts` | 也可选 `nodejs`；自动回退 |
| `playwright_version` | 空 | 覆盖 `package-lock` 里的 Playwright 版本（Termux Chromium 太新/太旧时可试） |
| `bundle_chromium` | `true` | 把 Chromium + 全部 Termux 依赖 `.deb` 打进包，手机离线安装；关闭则手机上联网 `pkg install` |

下载 Artifact `fuckds-termux-aarch64`，里面就是下面安装步骤用的 `fuckds-termux-aarch64.tar.gz`（含 `.sha256`）。

> `bundle_chromium=true` 会让 tar 增加 Chromium 依赖的体积（通常几十到上百 MB）；
> 如果 Actions Artifact 超过大小限制，就把这个开关关掉，手机联网 `pkg install chromium` 即可。

---

## 一、安装（首次 1~3 分钟，Chromium 另算）

### Termux 需要装什么

**项目本体不需要额外装 Node / npm / Go / Python**：Node 运行时、依赖 `.so`、Playwright、`web/dist`、`bin/ds-pow` 全在包里。

| 需要 | 说明 |
| --- | --- |
| Termux（F-Droid / GitHub） | aarch64 设备；不要用 Play 商店旧版 |
| `bash` / `tar` / `termux-tools` | Termux 默认自带；`tar` 缺失时 `pkg install -y tar` |
| `x11-repo` + `chromium`（可选） | 只有 browser 引擎需要；包里有 `bundled-debs` 时离线安装，否则联网 `pkg install` |
| `procps` / `file`（可选） | `server-ctl` 的 pgrep/ps、doctor 的详细文件信息；不装也能跑 |

`termux-setup-storage` 只是为了读 `/sdcard/Download`，只需执行一次。

### 一键安装

前提：已把 `fuckds-termux-aarch64.tar.gz` 放到 Termux 能读的位置。

```bash
termux-setup-storage
cp /sdcard/Download/fuckds-termux-aarch64.tar.gz ~/
cd ~
tar -xzf fuckds-termux-aarch64.tar.gz

# 一条命令：复制到 ~/fuckds + 修复权限 + .env + Chromium + 写 token + 启动
bash fuckds/install-termux.sh --with-chromium --token '<userToken>' --start

# 也可以用统一 CLI（解压目录里就能跑；注意路径是 fuckds/fuckds）：
bash fuckds/fuckds install --with-chromium --token '<userToken>' --start
```

安装完成后会创建 Termux 全局命令 `$PREFIX/bin/fuckds`，之后可以直接：

```bash
fuckds status
fuckds logs -f
fuckds web
fuckds uninstall --yes
```

（`~/fuckds/fuckds` 仍然可用；全局 wrapper 只指向本项目，卸载时会自动删掉。）

安装器选项：

| 选项 | 作用 |
| --- | --- |
| `--with-chromium` | 安装 `x11-repo` + `chromium`，启用 browser 引擎；不装则只用 direct |
| `--token '<token>'` | 写入 `data/auth.json`（不联网校验，追加到号池最前面） |
| `--token='<token>'` | 同上 |
| `--start` | 安装完立即 `./fuckds up` |
| `目标目录` | 默认 `~/fuckds`，可传 `~/apps/fuckds` |

说明：

- **离线 Chromium**：如果构建时 `bundle_chromium=true`，`--with-chromium` 会直接 `dpkg -i bundled-debs/*.deb`，不联网；没有 `bundled-debs` 才走 `pkg install`。
- 不装 Chromium：direct 引擎（默认、功能最全）完全可用。
- 不传 `--token`：之后 `cd ~/fuckds && ./fuckds auth '<userToken>'`。
- 不传 `--start`：之后 `cd ~/fuckds && ./fuckds up`。
- `--token` 会进 shell history；介意就省略，用 `./fuckds auth`。
- 安装器只把 `x11-repo`/`chromium` 记到 `data/termux-installed.txt`，卸载时只删它自己装的。
- 如果文件管理器把 tar 解压到了 `/sdcard/...`，可以直接在那里执行
  `bash /sdcard/.../fuckds/install-termux.sh --with-chromium`，脚本会复制到 `~/fuckds` 并修权限。

## 二、拿 userToken 并启动（手动方式）

1. 浏览器打开 <https://chat.deepseek.com> 并登录；
2. `F12` → Application → Local Storage → `https://chat.deepseek.com` → `userToken`；
3. 复制 `{"value":"xxxxx","__version":"0"}` 里的 `value`。

```bash
cd ~/fuckds

# 写入号池（可一次传多个，空格分隔）
./fuckds auth '<userToken>'

# 后台启动（自动 termux-wake-lock）
./fuckds up

# 看状态
./fuckds status
```

打开网页控制台：<http://127.0.0.1:8787/admin>
（状态 / 号池 / 测试 / 日志 / 配置 / 重启都在里面。）

OpenAI 兼容地址：`http://127.0.0.1:8787/v1`  
Anthropic 兼容地址：`http://127.0.0.1:8787`

## 三、命令

| 命令 | 作用 |
| --- | --- |
| `./fuckds install` / `fuckds install` | 安装到 `~/fuckds`（支持 `--with-chromium --token ... --start`） |
| `./fuckds up` | 后台启动（pid `data/server.pid`，日志 `data/server.log`） |
| `./fuckds down` | 停止 |
| `./fuckds restart` | 重启（改完 `.env` / token 后用） |
| `./fuckds status` | 查看引擎 / 账号 / 运行时长 |
| `./fuckds auth <token...>` | 写入/追加号池；`--list` 查看，`--check` 校验 |
| `./fuckds smoke` | 端到端自检（会真的访问 DeepSeek） |
| `./fuckds doctor` | 检查自带 Node / ds-pow / Chromium / 配置 |
| `./fuckds serve` | 前台启动（调试用，Ctrl-C 退出） |
| `./fuckds logs [-f] [-n 200]` | 查看 / 跟踪 `data/server.log` |
| `./fuckds web` | 打印并打开 `/admin` 控制台 |
| `./fuckds test` | 跑项目测试 |
| `./fuckds version` | 版本号 |
| `./fuckds uninstall` | 卸载项目；加 `--yes --purge-packages` 连安装器装的 Chromium/x11-repo 一起删 |

> 网页控制台的“重启 / 停止”按钮会通过 `runtime/bin/node scripts/server-ctl.mjs restart|down` 执行，
> 和命令行 `./fuckds restart|down` 是同一条路径。停止后网页会断开，重新启动用 `./fuckds up`。

### CLI / WebUI 分工

| 功能 | CLI | WebUI（`/admin`） |
| --- | --- | --- |
| 安装 | `./fuckds install` | - |
| 启动 | `./fuckds up` | -（服务停止时网页不可用） |
| 停止 | `./fuckds down` | 概览页「停止服务」 |
| 重启 | `./fuckds restart` | 概览 / 设置页「重启服务」 |
| 状态 | `./fuckds status` | 概览页 |
| 日志 | `./fuckds logs -f` | 日志页 / 概览最近日志 |
| 配置 | 编辑 `.env` | 设置页（保存后重启） |
| 号池 / 校验 | `./fuckds auth` | 账号页 |
| 测试 | `./fuckds smoke` | Playground 页 |
| 卸载 | `./fuckds uninstall` | - |

## 四、两种引擎

| 引擎 | 依赖 | 说明 |
| --- | --- | --- |
| **direct**（默认，`ENGINE=auto` 有 token 就走） | `bin/ds-pow` | 直连网页内部接口 + Go 解 PoW；支持图片、超长上下文转附件、号池、工具调用，最快 |
| **browser** | Termux Chromium | Playwright 驱动真实网页；不支持附件/图片（项目本身的设计） |

默认配置是 `ENGINE=auto`：有 token + `bin/ds-pow` 就直连，否则回退 browser。
所以 **开箱即用就是功能最全的 direct 引擎**。

### 启用 browser 引擎

```bash
# Chromium 在 Termux 的 x11-repo 里
pkg install -y x11-repo
pkg update -y
pkg install -y chromium
./fuckds doctor          # 应能看到 chromium 路径

# 先单独验证 Chromium 能不能 headless 跑（不经过 Playwright）
"$PREFIX/bin/chromium" --headless --no-sandbox --disable-gpu --dump-dom about:blank
```

启动器会自动探测并导出 `CHROMIUM_EXECUTABLE`，同时设置：

```
PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1
BROWSER_ARGS=--no-zygote --disable-setuid-sandbox --disable-dev-shm-usage --disable-gpu --disable-infobars
```

如果自动探测不到，编辑 `.env`：

```ini
CHROMIUM_EXECUTABLE=/data/data/com.termux/files/usr/bin/chromium
```

然后 `./fuckds restart`。

> 改 `CHROMIUM_EXECUTABLE` / `BROWSER_ARGS` 后：CLI 用 `./fuckds restart`，WebUI 点「重启服务」即可。
> WebUI 的重启/停止会优先调用项目根目录的 `fuckds` 启动器，因此也会重新读 `.env`、重建 Chromium wrapper。

> Termux 的 Chromium 是 Android/Bionic 构建，和 Playwright 自带的 Ubuntu 版 Chromium 不是同一个包；
> 如果遇到 CDP 协议版本不兼容，优先用 direct 引擎（默认、功能更全），
> 或在 Actions 里用 `playwright_version` 输入换一个 Playwright 版本重新打包。

## 五、配置

编辑 `~/fuckds/.env` 后 `./fuckds restart`。常用项：

```ini
ENGINE=auto              # direct / browser / auto
HOST=127.0.0.1           # 局域网访问改成 0.0.0.0（务必设 API_KEY）
PORT=8787
API_KEY=                 # 设置后 OpenAI/Anthropic/后台都要求 Bearer
CONCURRENCY=1            # 手机保持 1
DEEPSEEK_THINKING=always
DEEPSEEK_SEARCH=false
```

完整配置见 `.env.example` / `README.md`，后台“设置”页也能改。

## 六、常见问题

### 1）`Permission denied` / `noexec`

不要把目录放在 `/sdcard` 上直接运行。用安装脚本复制到 `$HOME/fuckds`：

```bash
bash /path/to/fuckds/install-termux.sh
```

### 2）自带 Node 起不来

```bash
./fuckds doctor
```

- 确认设备是 aarch64：`uname -m` 输出 `aarch64`；
- 确认用 Termux 的 `tar -xzf` 解包（保留目录结构），不要用只解压出单个文件的工具；
- `./fuckds doctor` 的 `node:` 行若报 `FAIL (library not found)`，说明解包丢了 `runtime/lib`，重新用 tar 解包；
  （不要直接跑 `runtime/bin/node`，那样不会带上 `LD_LIBRARY_PATH`。）
- 本包自带 Node，不需要 `pkg install nodejs`；装了也不冲突（走绝对路径）。

### 3）browser 引擎报 Chromium 找不到

```bash
pkg install -y x11-repo
pkg update -y
pkg install -y chromium
# 或一条命令（安装器会处理 x11-repo）：
bash install-termux.sh --with-chromium
```

再把 `.env` 的 `ENGINE=browser`（或 `auto` 且没有 ds-pow）后重启。

### 4）后台运行被 Android 杀

- `./fuckds up` 会自动 `termux-wake-lock`；
- 在 Android 设置里把 Termux 的电池优化设为“不限制”；
- 需要长期跑可以装 Termux:Boot，但本包不自动开机自启。

### 5）端口被占用

改 `.env` 的 `PORT=18787`（或其它），`./fuckds restart`。

## 七、更新

保留 `data/`（token、日志）和 `.env`，用安装器覆盖其余文件。不要把新包**直接解压覆盖** `~/fuckds`，
否则包里的默认 `.env` 会先覆盖你的配置：

```bash
cd ~
rm -rf ~/fuckds-update
mkdir -p ~/fuckds-update
tar -xzf fuckds-termux-aarch64.tar.gz -C ~/fuckds-update
bash ~/fuckds-update/fuckds/install-termux.sh ~/fuckds   # 安装器会保留旧 .env 和 data/
rm -rf ~/fuckds-update
cd ~/fuckds && ./fuckds restart
```

## 八、安全

- 默认只监听 `127.0.0.1`，只有本机能访问；
- 改成 `0.0.0.0` 对外提供服务时，**一定设置 `API_KEY`**；
- token 存在 `data/auth.json`（0600），不要把它提交到 git 或发给别人。

## 九、一键卸载（含安装器装的东西）

```bash
cd ~/fuckds

# 只删项目（保留 Chromium / x11-repo）
./fuckds uninstall --yes

# 连安装器装过的 Chromium / x11-repo 一起删，并清理自动依赖
./fuckds uninstall --yes --purge-packages

# 删之前备份 data/ 和 .env 到 ~/fuckds-backup-<时间>
./fuckds uninstall --yes --keep-data
```

会删除：

- `~/fuckds` 整个目录（runtime、node_modules、src、scripts、web/dist、test、`data/auth.json`、日志、`.env`）
- 安装器创建的全局命令 `$PREFIX/bin/fuckds`（只删指向本项目的那个）
- 加 `--purge-packages` 时：`data/termux-installed.txt` 里记录的 `chromium` / `x11-repo`，以及 `apt autoremove` 清理的自动依赖
  （离线安装时，新装的 Chromium 依赖会被 `apt-mark auto` 标记，卸载 `chromium` 后由 `apt autoremove` 回收）

不会删除：

- Termux 本体和基础包（`bash`、`tar`、`termux-tools` 等）
- `~/storage`（`termux-setup-storage` 创建的全局软链）
- 你下载的 `fuckds-termux-aarch64.tar.gz` / artifact zip

> Chromium 如果是你自己手动装的（安装器没记录），`--purge-packages` 不会误删，只打印手动命令。
> 如果项目目录已经被删了，才需要手动清理：
>
> ```bash
> pkg uninstall chromium
> pkg uninstall x11-repo
> apt autoremove -y
> rm -f "$PREFIX/bin/fuckds"
> ```
>
> （不需要 Chromium / x11 时才执行。）
