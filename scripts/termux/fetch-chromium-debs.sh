#!/usr/bin/env bash
# 在 CI 里下载 Chromium 及其 Termux 依赖闭包（.deb），供手机离线安装。
#
# 用法：fetch-chromium-debs.sh <out-dir>
# 产物：<out-dir>/*.deb、<out-dir>/packages.tsv、<out-dir>/BUNDLE.txt
#
# 说明：不把 Chromium 解包到项目里，而是把官方 .deb 原样带回去。
# 手机上用 dpkg -i 安装到真正的 Termux $PREFIX，路径/资源/依赖关系和普通 pkg 安装一致。
set -euo pipefail

OUT=${1:?用法: fetch-chromium-debs.sh <out-dir>}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ARCH=aarch64

rm -rf "$OUT"
mkdir -p "$OUT"

WORK=$(mktemp -d "${TMPDIR:-/tmp}/fuckds-chromium.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/repo"

MAIN_BASES=(
  "https://packages.termux.dev/apt/termux-main"
  "https://packages-cf.termux.dev/apt/termux-main"
  "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
  "https://mirrors.ustc.edu.cn/termux/apt/termux-main"
  "https://mirror.mwt.me/termux/main"
)

X11_BASES=(
  "https://packages.termux.dev/apt/termux-x11"
  "https://packages-cf.termux.dev/apt/termux-x11"
  "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-x11"
  "https://mirrors.ustc.edu.cn/termux/apt/termux-x11"
  "https://mirror.mwt.me/termux/x11"
)
# 镜像如果同时镜像了 main，也尝试对应的 x11 路径。
for base in "${MAIN_BASES[@]}"; do
  X11_BASES+=("${base/termux-main/termux-x11}")
done

fetch_index() {
  local name=$1
  shift
  local base ext filename url
  for base in "$@"; do
    base=${base%/}
    for ext in xz gz ""; do
      if [ -n "$ext" ]; then
        filename="Packages.$ext"
      else
        filename=Packages
      fi
      url="$base/dists/stable/main/binary-$ARCH/$filename"
      if curl -fsSL --retry 3 --connect-timeout 20 -o "$WORK/repo/$name.$filename" "$url"; then
        case "$ext" in
          xz) xz -dc "$WORK/repo/$name.$filename" > "$WORK/repo/$name.Packages" 2>/dev/null || continue ;;
          gz) gzip -dc "$WORK/repo/$name.$filename" > "$WORK/repo/$name.Packages" 2>/dev/null || continue ;;
          *)  cp "$WORK/repo/$name.$filename" "$WORK/repo/$name.Packages" || continue ;;
        esac
        if ! grep -q '^Package:' "$WORK/repo/$name.Packages"; then
          continue
        fi
        echo "$base" > "$WORK/repo/$name.base"
        return 0
      fi
    done
  done
  return 1
}

if ! fetch_index main "${MAIN_BASES[@]}"; then
  echo "❌ 无法获取 Termux main 仓库索引" >&2
  exit 1
fi
if ! fetch_index x11 "${X11_BASES[@]}"; then
  echo "❌ 无法获取 Termux x11 仓库索引（Chromium 在 x11-repo）" >&2
  exit 1
fi

MAIN_BASE=$(cat "$WORK/repo/main.base")
X11_BASE=$(cat "$WORK/repo/x11.base")
echo "Termux main: $MAIN_BASE"
echo "Termux x11 : $X11_BASE"

cat "$WORK/repo/main.Packages" "$WORK/repo/x11.Packages" > "$WORK/repo/all.Packages"
python3 "$HERE/resolve-deps.py" "$WORK/repo/all.Packages" chromium > "$WORK/deps.tsv"
if ! grep -q $'^chromium\t' "$WORK/deps.tsv"; then
  echo "❌ 依赖解析结果里没有 chromium" >&2
  exit 1
fi

echo "==> Chromium 依赖闭包："
while IFS=$'\t' read -r name version _filename _sha; do
  echo "  - $name $version"
done < "$WORK/deps.tsv"

echo "==> 下载 .deb"
COUNT=0
while IFS=$'\t' read -r name version filename sha256; do
  [ -n "$filename" ] || continue
  deb="$OUT/$(basename "$filename")"
  tmp="$deb.tmp"
  ok=0
  # Chromium 来自 x11 repo；依赖大多来自 main repo。按 x11 -> main 顺序试。
  for base in "$X11_BASE" "$MAIN_BASE"; do
    if curl -fsSL --retry 3 --connect-timeout 20 -o "$tmp" "$base/$filename"; then
      if [ -n "${sha256:-}" ]; then
        if echo "$sha256  $tmp" | sha256sum -c - >/dev/null 2>&1; then
          mv -f "$tmp" "$deb"
          ok=1
          break
        fi
      else
        mv -f "$tmp" "$deb"
        ok=1
        break
      fi
    fi
    rm -f "$tmp"
  done
  if [ "$ok" != 1 ]; then
    echo "❌ 下载失败：$name $version ($filename)" >&2
    exit 1
  fi
  deb_arch=$(dpkg-deb -f "$deb" Architecture 2>/dev/null || true)
  case "$deb_arch" in
    aarch64|all) ;;
    *)
      echo "❌ 架构不符：$name ($deb_arch)，期望 aarch64/all" >&2
      exit 1
      ;;
  esac
  COUNT=$((COUNT + 1))
  echo "  + $(basename "$deb")"
done < "$WORK/deps.tsv"

cp "$WORK/deps.tsv" "$OUT/packages.tsv"
{
  echo "Chromium offline .deb bundle"
  echo "main_repo: $MAIN_BASE"
  echo "x11_repo: $X11_BASE"
  echo "packages: $COUNT"
  echo "built_at: $(date -u +%FT%TZ)"
} > "$OUT/BUNDLE.txt"

echo "==> 已打包 $COUNT 个 .deb：$(du -sh "$OUT" | cut -f1)"
