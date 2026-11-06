#!/usr/bin/env bash
# 组装 app.tar.gz：项目源码 + node_modules + bin/ds-pow + 运行脚本 + 预置 .env
# 解包后位于手机上的 <filesDir>/linux/app，通过 proot -b 绑定为 /opt/fuckds（可写）。
set -Eeuo pipefail
cd "$(dirname "$0")/../.."

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
APP="$STAGE/opt/fuckds"
mkdir -p "$APP/bin"

# 项目文件（排除体积大/无关内容）
tar cf - \
  --exclude='.git' \
  --exclude='node_modules' \
  --exclude='web/node_modules' \
  --exclude='web/dist' \
  --exclude='test' \
  --exclude='fuckds.zip' \
  --exclude='android-shell' \
  --exclude='bin' \
  --exclude='data' \
  --exclude='*.log' \
  --exclude='.github' \
  --exclude='app.tar.gz' \
  --exclude='payload-rootfs.tar.gz' \
  --exclude='*.apk' \
  . | tar xf - -C "$APP"

# 依赖（纯 JS）与 CI 里构建好的 web/dist
cp -a node_modules "$APP/node_modules"
cp -a web/dist "$APP/web/dist"

# PoW 求解器（linux/arm64）
cp bin/ds-pow "$APP/bin/ds-pow"
chmod 755 "$APP/bin/ds-pow"

# 运行时入口 + 预置 .env（用户可在 /admin 后台直接改 .env）
cp scripts/android/android-run.sh "$APP/android-run.sh"
chmod 755 "$APP/android-run.sh"
cp scripts/android/env.android "$APP/.env"

# 顶层条目直接是项目文件（src/、bin/、.env…），解包目标即应用目录
tar czf app.tar.gz -C "$APP" .
echo "app.tar.gz: $(du -h app.tar.gz | cut -f1)"
