#!/bin/bash
# The iOS sample's UI evidence on a simulator, with Maestro and screenshots. Runs ON THE MAC:
#   device-checks/ios-sim.sh          DEVICE=<udid or name> (default: sm-iPhone)   SKIP_BUILD=1   OUT=<dir>
# Builds and installs the app as ios-engine-checks.sh does, then:
# - the perf lab on real engines: the header names WKWebView, and the first embeds go live (screenshots);
# - a drag that starts on an embed scrolls the chat (the embed's UIKitView is Cooperative);
# - a plugin's button, a page element, reaches the app over the bridge;
# - the overlay demo: a Compose menu and dialog over a live page (screenshots);
# - typing into a page's text field with the software keyboard;
# - the user-gesture probe (-gesture-probe): dragging the list over a page that tries to leave by itself leaves every
#   one of its tries the page's own; a tap on its link is the user's.
# Every step's screenshot lands in $OUT. A simulator it boots, it shuts down at the end, and Maestro's xcodebuild output
# folders this run made in $TMPDIR are deleted. Exit status: the number of failed steps.
set -u
export PATH="$HOME/.maestro/bin:/opt/homebrew/bin:$PATH"
[ -f "$HOME/ios-build-env.sh" ] && . "$HOME/ios-build-env.sh"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
APPS="$(cd "$(dirname "$0")/../.." && pwd)"
DEVICE="${DEVICE:-sm-iPhone}"
OUT="${OUT:-$HOME/work/webview-ios-sim}"
APP_ID=dev.supermux.webview.sample
mkdir -p "$OUT"
cd "$APPS/webview-sample/iosApp" && xcodegen generate >/dev/null || { echo "FAIL xcodegen"; exit 1; }
if [ -z "${SKIP_BUILD:-}" ]; then
  xcodebuild -project WebViewSample.xcodeproj -scheme WebViewSampleApp -configuration Debug -sdk iphonesimulator \
    -destination "generic/platform=iOS Simulator" -derivedDataPath build/dd ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build \
    > "$OUT/build.log" 2>&1 || { grep -E "error:|^e: " "$OUT/build.log" | head -40; echo "FAIL simulator build"; exit 1; }
fi
# Shut down at the end only if this script boots it: another session's booted simulator stays as it was.
BOOTED_HERE=""
xcrun simctl list devices | grep -F "$DEVICE" | grep -q "(Booted)" || BOOTED_HERE=1
xcrun simctl boot "$DEVICE" 2>/dev/null
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1
UDID="$(xcrun simctl list devices | grep "$DEVICE (" | grep -oE "[0-9A-F-]{36}" | head -1)"
[ -n "$UDID" ] || UDID="$DEVICE"
xcrun simctl install "$UDID" build/dd/Build/Products/Debug-iphonesimulator/WebViewSampleApp.app || { echo "FAIL install"; exit 1; }
xcrun simctl terminate "$UDID" "$APP_ID" 2>/dev/null
# Attached to the app's console, for its output: the launch is killed at the end, never left running.
xcrun simctl launch --console-pty --terminate-running-process "$UDID" "$APP_ID" > "$OUT/console.log" 2>&1 &
LAUNCH=$!
sleep 6
FAILS=0
# Each Maestro run leaves an xcodebuild output folder (about 150 MB) in $TMPDIR: this run's own go at the end.
STARTED_MARK="$(mktemp)"
flow() { # name, yaml commands
  printf '%s\n' "appId: $APP_ID" "---" "$2" > "$OUT/$1.yaml"
  if maestro --udid "$UDID" test --no-reinstall-driver "$OUT/$1.yaml" > "$OUT/$1.log" 2>&1; then echo "PASS $1"; else echo "FAIL $1 (see $OUT/$1.log)"; FAILS=$((FAILS+1)); fi
  xcrun simctl io "$UDID" screenshot "$OUT/$1.png" >/dev/null 2>&1
}
flow perf-lab-live "- extendedWaitUntil: { visible: \"WKWebView\", timeout: 60000 }
- extendedWaitUntil: { visible: \"Demo 1\", timeout: 30000 }"
flow drag-on-an-embed-scrolls-the-chat "- assertVisible: { id: \"demo-0\" }
- swipe: { from: { id: \"demo-0\" }, direction: UP, duration: 600 }
- waitForAnimationToEnd
- assertNotVisible: { id: \"demo-0\" }"
# The plugin's button is the page's: it exists only once the page is live, which it goes only while the chat rests, and
# a tap at the middle of a button cut off by the screen's edge misses. So: quickly to the message before the plugin (its
# tag is always there), short, slow drags until the next message's header shows (the plugin above it is then wholly on
# screen), and a rest until the page is live.
flow plugin-talks-over-the-bridge "- scrollUntilVisible: { element: { id: \"demo-8\" }, direction: DOWN, timeout: 60000, visibilityPercentage: 10 }
- repeat:
    times: 20
    while:
      notVisible: \"Agent · message 11 .*\"
    commands:
      - swipe: { start: \"50%, 85%\", end: \"50%, 62%\", duration: 1200 }
- extendedWaitUntil: { visible: \"Tell the app\", timeout: 15000 }
- tapOn: \"Tell the app\"
- extendedWaitUntil: { visible: \"The plugin told the app it was clicked: 1 ×\", timeout: 10000 }"
flow overlays-over-a-live-page "- tapOn: { id: \"screen-overlays\" }
- extendedWaitUntil: { visible: \"A page under Compose overlays\", timeout: 30000 }
- tapOn: { id: \"open-menu\" }
- assertVisible: \"Open in browser\"
- tapOn: \"Reload\"
- tapOn: { id: \"open-dialog\" }
- assertVisible: \"A Compose dialog\"
- tapOn: { id: \"close-dialog\" }
- assertNotVisible: \"A Compose dialog\""
# A headless simulator counts a hardware keyboard as attached; XCTest's typing still reaches the focused field.
flow typing-into-a-page "- tapOn: { id: \"screen-overlays\" }
- extendedWaitUntil: { visible: \"A page under Compose overlays\", timeout: 30000 }
- tapOn: \"Type here\"
- inputText: \"çşğıöü 日本\"
- assertVisible: \"çşğıöü 日本\""
sleep 2
if xcrun simctl spawn "$UDID" launchctl list | grep -q "$APP_ID"; then echo "PASS still running"; else echo "FAIL the app is gone"; FAILS=$((FAILS+1)); fi
if grep -q "Uncaught Kotlin exception" "$OUT/console.log"; then echo "FAIL an uncaught Kotlin exception"; grep -A6 "Uncaught Kotlin exception" "$OUT/console.log" | head -8; FAILS=$((FAILS+1)); else echo "PASS no uncaught Kotlin exception"; fi
kill "$LAUNCH" 2>/dev/null
# The user-gesture probe, in the app launched anew with -gesture-probe: drags over a page that tries to leave by itself
# (Compose keeps them to scroll the list) make none of its tries the user's; then a tap on its link is.
xcrun simctl launch --console-pty --terminate-running-process "$UDID" "$APP_ID" -gesture-probe > "$OUT/probe-console.log" 2>&1 &
LAUNCH=$!
sleep 6
flow gesture-probe "- extendedWaitUntil: { visible: \"page's own [1-9].*\", timeout: 60000 }
- swipe: { from: { id: \"probe-page\" }, direction: UP, duration: 500 }
- swipe: { from: { id: \"probe-page\" }, direction: DOWN, duration: 500 }
- swipe: { from: { id: \"probe-page\" }, direction: UP, duration: 500 }
- swipe: { from: { id: \"probe-page\" }, direction: DOWN, duration: 500 }
- extendedWaitUntil: { visible: \"page's own 32 · the user's 0: \", timeout: 30000 }
- tapOn: \"Open example.com\"
- extendedWaitUntil: { visible: \"page's own 32 · the user's 1: https://example.com/link\", timeout: 10000 }"
kill "$LAUNCH" 2>/dev/null
[ -n "$BOOTED_HERE" ] && xcrun simctl shutdown "$UDID" 2>/dev/null
find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'maestro_xctestrunner_xcodebuild_output*' -newer "$STARTED_MARK" -exec rm -rf {} + 2>/dev/null
rm -f "$STARTED_MARK"
echo "INFO screenshots in $OUT"
echo "ios simulator UI checks: $FAILS failed"
exit $FAILS
