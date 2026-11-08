#!/usr/bin/env bash
# 构建 fuckds-rootfs.tar.xz（参考 IDAdroid：rootfs 作为独立大文件分发）
#   docker(arm64) Ubuntu 22.04 + Node 20 + Playwright Chromium + 依赖库
#   + /opt/fuckds 完整项目（含 node_modules、bin/ds-pow、android-run.sh、.env）
#   容器导出后用 xz -9 压缩（比 gzip 更小，App 端 tukaani xz 解压）
set -Eeuo pipefail
cd "$(dirname "$0")/../.."

ROOTFS_IMAGE="${ROOTFS_IMAGE:-ubuntu:22.04}"
CONTAINER=fuckds-rootfs
OUT=fuckds-rootfs.tar.xz

command -v docker >/dev/null 2>&1 || { echo "缺少 docker"; exit 1; }

docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

echo "==> 在 arm64 容器内安装 Node + Chromium + fuckds 项目（约 200MB 下载，耗时较长）"
docker run --name "$CONTAINER" --platform linux/arm64 \
  -v "$PWD:/work" \
  -e NODE_VERSION="${NODE_VERSION:-20.18.1}" \
  "$ROOTFS_IMAGE" \
  bash /work/scripts/ci/rootfs-inner.sh

echo "==> 导出 rootfs 并压缩为 xz"
docker export "$CONTAINER" | xz -9 -T0 > "$OUT"
docker rm "$CONTAINER" >/dev/null 2>&1 || true

echo "rootfs: $(du -h "$OUT" | cut -f1)"
