#!/bin/bash
# webview-sample on the user's phone, over Tailscale (Plan 2a, "Device preflight").
#   device-checks/android-sample.sh [none|scroll|reading|fling] [fake] [serial]
# Builds the debug APK on the Mac (webview-mac-gradle), copies it here, installs it over an earlier webview-sample
# (dev.supermux.webview.sample, and nothing else) and starts it. With a run name it starts that perf-lab run and prints
# its `perf-lab:` line. The app stays installed for the user to try.
set -euo pipefail
RUN="${1:-none}"
FAKE=false
if [ "${2:-}" = "fake" ]; then FAKE=true; fi
S="${3:-100.123.34.70:57951}"
ADB="$HOME/Android/Sdk/platform-tools/adb"
PKG=dev.supermux.webview.sample
MAIN=/home/ahmet/.mux/worktrees/supermux-3962b5bf/c58b82b4-abaf-40d9-a195-6376a386ddd1
ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
if [ "$ROOT" = "$MAIN" ]; then DST=work/webview-compose; else DST=work/webview-compose-$(printf %s "$ROOT" | sha1sum | cut -c1-8); fi
"$ADB" -s "$S" get-state >/dev/null || { echo "BLOCKED: $S is not connected (see the plan's Device preflight)"; exit 2; }
( cd "$ROOT" && webview-mac-gradle :webview-sample:assembleDebug )
OUT=$(mktemp -d "$HOME/.cache/webview-sample.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
scp -q "mac:$DST/apps/webview-sample/build/outputs/apk/debug/webview-sample-debug.apk" "$OUT/sample.apk"
"$ADB" -s "$S" shell input keyevent KEYCODE_WAKEUP
"$ADB" -s "$S" install -r "$OUT/sample.apk" >/dev/null
"$ADB" -s "$S" shell am force-stop "$PKG"
# The phone's log is the user's: never cleared. Only the lines from this launch on are read, by the phone's own clock,
# in epoch seconds with a fraction (logcat takes a bare number for a line count), so an earlier run's line never matches.
START="$("$ADB" -s "$S" shell date +%s | tr -d '\r').000"
EXTRAS=(--ez fake "$FAKE")
if [ "$RUN" != "none" ]; then EXTRAS+=(--es run "$RUN"); fi
"$ADB" -s "$S" shell am start -W -n "$PKG/.MainActivity" "${EXTRAS[@]}" >/dev/null
echo "started $PKG (fake=$FAKE, run=$RUN)"
if [ "$RUN" = "none" ]; then exit 0; fi
# Polled, as a dump: a streaming logcat given a start time prints nothing on this phone's Android 16.
LINE=""
for _ in $(seq 1 180); do
  LINE=$("$ADB" -s "$S" logcat -d -T "$START" -s System.out:I | grep -m1 "perf-lab:" || true)
  if [ -n "$LINE" ]; then break; fi
  sleep 5
done
if [ -z "$LINE" ]; then echo "FAIL: no perf-lab line within 15 minutes"; exit 1; fi
echo "$LINE"
"$ADB" -s "$S" logcat -d -T "$START" -s webview-sample:W | tail -n 20 || true
