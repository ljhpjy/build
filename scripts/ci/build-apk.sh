#!/usr/bin/env bash
# fuckds Android 打包流水线（参考 IDAdroid 架构重写）：
#   产物一：小 APK（壳 + proot，不含大文件）
#   产物二：fuckds-rootfs.tar.xz（完整 Linux 环境 + 项目，由 App 内导入解压）
set -Eeuo pipefail
cd "$(dirname "$0")/../.."   # 仓库根目录

NODE_VERSION="${NODE_VERSION:-20.18.1}"
PROOT_URL="${PROOT_URL:-https://github.com/proot-me/proot-static-build/releases/download/v5.4.0/proot-v5.4.0-aarch64-static}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

log() { printf '\n\033[1;36m==>\033[0m %s\n' "$*"; }

for c in node npm go docker keytool gradle xz python3 unzip; do
  command -v "$c" >/dev/null 2>&1 || { echo "缺少命令: $c"; exit 1; }
done

# 1. 依赖
#    服务端 playwright 纯 JS，npm ci；前端 vite8/rolldown 带平台原生绑定，
#    锁文件跨平台会漏装（npm/cli#4828），CI 工作区内重建锁文件。
log "安装 Node 依赖（PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1）"
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm ci

log "安装前端依赖（重建锁文件适配当前平台）"
(
  cd web
  rm -rf node_modules package-lock.json
  npm install --no-audit --no-fund
  BINDING_VERSION="$(node -p "require('./node_modules/rolldown/package.json').version" || true)"
  npm install --save-dev --no-audit --no-fund "@rolldown/binding-linux-x64-gnu@${BINDING_VERSION}" || true
)

log "构建前端 web/dist"
npm --prefix web run build

# 2. 交叉编译 PoW 求解器
log "编译 bin/ds-pow（GOOS=linux GOARCH=arm64）"
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags '-s -w' -o bin/ds-pow ./cmd/powd
ls -lh bin/ds-pow

# 3. 构建 rootfs（arm64 Ubuntu + Node + Chromium + /opt/fuckds 完整项目）
log "构建 fuckds-rootfs.tar.xz（docker/arm64，耗时较长）"
bash scripts/ci/build-rootfs.sh

# 4. 下载 proot（aarch64，三路回退；仅 260KB，打进 APK asset）
log "下载 proot（aarch64）"
mkdir -p android-shell/app/src/main/assets/payload
PROOT_DEST=android-shell/app/src/main/assets/payload/proot
PROOT_SRC=""
try_fetch() {
  if curl -fsSL --connect-timeout 30 "$1" -o "$PROOT_DEST.dl" 2>/dev/null; then
    PROOT_SRC="$1"
    mv -f "$PROOT_DEST.dl" "$PROOT_DEST"
  else
    rm -f "$PROOT_DEST.dl"
  fi
}
GH_JSON=$(curl -fsSL --connect-timeout 30 \
  "https://api.github.com/repos/proot-me/proot-static-build/releases/latest" 2>/dev/null || true)
ASSET_URL=$(printf '%s' "$GH_JSON" | tr ',' '\n' \
  | sed -n 's/.*"browser_download_url": *"\([^"]*aarch64[^"]*static[^"]*\)".*/\1/p' \
  | grep -v '\.tar\.' | head -1 || true)
[ -n "$ASSET_URL" ] && try_fetch "$ASSET_URL"
[ -z "$PROOT_SRC" ] && try_fetch "${PROOT_URL}"
if [ -z "$PROOT_SRC" ]; then
  log "GitHub 无可用资产，改用 Termux 仓库的 proot"
  DEB_NAME=$(curl -fsSL --connect-timeout 30 \
    "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages" 2>/dev/null \
    | awk '/^Package: proot$/{f=1} f && /^Filename:/{print $2; exit}' || true)
  if [ -n "$DEB_NAME" ]; then
    curl -fsSL --connect-timeout 60 "https://packages.termux.dev/apt/termux-main/${DEB_NAME}" -o /tmp/proot.deb
    TMPD=$(mktemp -d)
    if ( cd "$TMPD" && ar x /tmp/proot.deb data.tar.xz \
         && tar -xJf data.tar.xz ./data/data/com.termux/files/usr/bin/proot ); then
      cp "$TMPD/data/data/com.termux/files/usr/bin/proot" "$PROOT_DEST"
      PROOT_SRC="termux:${DEB_NAME}"
    else
      echo "警告：proot deb 解包失败（${DEB_NAME}）" >&2
    fi
    rm -rf "$TMPD" /tmp/proot.deb
  fi
fi
if [ -z "$PROOT_SRC" ]; then
  echo "错误：无法下载 proot（GitHub/termux 均失败）" >&2
  exit 1
fi
MAGIC=$(od -An -tx1 -N4 "$PROOT_DEST" | tr -d ' \n')
if [ "$MAGIC" != "7f454c46" ]; then
  echo "错误：proot 文件不是 ELF（MAGIC=$MAGIC，来源=$PROOT_SRC）" >&2
  exit 1
fi
chmod +x "$PROOT_DEST"
ls -l "$PROOT_DEST"
echo "proot 来源: $PROOT_SRC"

# 5. 签名证书（首次自动生成，GitHub cache 持久化）
if [ ! -f android-shell/fuckds-release.jks ]; then
  log "生成签名证书（首次）"
  keytool -genkeypair -keystore android-shell/fuckds-release.jks -alias fuckds \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass fuckds-android -keypass fuckds-android \
    -dname "CN=fuckds, OU=android, O=fuckds, L=NA, ST=NA, C=CN"
fi

# 6. Gradle 构建小 APK（只有几 KB 资产，不再有大文件）
log "Gradle assembleRelease"
(cd android-shell && gradle --no-daemon --console=plain assembleRelease)

# 7. zipalign + apksigner
if [ -n "$SDK" ] && [ -d "$SDK/build-tools/34.0.0" ]; then
  export PATH="$SDK/build-tools/34.0.0:$PATH"
fi
log "签名 APK"
bash scripts/ci/sign-apk.sh

APK_LIST="$(ls -1 fuckds-android-*.apk 2>/dev/null || true)"
log "完成：$(printf '%s\n' "$APK_LIST" | tail -1)"
ls -lh fuckds-rootfs.tar.xz 2>/dev/null || true
