#!/usr/bin/env bash
# 在 docker（qemu 模拟 arm64）里构建完整 Linux rootfs：
#   Ubuntu 22.04 基础系统 + Node.js 20 + Playwright Chromium(linux-arm64) + 全套依赖库
# 产物：payload-rootfs.tar.gz（解压到手机 <filesDir>/linux/rootfs，由 proot -r 挂载）
set -Eeuo pipefail
cd "$(dirname "$0")/../.."

ROOTFS_IMAGE="${ROOTFS_IMAGE:-ubuntu:22.04}"
CONTAINER=fuckds-rootfs
OUT=payload-rootfs.tar.gz

command -v docker >/dev/null 2>&1 || { echo "缺少 docker"; exit 1; }

docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

echo "==> 在 arm64 容器内安装 Node + Chromium（需要下载约 200MB，耗时几分钟）"
docker run --name "$CONTAINER" --platform linux/arm64 \
  -v "$PWD:/work" \
  -e NODE_VERSION="${NODE_VERSION:-20.18.1}" \
  "$ROOTFS_IMAGE" \
  bash /work/scripts/ci/rootfs-inner.sh

echo "==> 导出 rootfs"
docker export "$CONTAINER" | gzip -9 > "$OUT"
docker rm "$CONTAINER" >/dev/null 2>&1 || true

echo "rootfs: $(du -h "$OUT" | cut -f1)"
