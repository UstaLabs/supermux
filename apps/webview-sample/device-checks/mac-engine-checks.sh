#!/usr/bin/env bash
# The macOS engine's checks, in the desktop sample, in a real window. Runs ON THE MAC, after
# `webview-mac-gradle :webview-sample:installMacApp`:
#   bash apps/webview-sample/device-checks/mac-engine-checks.sh [check name]
#   MEASURE=1 runs the measurements instead, LAB=1 the perf lab's runs.
#   OUT=<dir> for the log and frames (default ~/work/webview-mac-checks).
# An SSH session has no window server (AWT throws HeadlessException): the app starts in the logged-in user's GUI
# session as a transient Aqua LaunchAgent, which runs java itself, never a shell. It prints the app's CHECK lines and
# boots the agent out. Exit status: 0 when every check passed.
set -uo pipefail
LABEL=dev.supermux.webview.sample.checks
DOMAIN="gui/$(id -u)"
APPS="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$APPS/webview-sample/build/mac-app/lib"
JAVA=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java
OUT="${OUT:-$HOME/work/webview-mac-checks}"
ONLY="${1:-}"

stop() { launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true; }

[[ -x "$JAVA" ]] || { echo "mac-engine-checks.sh: no java at $JAVA" >&2; exit 1; }
[[ -d "$LIB" ]] || { echo "mac-engine-checks.sh: no $LIB: run webview-mac-gradle :webview-sample:installMacApp first" >&2; exit 1; }
if ioreg -n Root -d1 | grep -q '"CGSSessionScreenIsLocked"=Yes'; then
  echo "mac-engine-checks.sh: the screen is locked: ask the user to unlock the Mac" >&2
  exit 3
fi
# What WebKit and the caches held before any run, so mac-cleanup.sh removes only what the app added.
BEFORE="$HOME/work/webview-mac-library-before.txt"
[[ -f "$BEFORE" ]] || ls -1d ~/Library/WebKit/* ~/Library/Caches/* ~/Library/HTTPStorages/* 2>/dev/null | sort > "$BEFORE"

mkdir -p "$OUT"
: > "$OUT/checks.log"
: > "$OUT/checks.err"
MODE=-engine-checks
if [[ -n "${MEASURE:-}" ]]; then MODE=-engine-measure; fi
if [[ -n "${LAB:-}" ]]; then MODE=-perf-lab; fi
EXTRA=""
if [[ -n "$ONLY" ]]; then EXTRA="<string>-only</string><string>$ONLY</string>"; fi
PLIST="$OUT/$LABEL.plist"
cat > "$PLIST" <<EOF_PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$JAVA</string>
    <string>-Xmx2g</string>
    <string>-Dskiko.renderApi=METAL</string>
    <string>-cp</string>
    <string>$LIB/*</string>
    <string>dev.supermux.webview.sample.MainKt</string>
    <string>$MODE</string>
    <string>-out</string>
    <string>$OUT</string>
    $EXTRA
  </array>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
  <key>ProcessType</key><string>Interactive</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><false/>
  <key>StandardOutPath</key><string>$OUT/checks.log</string>
  <key>StandardErrorPath</key><string>$OUT/checks.err</string>
</dict>
</plist>
EOF_PLIST
plutil -lint "$PLIST" >/dev/null || { echo "mac-engine-checks.sh: bad plist $PLIST" >&2; exit 1; }
stop
launchctl bootstrap "$DOMAIN" "$PLIST" || { echo "mac-engine-checks.sh: launchctl bootstrap failed" >&2; exit 1; }
# Keep the Mac and its display awake: an occluded or sleeping window is throttled, and its numbers mean nothing.
caffeinate -d -i -u -t 1500 &
CAFFEINATE=$!
trap 'kill "$CAFFEINATE" 2>/dev/null || true; stop' EXIT
ASKED=""
for ((i = 0; i < 1200; i++)); do
  if grep -q "CHECKS DONE" "$OUT/checks.log"; then break; fi
  if [[ -z "$ASKED" ]] && grep -q NEEDS-USER-CLICK "$OUT/checks.log"; then
    echo "NEEDS-USER-CLICK: ask the user to click once on the 'webview-sample' window"
    ASKED=1
  fi
  if ((i > 30)) && ! launchctl print "$DOMAIN/$LABEL" 2>/dev/null | grep -q 'state = running'; then
    echo "mac-engine-checks.sh: the app stopped without CHECKS DONE" >&2
    break
  fi
  sleep 1
done
grep -E "^CHECK |^CHECKS |^MEASURE |^perf-lab: " "$OUT/checks.log"
if ! grep -q "CHECKS DONE" "$OUT/checks.log"; then
  echo "mac-engine-checks.sh: no CHECKS DONE; the end of checks.err:" >&2
  tail -40 "$OUT/checks.err" >&2
  exit 4
fi
grep -qE "^CHECKS DONE passed=[0-9]+ failed=0" "$OUT/checks.log"
