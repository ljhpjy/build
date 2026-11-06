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

# 固化 resolv.conf 为普通文件（Android 侧会通过 proot -b 绑定真实的进去；
# 保持普通文件而不是符号链接，避免绑定被链接目标绕开）
rm -f /etc/resolv.conf
echo -e "nameserver 223.5.5.5\nnameserver 8.8.8.8" > /etc/resolv.conf

apt-get clean
rm -rf /var/lib/apt/lists/*

echo "==> rootfs 内部准备完成"
