#!/usr/bin/env bash
# 打私有包：含 private-subscriptions.json 预置订阅的 release APK。
#
# 用法：
#   scripts/build-private-apk.sh            # 构建并输出到 dist/
#   scripts/build-private-apk.sh --install  # 构建后 adb -s <设备> 安装（需先 adb connect）
#
# 私有订阅内容在 app/src/main/assets/private-subscriptions.json
# （gitignore，只在作者本地存在）。公开构建（不带 -PprivateSubs）永远纯净。
set -euo pipefail
cd "$(dirname "$0")/.."

source .env.sh
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY

./gradlew :app:assembleRelease -PprivateSubs=true

src="app/build/outputs/apk/release/app-release.apk"
ver=$(python3 -c "import json;print(json.load(open('app/build/outputs/apk/release/output-metadata.json'))['elements'][0]['versionName'])")
out="dist/TVLive-v${ver}-private-release.apk"
mkdir -p dist
cp "$src" "$out"
echo "私有包已输出: $out"

if [[ "${1:-}" == "--install" ]]; then
  dev="${ADB_SERIAL:-}"
  if [[ -z "$dev" ]]; then
    echo "用法提示：先 export ADB_SERIAL=192.168.0.104:5555（或 adb connect 后用 adb devices 查看）"
    exit 1
  fi
  "$HOME/Library/Android/sdk/platform-tools/adb" -s "$dev" install -r "$out"
  echo "已安装到 $dev"
fi
