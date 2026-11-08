#!/data/data/com.termux/files/usr/bin/bash
# fuckds · Termux 安装/迁移脚本
#
# 常用：
#   bash install-termux.sh --with-chromium --token '<userToken>' --start
#
# 说明：把当前目录整个复制到 HOME（/sdcard 是 noexec，不能原地运行），
#       修复执行位，检查自带 Node 运行时，生成默认 .env，
#       可选安装 Chromium、写入 token、立即启动。
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# 从源码仓库的 scripts/termux/ 直接执行时，安装整个项目而不是只有这个子目录。
if [ ! -f "$HERE/package.json" ] && [ -f "$HERE/../../package.json" ]; then
  HERE=$(cd "$HERE/../.." && pwd)
fi
PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
WITH_CHROMIUM=0

pkg_ready() {
  dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q 'install ok installed'
}
START=0
TOKEN=""
DEST=""

usage() {
  cat <<'EOF'
用法：
  bash install-termux.sh [选项] [目标目录]

选项：
  --with-chromium       安装 Termux Chromium（x11-repo），启用 browser 引擎
  --token <userToken>   安装时写入 data/auth.json（不校验，追加到号池最前面）
  --token=<userToken>   同上
  --start               安装完成后立即执行 ./fuckds up
  -h, --help            显示本帮助

示例：
  bash install-termux.sh --with-chromium --token '<userToken>' --start
  bash install-termux.sh ~/apps/fuckds
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --with-chromium)
      WITH_CHROMIUM=1
      shift
      ;;
    --token)
      TOKEN=${2:-}
      if [ -z "$TOKEN" ]; then
        echo "❌ --token 需要一个值" >&2
        exit 2
      fi
      shift 2
      ;;
    --token=*)
      TOKEN=${1#*=}
      if [ -z "$TOKEN" ]; then
        echo "❌ --token= 后面不能为空" >&2
        exit 2
      fi
      shift
      ;;
    --start)
      START=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --*)
      echo "❌ 未知参数：$1" >&2
      usage
      exit 2
      ;;
    *)
      DEST=$1
      shift
      ;;
  esac
done

DEST=${DEST:-$HOME/fuckds}

if [ ! -d "$PREFIX" ]; then
  echo "❌ 请在 Termux 里运行本脚本（找不到 $PREFIX）" >&2
  exit 1
fi

case "$DEST" in
  "$HERE"/*)
    echo "❌ 目标目录不能是当前目录的子目录：$DEST" >&2
    exit 1
    ;;
esac

if [ "$HERE" = "$DEST" ]; then
  echo "已经在目标目录：$DEST（跳过复制）"
else
  mkdir -p "$DEST"
  echo "==> 复制到 $DEST"
  # 更新时保留用户已有的 .env（data/ 不会被 cp 删除，auth.json 也会保留）
  SAVED_ENV=""
  if [ -f "$DEST/.env" ]; then
    SAVED_ENV=$(mktemp "${TMPDIR:-${PREFIX}/tmp}/fuckds-env.XXXXXX")
    cp "$DEST/.env" "$SAVED_ENV"
  fi
  # 用 cp -R 而不是 cp -a：从 /sdcard 复制时保留 ownership 会失败，模式后面单独修
  cp -R "$HERE"/. "$DEST"/
  if [ -n "$SAVED_ENV" ]; then
    mv -f "$SAVED_ENV" "$DEST/.env"
  fi
fi

# 后面的 CLI wrapper 必须用绝对路径，否则从其它目录执行会找不到项目。
DEST=$(cd "$DEST" && pwd)

chmod 755 "$DEST/fuckds" "$DEST/install-termux.sh" "$DEST/uninstall-termux.sh" "$DEST/runtime/bin/node" "$DEST/bin/ds-pow" 2>/dev/null || true

if [ ! -f "$DEST/.env" ]; then
  if [ -f "$DEST/scripts/termux/env.termux" ]; then
    cp "$DEST/scripts/termux/env.termux" "$DEST/.env"
  else
    cp "$DEST/.env.example" "$DEST/.env"
  fi
fi
chmod 600 "$DEST/.env" 2>/dev/null || true

mkdir -p "$DEST/data"

printf '==> 检查自带 Node 运行时：'
if ! LD_LIBRARY_PATH="$DEST/runtime/lib:$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" "$DEST/runtime/bin/node" -v; then
  echo "❌ 自带 Node 运行时启动失败" >&2
  echo "   请确认是用 Termux 的 tar 解包（tar -xzf ...），而不是用文件管理器解压/在 /sdcard 上运行。" >&2
  exit 1
fi

# 安装全局命令 fuckds；只覆盖不存在或本项目的 wrapper，避免覆盖用户自己的命令。
WRAPPER="$PREFIX/bin/fuckds"
if [ -d "$PREFIX/bin" ]; then
  if [ -L "$WRAPPER" ]; then
    echo "提示：$WRAPPER 是符号链接，未覆盖；可继续用 $DEST/fuckds"
  elif [ ! -e "$WRAPPER" ] || grep -q 'fuckds termux cli wrapper' "$WRAPPER" 2>/dev/null; then
    if cat > "$WRAPPER" <<EOF
#!/system/bin/sh
# fuckds termux cli wrapper
set -eu
exec "$DEST/fuckds" "\$@"
EOF
    then
      chmod 755 "$WRAPPER" 2>/dev/null || true
      echo "$WRAPPER" > "$DEST/data/cli-wrapper.txt"
      echo "==> 已安装命令：fuckds（$WRAPPER）"
    else
      echo "⚠️ 无法写入 $WRAPPER，跳过全局命令；可继续用 $DEST/fuckds"
    fi
  else
    echo "提示：$WRAPPER 已存在且不是本项目的 wrapper，未覆盖；可继续用 $DEST/fuckds"
  fi
fi

if [ -n "$TOKEN" ]; then
  echo "==> 写入 userToken 到 $DEST/data/auth.json"
  FUCKDS_AUTH="$DEST/data/auth.json" FUCKDS_TOKEN="$TOKEN" \
  LD_LIBRARY_PATH="$DEST/runtime/lib:$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "$DEST/runtime/bin/node" -e '
    const fs = require("fs");
    const path = require("path");
    const file = process.env.FUCKDS_AUTH;
    const token = String(process.env.FUCKDS_TOKEN || "").trim();
    if (!token) {
      console.error("empty token");
      process.exit(1);
    }
    let tokens = [];
    try {
      const old = JSON.parse(fs.readFileSync(file, "utf8"));
      if (Array.isArray(old.tokens)) tokens = old.tokens.map(String).filter(Boolean);
      else if (typeof old.token === "string") tokens = [old.token];
    } catch {}
    if (!tokens.includes(token)) tokens.unshift(token);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(
      file,
      JSON.stringify({ tokens, savedAt: new Date().toISOString() }, null, 2),
      { mode: 0o600 },
    );
    fs.chmodSync(file, 0o600);
    console.log(`号池现有 ${tokens.length} 个 token（新 token 已置顶）`);
  '
fi

if [ "$WITH_CHROMIUM" = 1 ]; then
  HAD_CHROMIUM=0
  HAD_X11=0
  if command -v dpkg-query >/dev/null 2>&1; then
    if pkg_ready chromium; then HAD_CHROMIUM=1; fi
    if pkg_ready x11-repo; then HAD_X11=1; fi
  fi

  DEBDIR="$DEST/bundled-debs"
  INSTALLED_OFFLINE=0

  # 优先用 Action 里打进来的离线 .deb；没有才联网 pkg install。
  if [ -d "$DEBDIR" ] && compgen -G "$DEBDIR/*.deb" >/dev/null; then
    echo "==> 离线安装 Chromium（bundled-debs，不需要联网）"
    INSTALL_DEBS=()
    NEW_PKGS=()
    while IFS= read -r -d '' deb; do
      pkg=$(dpkg-deb -f "$deb" Package 2>/dev/null || true)
      ver=$(dpkg-deb -f "$deb" Version 2>/dev/null || true)
      [ -n "$pkg" ] && [ -n "$ver" ] || continue
      inst=""
      if command -v dpkg-query >/dev/null 2>&1; then
        inst=$(dpkg-query -W -f='${Version}' "$pkg" 2>/dev/null || true)
      fi
      if [ -n "$inst" ] && dpkg --compare-versions "$inst" ge "$ver"; then
        continue
      fi
      INSTALL_DEBS+=("$deb")
      if [ -z "$inst" ]; then NEW_PKGS+=("$pkg"); fi
    done < <(find "$DEBDIR" -maxdepth 1 -type f -name '*.deb' -print0)

    DPKG_LOG="${TMPDIR:-$PREFIX/tmp}/fuckds-dpkg.log"
    if [ ${#INSTALL_DEBS[@]} -gt 0 ]; then
      echo "==> dpkg -i（${#INSTALL_DEBS[@]} 个包；两遍处理依赖顺序）"
      : > "$DPKG_LOG"
      dpkg -i "${INSTALL_DEBS[@]}" >>"$DPKG_LOG" 2>&1 || true
      dpkg -i "${INSTALL_DEBS[@]}" >>"$DPKG_LOG" 2>&1 || true
    fi

    # 新装的依赖标记成 auto：卸载 chromium 后 apt autoremove 能清掉。
    if command -v apt-mark >/dev/null 2>&1; then
      for pkg in "${NEW_PKGS[@]}"; do
        if [ "$pkg" != chromium ]; then
          apt-mark auto "$pkg" >/dev/null 2>&1 || true
        fi
      done
    fi

    if pkg_ready chromium; then
      INSTALLED_OFFLINE=1
      echo "✅ Chromium 离线安装完成"
    else
      echo "⚠️ 离线安装未成功，尝试联网 pkg install"
      if [ -s "$DPKG_LOG" ]; then tail -n 20 "$DPKG_LOG"; fi
    fi
  fi

  if [ "$INSTALLED_OFFLINE" != 1 ]; then
    if command -v pkg >/dev/null 2>&1; then
      echo "==> 安装 Termux Chromium（联网）"
      pkg install -y x11-repo >/dev/null 2>&1 || true
      pkg update -y >/dev/null 2>&1 || true
      pkg install -y chromium || echo "⚠️ Chromium 安装失败，可稍后手动执行：pkg install x11-repo && pkg install chromium"
    else
      echo "⚠️ 找不到 pkg，也没有可用的 bundled-debs，跳过 Chromium 安装"
    fi
  fi

  # 只记录安装器自己装上的包；卸载时只删这些，避免误删用户原来的环境。
  MARK="$DEST/data/termux-installed.txt"
  touch "$MARK"
  chmod 600 "$MARK" 2>/dev/null || true
  if command -v dpkg-query >/dev/null 2>&1; then
    if [ "$HAD_X11" = 0 ] && pkg_ready x11-repo; then
      grep -qx x11-repo "$MARK" 2>/dev/null || echo x11-repo >> "$MARK"
    fi
    if [ "$HAD_CHROMIUM" = 0 ] && pkg_ready chromium; then
      grep -qx chromium "$MARK" 2>/dev/null || echo chromium >> "$MARK"
    fi
  fi
fi

if ! command -v chromium >/dev/null 2>&1 \
   && [ ! -x "$PREFIX/bin/chromium" ] \
   && [ ! -x "$PREFIX/lib/chromium/chromium" ]; then
  echo "提示：browser 引擎需要 Chromium，可执行 pkg install chromium（或重跑本脚本加 --with-chromium）"
fi

if [ "$START" = 1 ]; then
  echo "==> 启动服务"
  if [ -z "$TOKEN" ] && [ ! -s "$DEST/data/auth.json" ]; then
    echo "⚠️ 还没有 token；启动后请先 ./fuckds auth '<userToken>'"
  fi
  "$DEST/fuckds" up || echo "⚠️ 启动失败，查看 $DEST/data/server.log"
fi

cat <<EOF
✅ 安装完成：$DEST
下一步：
  cd "$DEST"
  ./fuckds auth '<userToken>'     # 如果安装时没传 --token
  ./fuckds up                    # 如果安装时没传 --start
  浏览器打开：http://127.0.0.1:8787/admin

常用：
  ./fuckds status                查看状态
  ./fuckds restart               改完 .env / token 后重启
  ./fuckds doctor                环境自检
  ./fuckds down                  停止

卸载：
  ./fuckds uninstall --yes                      # 只删项目
  ./fuckds uninstall --yes --purge-packages     # 连安装器装过的 Chromium/x11-repo 一起删
EOF
