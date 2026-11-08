#!/usr/bin/env bash
# 下载 Termux aarch64 的 Node 运行时（node 可执行文件 + 它依赖的全部 .so）。
#
# 用法：fetch-runtime.sh <out-dir> [nodejs-lts|nodejs]
#
# 产物：
#   <out-dir>/bin/node
#   <out-dir>/lib/*.so*
#   <out-dir>/packages.tsv     （构建清单）
#
# 说明：直接把 Termux 官方 .deb 解包，不重新编译，保证和 Termux 的 Bionic ABI 一致。
# 终端在 Termux 里运行时由启动器通过 LD_LIBRARY_PATH 指向 <out-dir>/lib。
set -euo pipefail

OUT=${1:?用法: fetch-runtime.sh <out-dir> [nodejs-lts|nodejs]}
NODE_PKG=${2:-nodejs-lts}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ARCH=aarch64
TERMUX_USR_IN_DEB=data/data/com.termux/files/usr

rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/lib"

WORK=$(mktemp -d "${TMPDIR:-/tmp}/fuckds-termux.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/repo" "$WORK/debs" "$WORK/sysroot"

BASE=""
for candidate in \
  "https://packages.termux.dev/apt/termux-main" \
  "https://packages-cf.termux.dev/apt/termux-main" \
  "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main" \
  "https://mirrors.ustc.edu.cn/termux/apt/termux-main" \
  "https://mirror.mwt.me/termux/main"
do
  candidate=${candidate%/}
  for ext in xz gz ""; do
    if [ -n "$ext" ]; then
      name="Packages.$ext"
    else
      name=Packages
    fi
    url="$candidate/dists/stable/main/binary-$ARCH/$name"
    if curl -fsSL --retry 3 --connect-timeout 20 -o "$WORK/repo/$name" "$url"; then
      case "$ext" in
        xz) xz -dc "$WORK/repo/$name" > "$WORK/repo/Packages" 2>/dev/null || continue ;;
        gz) gzip -dc "$WORK/repo/$name" > "$WORK/repo/Packages" 2>/dev/null || continue ;;
        *)  cp "$WORK/repo/$name" "$WORK/repo/Packages" || continue ;;
      esac
      if ! grep -q '^Package:' "$WORK/repo/Packages"; then
        continue
      fi
      BASE=$candidate
      break 2
    fi
  done
done

if [ -z "$BASE" ] || [ ! -s "$WORK/repo/Packages" ]; then
  echo "❌ 无法获取 Termux 仓库索引（Packages.xz / Packages.gz）" >&2
  exit 1
fi
echo "Termux 仓库: $BASE"

python3 "$HERE/resolve-deps.py" "$WORK/repo/Packages" "$NODE_PKG" nodejs > "$WORK/deps.tsv"
if [ ! -s "$WORK/deps.tsv" ]; then
  echo "❌ 依赖解析结果为空" >&2
  exit 1
fi

echo "==> 需要下载的 Termux 包："
while IFS=$'\t' read -r name version _filename _sha; do
  echo "  - $name $version"
done < "$WORK/deps.tsv"

echo "==> 下载并解包"
while IFS=$'\t' read -r name version filename sha256; do
  [ -n "$filename" ] || continue
  deb="$WORK/debs/$(basename "$filename")"
  echo "  * $name $version"
  curl -fsSL --retry 3 --connect-timeout 20 -o "$deb" "$BASE/$filename"
  if [ -n "${sha256:-}" ]; then
    echo "$sha256  $deb" | sha256sum -c - >/dev/null
  fi
  dpkg-deb -x "$deb" "$WORK/sysroot"
done < "$WORK/deps.tsv"

TERMUX_USR="$WORK/sysroot/$TERMUX_USR_IN_DEB"
NODE_SRC="$TERMUX_USR/bin/node"
if [ ! -e "$NODE_SRC" ]; then
  echo "❌ 解包后找不到 $NODE_SRC" >&2
  find "$WORK/sysroot" -maxdepth 8 -type f -name 'node' -print >&2 || true
  exit 1
fi

cp -Lf "$NODE_SRC" "$OUT/bin/node"
chmod 755 "$OUT/bin/node"

LIB_COUNT=0
for libdir in lib lib64; do
  [ -d "$TERMUX_USR/$libdir" ] || continue
  while IFS= read -r -d '' lib; do
    cp -Lf "$lib" "$OUT/lib/"
    LIB_COUNT=$((LIB_COUNT + 1))
  done < <(find "$TERMUX_USR/$libdir" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -print0 2>/dev/null || true)
done

# OpenSSL 3 provider / legacy engine（如果包里有就一起带上）。
for sub in ossl-modules engines-3; do
  if [ -d "$TERMUX_USR/lib/$sub" ]; then
    cp -aL "$TERMUX_USR/lib/$sub" "$OUT/lib/" || echo "⚠️ 复制 $sub 失败（忽略）"
  fi
done

# 兼容把 ICU 数据放在 share/icu 的 libicu 构建；启动器会设置 ICU_DATA。
if [ -d "$TERMUX_USR/share/icu" ]; then
  mkdir -p "$OUT/share"
  cp -aL "$TERMUX_USR/share/icu" "$OUT/share/icu" || echo "⚠️ 复制 ICU 数据失败（忽略，通常 libicudata 已含数据）"
fi

# CA 证书（Openssl 包里可能有）；Node 默认带 Mozilla CA，这里只作为兜底。
if [ -d "$TERMUX_USR/etc/tls" ]; then
  mkdir -p "$OUT/etc"
  cp -aL "$TERMUX_USR/etc/tls" "$OUT/etc/tls" || echo "⚠️ 复制 CA 证书失败（忽略，Node 会用自己的 Mozilla CA）"
fi

cp "$WORK/deps.tsv" "$OUT/packages.tsv"
echo "==> 已复制 $LIB_COUNT 个 .so（已解引用符号链接，方便在 /sdcard 上解压）"

python3 "$HERE/check-runtime.py" "$OUT"
echo "==> Termux Node 运行时准备完成：$(du -sh "$OUT" | cut -f1)"
