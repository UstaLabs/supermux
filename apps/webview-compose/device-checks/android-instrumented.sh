#!/bin/bash
# webview-compose's instrumented tests on the user's phone, over Tailscale (Plan 2a, "Device preflight").
#   device-checks/android-instrumented.sh [test class] [serial]
#   e.g. device-checks/android-instrumented.sh dev.supermux.webview.engine.android.EngineLoadTest
# Builds the test APK on the Mac (webview-mac-gradle), copies it here, installs it, runs it, prints the result and
# uninstalls it. It installs and removes dev.supermux.webview.test only: nothing else on the phone is touched.
set -euo pipefail
CLASS="${1:-}"
S="${2:-100.123.34.70:57951}"
ADB="$HOME/Android/Sdk/platform-tools/adb"
PKG=dev.supermux.webview.test
MAIN=/home/ahmet/.mux/worktrees/supermux-3962b5bf/c58b82b4-abaf-40d9-a195-6376a386ddd1
ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
# The Mac copy webview-mac-gradle builds in: the same rule as the helper's own.
if [ "$ROOT" = "$MAIN" ]; then DST=work/webview-compose; else DST=work/webview-compose-$(printf %s "$ROOT" | sha1sum | cut -c1-8); fi
"$ADB" -s "$S" get-state >/dev/null || { echo "BLOCKED: $S is not connected (see the plan's Device preflight)"; exit 2; }
( cd "$ROOT" && webview-mac-gradle :webview-compose:assembleDebugAndroidTest )
OUT=$(mktemp -d "$HOME/.cache/webview-instrumented.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
scp -q "mac:$DST/apps/webview-compose/build/outputs/apk/androidTest/debug/webview-compose-debug-androidTest.apk" "$OUT/tests.apk"
"$ADB" -s "$S" shell input keyevent KEYCODE_WAKEUP
"$ADB" -s "$S" install -r -t "$OUT/tests.apk" >/dev/null
# The phone's log is the user's: never cleared. Only the lines from this run on are read, by the phone's own clock, in
# epoch seconds with a fraction (logcat takes a bare number for a line count).
START="$("$ADB" -s "$S" shell date +%s | tr -d '\r').000"
ARGS=()
if [ -n "$CLASS" ]; then ARGS=(-e class "$CLASS"); fi
set +e
"$ADB" -s "$S" shell am instrument -w "${ARGS[@]}" "$PKG/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/result.txt"
set -e
"$ADB" -s "$S" logcat -d -T "$START" -s WebViewEngine:V WebViewEnvironment:V TestRunner:V AndroidRuntime:E chromium:E > "$OUT/logcat.txt" || true
# Files the tests wrote for the run (HandoffFlashTest's frames, say) come off the phone before the APK, and its storage,
# go: into ~/.cache/webview-instrumented-outputs/<time>/.
FILES="/sdcard/Android/data/$PKG/files"
if [ -n "$("$ADB" -s "$S" shell "ls $FILES 2>/dev/null" | tr -d '\r')" ]; then
  KEEP="$HOME/.cache/webview-instrumented-outputs/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$KEEP"
  "$ADB" -s "$S" pull "$FILES/." "$KEEP" >/dev/null && echo "outputs: $KEEP"
fi
"$ADB" -s "$S" uninstall "$PKG" >/dev/null || true
if grep -q "^OK (" "$OUT/result.txt"; then
  grep -h "WebViewEnvironment\|WebViewEngine" "$OUT/logcat.txt" | tail -n 20 || true
  echo "PASS"
  exit 0
fi
echo "---- logcat: WebViewEngine, TestRunner, crashes ----"
tail -n 200 "$OUT/logcat.txt"
echo "FAIL"
exit 1
