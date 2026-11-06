#!/bin/sh
# fuckds 在手机上的运行时入口（proot 内执行，完整环境：browser + direct 双引擎）
# 由 BootService 以 proot 启动；SIGTERM 直接传到 node，走项目自带的优雅退出。
set -eu

export HOME=/opt/fuckds
export TMPDIR=/tmp
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
# proot 环境下跳过 Playwright 的宿主机依赖校验（依赖库已随 rootfs 安装）
export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1

cd /opt/fuckds
mkdir -p data logs

{
  echo "===== fuckds android runtime ====="
  echo "node: $(/usr/local/node/bin/node --version 2>&1)"
  echo "chromium: $(/opt/chromium/chrome --version 2>&1 || echo missing)"
  echo "ds-pow: $(ls -l bin/ds-pow 2>&1)"
  echo "HOME=$HOME TMPDIR=$TMPDIR"
} > logs/node.log 2>&1

exec /usr/local/node/bin/node src/index.mjs >> logs/node.log 2>&1
