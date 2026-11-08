#!/data/data/com.termux/files/usr/bin/bash
# fuckds · Termux 卸载脚本
#
# 一键删除项目 + 可选删除安装器自己装过的 Termux 包。
# 不会删除 Termux 本体、bash/tar/termux-tools 等基础包，也不会动 ~/storage。
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SELF="$HERE/$(basename "${BASH_SOURCE[0]}")"
PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
TARGET=""
PURGE=0
YES=0
KEEP=0
FORCE=0

usage() {
  cat <<'EOF'
用法：
  ./fuckds uninstall [选项]
  bash uninstall-termux.sh [--target DIR] [选项]

选项：
  --yes, -y            不再询问，直接执行
  --purge-packages     额外删除安装器记录过的 chromium / x11-repo，并 apt autoremove
  --keep-data          删除前把 data/ 和 .env 备份到 ~/fuckds-backup-<时间>
  --target DIR         指定项目目录（默认自动识别）
  --force              即使目录看起来不完整也强制删除
  -h, --help           显示本帮助

说明：
  --purge-packages 只认 data/termux-installed.txt 里的包；如果 Chromium 是你自己
  手动装的（没有记录），不会误删，只打印手动命令。
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --target)
      TARGET=${2:-}
      if [ -z "$TARGET" ]; then echo "❌ --target 需要一个值" >&2; exit 2; fi
      shift 2
      ;;
    --target=*)
      TARGET=${1#*=}
      if [ -z "$TARGET" ]; then echo "❌ --target= 后面不能为空" >&2; exit 2; fi
      shift
      ;;
    --purge-packages) PURGE=1; shift ;;
    --yes|-y) YES=1; shift ;;
    --keep-data) KEEP=1; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --*) echo "❌ 未知参数：$1" >&2; usage; exit 2 ;;
    *) echo "❌ 未知位置参数：$1" >&2; usage; exit 2 ;;
  esac
done

if [ -z "$TARGET" ]; then
  if [ -f "$HERE/package.json" ] && [ -d "$HERE/src" ]; then
    TARGET=$HERE
  elif [ -f "$HERE/../../package.json" ] && [ -d "$HERE/../../src" ]; then
    TARGET=$(cd "$HERE/../.." && pwd)
  else
    TARGET=$HOME/fuckds
  fi
fi

if [ ! -d "$TARGET" ]; then
  echo "❌ 目录不存在：$TARGET" >&2
  exit 1
fi
TARGET=$(cd "$TARGET" && pwd)

case "$TARGET" in
  /|/data|/data/data|/data/data/com.termux|/data/data/com.termux/files|/data/user|/storage|/storage/emulated|/sdcard|"$HOME"|"$PREFIX")
    echo "❌ 拒绝删除危险路径：$TARGET" >&2
    exit 1
    ;;
  /system*|/vendor*|/product*|/apex*|/proc*|/dev*|/sys*)
    echo "❌ 拒绝删除系统路径：$TARGET" >&2
    exit 1
    ;;
esac

if [ "$FORCE" != 1 ]; then
  if [ ! -f "$TARGET/package.json" ] || [ ! -d "$TARGET/src" ]; then
    echo "❌ $TARGET 看起来不是 fuckds 项目目录（可加 --force 强制删除）" >&2
    exit 1
  fi
fi

# 从项目目录里直接执行时，先把脚本复制到 TMPDIR，避免删目录把正在读的脚本一起删掉。
case "$SELF" in
  "$TARGET"/*)
    if [ "${FUCKDS_UNINSTALL_REEXEC:-0}" != 1 ]; then
      TMP_SELF=$(mktemp "${TMPDIR:-$PREFIX/tmp}/fuckds-uninstall.XXXXXX")
      cp "$SELF" "$TMP_SELF"
      chmod 755 "$TMP_SELF"
      FUCKDS_UNINSTALL_REEXEC=1 exec "$TMP_SELF" --target "$TARGET" "$@"
    fi
    ;;
esac

echo "==> 目标：$TARGET"
echo "    将删除项目目录：runtime / node_modules / src / scripts / web/dist / test / data / .env"
if [ "$KEEP" = 1 ]; then
  echo "    保留：data/ 和 .env 会先备份到 ~/fuckds-backup-<时间>"
fi
if [ "$PURGE" = 1 ]; then
  echo "    额外：删除安装器记录过的 chromium / x11-repo，并 apt autoremove"
  echo "    注意：如果你还装了其它 X11 包，卸载 x11-repo 可能影响它们；不加 --purge-packages 则不动系统包。"
fi

if [ "$YES" != 1 ]; then
  printf '继续？[y/N] '
  read -r answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *) echo "已取消"; exit 0 ;;
  esac
fi

# 1. 停服务 / 释放 wake lock
echo "==> 停止服务"
if [ -x "$TARGET/fuckds" ]; then
  "$TARGET/fuckds" down >/dev/null 2>&1 || true
fi
if command -v termux-wake-unlock >/dev/null 2>&1; then
  termux-wake-unlock >/dev/null 2>&1 || true
fi
if [ -f "$TARGET/data/server.pid" ]; then
  PID=$(cat "$TARGET/data/server.pid" 2>/dev/null || true)
  case "$PID" in
    ''|*[!0-9]*) ;;
    *) kill "$PID" 2>/dev/null || true ;;
  esac
fi

# 2. Playwright / Chromium 运行时临时目录
TMP=${TMPDIR:-$PREFIX/tmp}
rm -rf "$TMP"/playwright_* "$TMP"/.org.chromium.Chromium.* 2>/dev/null || true

# 3. 可选备份 data/.env
if [ "$KEEP" = 1 ]; then
  BACKUP="$HOME/fuckds-backup-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$BACKUP"
  if [ -d "$TARGET/data" ]; then cp -R "$TARGET/data" "$BACKUP/data"; fi
  if [ -f "$TARGET/.env" ]; then cp "$TARGET/.env" "$BACKUP/.env"; chmod 600 "$BACKUP/.env" 2>/dev/null || true; fi
  echo "==> 已备份到 $BACKUP"
fi

# 4. 可选删除安装器记录过的 Termux 包
if [ "$PURGE" = 1 ]; then
  MARK="$TARGET/data/termux-installed.txt"
  if [ -f "$MARK" ]; then
    echo "==> 安装器记录的项目包："
    sed 's/^/    /' "$MARK"
    if command -v pkg >/dev/null 2>&1; then
      if grep -qx chromium "$MARK"; then
        echo "==> 卸载 chromium"
        pkg uninstall -y chromium >/dev/null 2>&1 \
          || apt-get remove -y chromium >/dev/null 2>&1 \
          || echo "⚠️ chromium 卸载失败，请手动检查"
      fi
      if grep -qx x11-repo "$MARK"; then
        echo "==> 卸载 x11-repo"
        pkg uninstall -y x11-repo >/dev/null 2>&1 \
          || apt-get remove -y x11-repo >/dev/null 2>&1 \
          || echo "⚠️ x11-repo 卸载失败，请手动检查"
      fi
      if command -v apt-get >/dev/null 2>&1; then
        echo "==> apt autoremove（清理自动依赖）"
        apt-get autoremove -y >/dev/null 2>&1 || true
      fi
    else
      echo "⚠️ 找不到 pkg，跳过系统包卸载"
    fi
  else
    echo "==> 没有 data/termux-installed.txt，无法确认哪些包是安装器装的"
    echo "    如果你确定不再用 Chromium，可手动："
    echo "      pkg uninstall chromium"
    echo "      pkg uninstall x11-repo"
    echo "      apt autoremove -y"
  fi
fi

# 5. 删除全局 CLI wrapper（只删指向本项目的）
WRAPPER="$PREFIX/bin/fuckds"
REMOVE_WRAPPER=0
if [ -f "$TARGET/data/cli-wrapper.txt" ]; then
  W=$(cat "$TARGET/data/cli-wrapper.txt" 2>/dev/null || true)
  if [ "$W" = "$WRAPPER" ]; then REMOVE_WRAPPER=1; fi
fi
if [ -f "$WRAPPER" ] \
   && grep -q 'fuckds termux cli wrapper' "$WRAPPER" 2>/dev/null \
   && grep -qF "$TARGET/fuckds" "$WRAPPER" 2>/dev/null; then
  REMOVE_WRAPPER=1
fi
if [ "$REMOVE_WRAPPER" = 1 ]; then
  rm -f "$WRAPPER"
  echo "==> 已删除命令：$WRAPPER"
fi

# 6. 删除项目目录
echo "==> 删除 $TARGET"
rm -rf "$TARGET"
if [ -d "$TARGET" ]; then
  echo "❌ 删除失败：$TARGET" >&2
  exit 1
fi

cat <<EOF
✅ 已卸载 fuckds：$TARGET

没有删除（需要的话手动处理）：
  - Termux 本体和基础包：bash / tar / termux-tools 等
  - ~/storage（termux-setup-storage 创建，全局共用）
  - 你下载的 fuckds-termux-aarch64.tar.gz / artifact zip
EOF
