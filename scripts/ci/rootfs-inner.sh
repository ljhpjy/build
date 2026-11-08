#!/usr/bin/env bash
# 在 arm64 Ubuntu 22.04 容器内执行（root）：安装 Chromium 依赖库 + Node.js 20 + Playwright Chromium
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update -qq
apt-get install -y -qq --no-install-recommends \
  ca-certificates curl xz-utils \
  libstdc++6 libglib2.0-0 libnss3 libnspr4 \
  libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 libxcomposite1 \
  libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 \
  libdbus-1-3 libexpat1 libx11-6 libxcb1 libxext6 libxi6 libxtst6 libfontconfig1 \
  libfreetype6 libxrender1 libxshmfence1 libx11-xcb1 libxcb-dri3-0 libegl1 \
  libwayland-client0 \
  fonts-liberation fonts-noto-cjk >/dev/null

# Node.js 20（官方 linux-arm64 二进制包）
echo "==> 安装 Node.js v${NODE_VERSION}"
curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.xz" \
  | tar -xJ --strip-components=1 -C /usr/local
node --version

# Playwright Chromium（linux-arm64 官方构建）+ 系统依赖（--with-deps 自动 apt 补依赖）
echo "==> 安装 Playwright Chromium（arm64）"
cd /work
PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright npx playwright install --with-deps chromium

mkdir -p /opt/chromium
mv /opt/ms-playwright/chromium-*/chrome-linux/* /opt/chromium/
ls -lh /opt/chromium/chrome
/opt/chromium/chrome --version 2>&1 | head -1 || true

# 把 fuckds 完整项目安装进 /opt/fuckds（App 导入 rootfs 后直接可用）
echo "==> 安装 fuckds 项目到 /opt/fuckds"
mkdir -p /opt/fuckds/bin
tar cf - -C /work \
  --exclude='.git' --exclude='node_modules' --exclude='web/node_modules' \
  --exclude='web/dist' --exclude='test' --exclude='fuckds.zip' \
  --exclude='android-shell' --exclude='.github' --exclude='bin' \
  --exclude='*.apk' --exclude='*.xz' --exclude='*.log' --exclude='data' \
  . | tar xf - -C /opt/fuckds
cp -a /work/node_modules /opt/fuckds/node_modules
cp -a /work/web/dist /opt/fuckds/web/dist
cp /work/bin/ds-pow /opt/fuckds/bin/ds-pow
cp /work/scripts/android/android-run.sh /opt/fuckds/android-run.sh
cp /work/scripts/android/env.android /opt/fuckds/.env
chmod +x /opt/fuckds/bin/ds-pow /opt/fuckds/android-run.sh
ls -lh /opt/fuckds/bin/ds-pow

# /etc/resolv.conf 在运行中的容器里是 Docker 的 bind mount：rm 会 EBUSY，
# 且 bind mount 内容不会进入 docker export。这里只尽力覆盖（非致命）；
# 手机运行时由 BootService 通过 proot -b 绑定真实 resolv.conf，
# android-run.sh 里另有兜底写入。
echo -e "nameserver 223.5.5.5\nnameserver 8.8.8.8" > /etc/resolv.conf 2>/dev/null || true

apt-get clean
rm -rf /var/lib/apt/lists/*

echo "==> rootfs 内部准备完成"
