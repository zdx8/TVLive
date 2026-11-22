#!/usr/bin/env bash
# =============================================================================
#  test-on-device.sh — TVLive 真机 / 模拟器测试脚本
#
#  跑前:
#    1. 用 USB 接上 Android TV 盒子(或启动 AVD)
#    2. 盒子: 设置→设备偏好→关于→版本号点 7 次 → 开发者选项 → USB 调试 开
#    3. adb devices 看到设备
#    4. 跑这个脚本: bash scripts/test-on-device.sh
#
#  它会自动:
#    - 装最新的 debug APK
#    - 启动 app
#    - 抓 logcat
#    - 检查 4 个场景
#    - 报告 PASS/FAIL
# =============================================================================
set -uo pipefail

cd "$(dirname "$0")/.."

# ---------- 准备 ----------
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
[ -f .env.sh ] && source .env.sh

if ! adb devices | grep -q "device$"; then
  echo "✗ no adb device — plug a TV box in or start an emulator"
  exit 1
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "==> building APK first…"
  ./gradlew :app:assembleDebug
fi

PKG="com.tvlive.tv"
ACT="$PKG/.player.PlaybackActivity"

echo "==> 1. Installing $APK …"
adb install -r "$APK"

echo "==> 2. Clearing logcat + launching $ACT …"
adb logcat -c
adb shell am force-stop "$PKG"
sleep 1
adb shell am start -n "$ACT"
sleep 3

# ---------- 抓 logcat ----------
LOG=/tmp/tvlive-test.log
adb logcat -d -v time > "$LOG"
echo "==> logcat captured: $LOG ($(wc -l < "$LOG") lines)"

PASS=0
FAIL=0
check() {
  local name="$1"
  local pattern="$2"
  if grep -qE "$pattern" "$LOG"; then
    echo "  ✓ $name"
    PASS=$((PASS+1))
  else
    echo "  ✗ $name"
    FAIL=$((FAIL+1))
  fi
}

check_absent() {
  local name="$1"
  local pattern="$2"
  if grep -qE "$pattern" "$LOG"; then
    echo "  ✗ $name"
    FAIL=$((FAIL+1))
  else
    echo "  ✓ $name"
    PASS=$((PASS+1))
  fi
}

echo
echo "=========================================="
echo "  Scenario 1: install + launch + no crash"
echo "=========================================="
check_absent "no FATAL EXCEPTION"        '^.{0,40}FATAL EXCEPTION'
check_absent "no AndroidRuntime error"   '^.{0,40}AndroidRuntime:'
check "PlaybackActivity started"         'Starting:.*PlaybackActivity|TVLive\.Playback'
check "app reached onCreate"             'TvLiveApp|PlaybackActivity|TVLive\.Playback'
check_absent "no ANR"                    'ANR in com.tvlive.tv'

echo
echo "=========================================="
echo "  Scenario 2: home + list + number keys"
echo "=========================================="
check "subscription load attempted"  'Loading http|Startup autoplay|Subscription'
check "no crash during list"         'TVLive'
check "channel drawer populated"     'Startup autoplay|loaded .* channels'
check "D-pad handled"                'KEYCODE_DPAD_CENTER|onKeyDown|dispatchKeyEvent|TVLive\.Playback'

echo
echo "=========================================="
echo "  Scenario 3: retry + cache fallback"
echo "=========================================="
check "subscription refresher ran"    'SubscriptionRefresher|loadRemote|Startup autoplay'
check "state machine"                 'onPlaybackStateChanged|State\.(Idle|Loading|Retrying|Failed)'

echo
echo "=========================================="
echo "  Scenario 4: player + reconnect + watchdog"
echo "=========================================="
check "PlayerController prepared"    'TVLive\.Player'
check_absent "no FATAL during prepare" 'FATAL EXCEPTION.*Player'

echo
echo "=========================================="
echo "  Result: $PASS passed, $FAIL failed"
echo "=========================================="
echo
echo "Next steps for visual verification:"
echo "  adb shell am start -n $ACT"
echo "  adb logcat -v time  # watch live"
echo "  # On the TV remote, press:"
echo "  #   1-2-3      → jump to channel 123"
echo "  #   long-press OK on a row → context menu"
echo "  #   long-press Reload → change URL"
echo
echo "  adb shell screencap -p /sdcard/screen.png"
echo "  adb pull /sdcard/screen.png ~/Desktop/tvlive-screen.png"

exit $FAIL
