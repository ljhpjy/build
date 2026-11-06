#!/usr/bin/env bash
# fuckds → Android APK 完整打包流水线（在 GitHub Actions 或任意 Linux 机器上运行）
#
# 产物：fuckds-android-<时间戳>.apk
# 内容：proot + Ubuntu 22.04 arm64 rootfs + Node.js 20 + Playwright Chromium(linux-arm64)
#       + Go 交叉编译的 bin/ds-pow + 完整项目（browser/direct 双引擎，源码零改动）
#
# 前置依赖：node npm go docker keytool gradle；Android SDK build-tools 34.0.0（zipalign/apksigner）
set -Eeuo pipefail
cd "$(dirname "$0")/../.."   # 仓库根目录

NODE_VERSION="${NODE_VERSION:-20.18.1}"
PROOT_URL="${PROOT_URL:-https://github.com/proot-me/proot-static-build/releases/download/v5.4.0/proot-v5.4.0-aarch64-static}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

log() { printf '\n\033[1;36m==>\033[0m %s\n' "$*"; }

for c in node npm go docker keytool gradle; do
  command -v "$c" >/dev/null 2>&1 || { echo "缺少命令: $c"; exit 1; }
done

# 1. 服务端 + 前端依赖
#    服务端 playwright 是纯 JS，npm ci 即可（跳过浏览器下载，浏览器在 rootfs 里装）；
#    前端 vite 8 基于 rolldown，带平台原生绑定。仓库里的 web/package-lock.json 生成于
#    其它平台，带旧锁文件时 npm ci/install 都会漏装当前平台绑定（npm/cli#4828）——
#    只能在 CI 工作区里删掉锁文件重新生成（不提交，不影响仓库）。
log "安装 Node 依赖（PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1）"
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm ci

log "安装前端依赖（重建锁文件以适配当前平台原生绑定）"
(
  cd web
  rm -rf node_modules package-lock.json
  npm install --no-audit --no-fund
  # 兜底：显式补装当前平台的 rolldown 原生绑定（版本与已安装的 rolldown 一致）
  BINDING_VERSION="$(node -p "require('./node_modules/rolldown/package.json').version")"
  npm install --save-dev --no-audit --no-fund "@rolldown/binding-linux-x64-gnu@${BINDING_VERSION}" || true
)

log "构建前端 web/dist"
npm --prefix web run build

# 2. 交叉编译 PoW 求解器（linux/arm64 静态，跑在 proot 里）
log "编译 bin/ds-pow（GOOS=linux GOARCH=arm64）"
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags '-s -w' -o bin/ds-pow ./cmd/powd
ls -lh bin/ds-pow

# 3. 组装应用 tarball：项目 + node_modules + ds-pow + 启动脚本 + 预置 .env
log "组装 app.tar.gz"
bash scripts/ci/make-app-tarball.sh

# 4. 构建 Linux rootfs（docker 内 arm64 Ubuntu：Node + Chromium + 依赖库）
log "构建 payload-rootfs.tar.gz（docker/arm64，耗时较长）"
bash scripts/ci/build-rootfs.sh

# 5. 下载 proot（aarch64，三路自动回退）
log "下载 proot（aarch64）"
mkdir -p android-shell/app/src/main/assets/payload
PROOT_DEST=android-shell/app/src/main/assets/payload/proot
PROOT_SRC=""
try_fetch() {  # try_fetch <url>
  if curl -fsSL --connect-timeout 30 "$1" -o "$PROOT_DEST.dl" 2>/dev/null; then
    PROOT_SRC="$1"
    mv -f "$PROOT_DEST.dl" "$PROOT_DEST"
  else
    rm -f "$PROOT_DEST.dl"
  fi
}
# 5.1 GitHub 最新 release：动态找 aarch64 static 资产（优先非 tar 包）
GH_JSON=$(curl -fsSL --connect-timeout 30 \
  "https://api.github.com/repos/proot-me/proot-static-build/releases/latest" 2>/dev/null || true)
ASSET_URL=$(printf '%s' "$GH_JSON" | tr ',' '\n' \
  | sed -n 's/.*"browser_download_url": *"\([^"]*aarch64[^"]*static[^"]*\)".*/\1/p' \
  | grep -v '\.tar\.' | head -1)
[ -n "$ASSET_URL" ] && try_fetch "$ASSET_URL"
# 5.2 固定 URL 猜测（老版本兼容）
[ -z "$PROOT_SRC" ] && try_fetch "${PROOT_URL}"
[ -z "$PROOT_SRC" ] && try_fetch "https://github.com/proot-me/proot-static-build/releases/latest/download/proot-v5.4.0-aarch64-static"
# 5.3 Termux 官方仓库：按 Packages 索引定位 deb，解包出 proot（bionic 链接，同样可在应用目录执行）
if [ -z "$PROOT_SRC" ]; then
  log "GitHub 无可用资产，改用 Termux 仓库的 proot"
  DEB_NAME=$(curl -fsSL --connect-timeout 30 \
    "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages" 2>/dev/null \
    | awk '/^Package: proot$/{f=1} f && /^Filename:/{print $2; exit}')
  if [ -n "$DEB_NAME" ]; then
    curl -fsSL --connect-timeout 60 "https://packages.termux.dev/apt/termux-main/${DEB_NAME}" -o /tmp/proot.deb
    TMPD=$(mktemp -d)
    ( cd "$TMPD" && ar x /tmp/proot.deb data.tar.xz \
      && tar -xJf data.tar.xz ./data/data/com.termux/files/usr/bin/proot )
    cp "$TMPD/data/data/com.termux/files/usr/bin/proot" "$PROOT_DEST"
    rm -rf "$TMPD" /tmp/proot.deb
    PROOT_SRC="termux:${DEB_NAME}"
  fi
fi
if [ -z "$PROOT_SRC" ]; then
  echo "错误：无法下载 proot（GitHub/termux 均失败），请检查网络或更换 URL" >&2
  exit 1
fi
# 校验下载物是 ELF（防止拿到 HTML 错误页）
MAGIC=$(od -An -tx1 -N4 "$PROOT_DEST" | tr -d ' \n')
if [ "$MAGIC" != "7f454c46" ]; then
  echo "错误：proot 文件不是 ELF（MAGIC=$MAGIC，来源=$PROOT_SRC）" >&2
  exit 1
fi
chmod +x "$PROOT_DEST"
ls -l "$PROOT_DEST"
echo "proot 来源: $PROOT_SRC"

# 6. payload 放进 APK assets
log "拷贝 payload 到 assets"
cp app.tar.gz android-shell/app/src/main/assets/payload/app.tar.gz
cp payload-rootfs.tar.gz android-shell/app/src/main/assets/payload/rootfs.tar.gz

# 7. 签名证书（首次自动生成；GitHub Actions 会用 cache 持久化，保证覆盖安装）
if [ ! -f android-shell/fuckds-release.jks ]; then
  log "生成签名证书（首次）"
  keytool -genkeypair -keystore android-shell/fuckds-release.jks -alias fuckds \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass fuckds-android -keypass fuckds-android \
    -dname "CN=fuckds, OU=android, O=fuckds, L=NA, ST=NA, C=CN"
fi

# 8. Gradle 构建 APK
log "Gradle assembleRelease"
(cd android-shell && gradle --no-daemon --console=plain assembleRelease)

# 9. zipalign + apksigner
if [ -n "$SDK" ] && [ -d "$SDK/build-tools/34.0.0" ]; then
  export PATH="$SDK/build-tools/34.0.0:$PATH"
fi
log "签名 APK"
bash scripts/ci/sign-apk.sh

log "完成：$(ls -1 fuckds-android-*.apk 2>/dev/null | tail -1)"
