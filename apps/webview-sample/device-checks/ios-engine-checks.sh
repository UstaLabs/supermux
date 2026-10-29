#!/bin/bash
# The iOS engine's conformance checks (EngineChecks.kt), in the sample app on a simulator. Runs ON THE MAC:
#   device-checks/ios-engine-checks.sh [check name]
#   DEVICE=<udid or name> (default: sm-iPhone, iOS 26.5)   SKIP_BUILD=1 reuses the last build   OUT=<dir> for the logs
#   MEASURE=1 runs the measurements (Measurements.kt) instead, and prints their MEASURE lines too.
# Builds the app for the simulator (no signing), installs it, launches it with -engine-checks and prints its CHECK lines.
# Exit status: 0 when every check passed.
set -u
export PATH="/opt/homebrew/bin:$PATH"
[ -f "$HOME/ios-build-env.sh" ] && . "$HOME/ios-build-env.sh"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
APPS="$(cd "$(dirname "$0")/../.." && pwd)"
DEVICE="${DEVICE:-sm-iPhone}"
OUT="${OUT:-$HOME/work/webview-ios-checks}"
ONLY="${1:-}"
mkdir -p "$OUT"
cd "$APPS/webview-sample/iosApp" && xcodegen generate >/dev/null || { echo "FAIL xcodegen"; exit 1; }
if [ -z "${SKIP_BUILD:-}" ]; then
  xcodebuild -project WebViewSample.xcodeproj -scheme WebViewSampleApp -configuration Debug -sdk iphonesimulator \
    -destination "generic/platform=iOS Simulator" -derivedDataPath build/dd ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build \
    > "$OUT/build.log" 2>&1 || { grep -E "error:|^e: " "$OUT/build.log" | head -40; echo "FAIL simulator build (see $OUT/build.log)"; exit 1; }
fi
xcrun simctl boot "$DEVICE" 2>/dev/null
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1
xcrun simctl install "$DEVICE" build/dd/Build/Products/Debug-iphonesimulator/WebViewSampleApp.app || { echo "FAIL install"; exit 1; }
ARGS=(-engine-checks)
[ -n "${MEASURE:-}" ] && ARGS=(-engine-measure)
[ -n "$ONLY" ] && ARGS+=(-only "$ONLY")
rm -f "$OUT/checks.log"
xcrun simctl launch --console-pty --terminate-running-process "$DEVICE" dev.supermux.webview.sample "${ARGS[@]}" > "$OUT/checks.log" 2>&1 &
LAUNCH=$!
# macOS has no timeout(1): poll, for up to 20 minutes.
for _ in $(seq 1 1200); do
  grep -q "CHECKS DONE" "$OUT/checks.log" 2>/dev/null && break
  kill -0 "$LAUNCH" 2>/dev/null || break
  sleep 1
done
kill "$LAUNCH" 2>/dev/null
grep -E "^CHECK |^CHECKS |^MEASURE " "$OUT/checks.log"
if grep -q "Uncaught Kotlin exception" "$OUT/checks.log"; then grep -A6 "Uncaught Kotlin exception" "$OUT/checks.log" | head -12; echo "FAIL the app crashed"; exit 1; fi
grep -qE "^CHECKS DONE passed=[0-9]+ failed=0" "$OUT/checks.log"
