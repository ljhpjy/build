#!/usr/bin/env bash
# zipalign + apksigner 签名（证书 scripts/ci/build-apk.sh 首次运行时自动生成）
set -Eeuo pipefail
cd "$(dirname "$0")/../.."

UNSIGNED=android-shell/app/build/outputs/apk/release/app-release-unsigned.apk
ALIGNED=android-shell/fuckds-aligned.apk
OUT="fuckds-android-$(date +%Y%m%d-%H%M%S).apk"

command -v zipalign >/dev/null 2>&1 || { echo "缺少 zipalign（Android build-tools）"; exit 1; }
command -v apksigner >/dev/null 2>&1 || { echo "缺少 apksigner（Android build-tools）"; exit 1; }

zipalign -f -p 4 "$UNSIGNED" "$ALIGNED"
apksigner sign \
  --ks android-shell/fuckds-release.jks \
  --ks-key-alias fuckds \
  --ks-pass pass:fuckds-android \
  --key-pass pass:fuckds-android \
  --out "$OUT" "$ALIGNED"
apksigner verify "$OUT"

echo "APK: $OUT ($(du -h "$OUT" | cut -f1))"
