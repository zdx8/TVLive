#!/usr/bin/env bash
# =============================================================================
#  diagnose.sh — TVLive self-test runner
#  Mirror of iTelly-macOS's `dist/iTelly.app/Contents/MacOS/iTelly --diagnose`.
#  Builds the app, runs it, and greps the log for assertion results.
#  Exits non-zero if any "[FAIL]" appears.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

# Source env to get JDK 17 + writable Gradle/SDK homes
source .env.sh

# Build the debug APK
echo "==> Building debug APK…"
./gradlew :app:assembleDebug

# Locate the APK
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "[FAIL] APK not found at $APK"; exit 1; }
echo "==> APK: $APK ($(stat -f%z "$APK") bytes)"

# Ensure a connected device/emulator
adb devices | grep -q "device$" || {
  echo "[FAIL] no adb device — boot an emulator or connect a TV first"
  exit 2
}

# Install + run
echo "==> Installing…"
adb install -r "$APK"

# Clear logcat then start the activity
adb logcat -c
echo "==> Launching MainActivity (offline assertions will print in logcat)…"
adb shell am start -n com.tvlive.tv/.ui.MainActivity
sleep 3

# Capture logcat
LOG=$(adb logcat -d -s TVLive:D TVLive.Player:I TVLive.Diag:* AndroidRuntime:E)
echo "---- logcat ----"
echo "$LOG"
echo "----------------"

# Assert: zero [FAIL] lines
if echo "$LOG" | grep -q "\[FAIL\]"; then
  echo
  echo "[FAIL] diagnostics reported regression(s) — see logcat above"
  exit 1
fi

# Assert: at least one [PASS] line (otherwise the diag didn't run)
if ! echo "$LOG" | grep -q "\[PASS\]"; then
  echo
  echo "[FAIL] diagnostics did not run (no [PASS] in logcat)"
  exit 1
fi

# Assert: APK boots without immediate crash
if echo "$LOG" | grep -q "FATAL EXCEPTION"; then
  echo
  echo "[FAIL] app crashed on launch"
  exit 1
fi

echo
echo "✅ diagnostics passed"
