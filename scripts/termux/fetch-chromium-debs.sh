#!/usr/bin/env bash
# 在 CI 里下载 Chromium 及其 Termux 依赖闭包（.deb），供手机离线安装。
#
# 用法：fetch-chromium-debs.sh <out-dir>
# 产物：<out-dir>/*.deb、<out-dir>/packages.tsv、<out-dir>/BUNDLE.txt
#
# 关键点：
# - Chromium 在 x11 repo，不能硬编码猜测仓库地址；先从 main 里的 x11-repo 包
#   读出它真实的 sources.list，再拉取 x11 Packages；
# - 必须校验 x11 索引里确实有 Package: chromium，避免把 main 索引当成 x11；
# - .deb 原样带回手机，由 dpkg -i 安装到真正的 Termux $PREFIX。
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
# 只从明确含 termux-main 的镜像派生 x11 路径；avoid mirror.mwt.me/termux/main
# 被原样当成 x11（它其实还是 main 索引）。
for base in "${MAIN_BASES[@]}"; do
  case "$base" in
    *termux-main*) X11_BASES+=("${base/termux-main/termux-x11}") ;;
  esac
done

# fetch_index <name> <required-package-or-empty> <base...>
# 支持标准 APT 布局和少数镜像的 flat 布局；required 用于确认索引里真的有目标包。
fetch_index() {
  local name=$1 required=$2
  shift 2
  local base ext filename url path
  for base in "$@"; do
    base=${base%/}
    # 少数仓库/镜像会使用不同 suite 或 flat 布局，都试一下。
    for path in "dists/stable/main/binary-$ARCH" "dists/x11/main/binary-$ARCH" "dists/stable/x11/binary-$ARCH" ""; do
      for ext in xz gz ""; do
        if [ -n "$ext" ]; then
          filename="Packages.$ext"
        else
          filename=Packages
        fi
        if [ -n "$path" ]; then
          url="$base/$path/$filename"
        else
          url="$base/$filename"
        fi
        if curl -fsSL --retry 3 --connect-timeout 20 -o "$WORK/repo/$name.$filename" "$url"; then
          case "$ext" in
            xz) xz -dc "$WORK/repo/$name.$filename" > "$WORK/repo/$name.Packages" 2>/dev/null || continue ;;
            gz) gzip -dc "$WORK/repo/$name.$filename" > "$WORK/repo/$name.Packages" 2>/dev/null || continue ;;
            *)  cp "$WORK/repo/$name.$filename" "$WORK/repo/$name.Packages" || continue ;;
          esac
          if ! grep -q '^Package:' "$WORK/repo/$name.Packages"; then
            continue
          fi
          if [ -n "$required" ] && ! grep -qx "Package: $required" "$WORK/repo/$name.Packages"; then
            continue
          fi
          echo "$base" > "$WORK/repo/$name.base"
          return 0
        fi
      done
    done
  done
  return 1
}

if ! fetch_index main "" "${MAIN_BASES[@]}"; then
  echo "❌ 无法获取 Termux main 仓库索引" >&2
  exit 1
fi
MAIN_BASE=$(cat "$WORK/repo/main.base")
echo "Termux main: $MAIN_BASE"

# 从 main 的 x11-repo 包读真实 x11 仓库地址（不同时间/镜像可能变化）。
X11_FROM_PKG=""
X11_REPO_TSV="$WORK/x11-repo.tsv"
if python3 "$HERE/resolve-deps.py" "$WORK/repo/main.Packages" x11-repo > "$X11_REPO_TSV" 2>/dev/null; then
  while IFS=$'\t' read -r name version filename sha256; do
    [ "$name" = "x11-repo" ] || continue
    deb="$WORK/x11-repo.deb"
    if curl -fsSL --retry 3 --connect-timeout 20 -o "$deb" "$MAIN_BASE/$filename"; then
      if [ -n "${sha256:-}" ]; then
        echo "$sha256  $deb" | sha256sum -c - >/dev/null 2>&1 || true
      fi
      rm -rf "$WORK/x11repo-root"
      mkdir -p "$WORK/x11repo-root"
      if dpkg-deb -x "$deb" "$WORK/x11repo-root" 2>/dev/null; then
        list=$(find "$WORK/x11repo-root" -type f \( -name '*.list' -o -name 'sources.list' \) -print -quit 2>/dev/null || true)
        if [ -n "$list" ]; then
          X11_FROM_PKG=$(awk '$1 == "deb" { if ($2 ~ /^\[/) print $3; else print $2; exit }' "$list")
        fi
      fi
      # 有些 repo 包是通过 postinst 写 sources.list；直接在整个解包目录里找 deb 行。
      if [ -z "$X11_FROM_PKG" ]; then
        line=$(grep -RhsE 'deb[[:space:]]+(\[[^]]*\][[:space:]]+)?https?://' "$WORK/x11repo-root" 2>/dev/null | head -n 1 || true)
        if [ -n "$line" ]; then
          X11_FROM_PKG=$(printf '%s\n' "$line" | sed -E 's/.*deb[[:space:]]+(\[[^]]*\][[:space:]]+)?//' | awk '{print $1}')
        fi
      fi
    fi
    break
  done < "$X11_REPO_TSV"
fi

if [ -n "$X11_FROM_PKG" ]; then
  X11_FROM_PKG=${X11_FROM_PKG%/}
  X11_BASES=("$X11_FROM_PKG" "${X11_BASES[@]}")
  echo "x11-repo sources.list: $X11_FROM_PKG"
else
  echo "⚠️ 没能从 x11-repo 包读出 sources.list，使用候选 x11 地址"
fi

# 必须确认索引里有 chromium；否则说明这个地址其实是 main 或别的仓库。
if ! fetch_index x11 chromium "${X11_BASES[@]}"; then
  echo "❌ 无法获取包含 chromium 的 Termux x11 仓库索引" >&2
  echo "   x11-repo sources.list: ${X11_FROM_PKG:-未找到}" >&2
  echo "   可把 workflow 的 bundle_chromium 设为 false，手机端联网 pkg install chromium" >&2
  exit 1
fi
X11_BASE=$(cat "$WORK/repo/x11.base")
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
