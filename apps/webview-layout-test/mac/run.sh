#!/usr/bin/env bash
# Runs ON the Mac: starts the layout test in the logged-in user's GUI session as a transient Aqua LaunchAgent, waits for
# it, and boots it out. An SSH session has no window server (AWT dies with HeadlessException); `launchctl bootstrap
# gui/<uid>` with LimitLoadToSessionType=Aqua is the recipe in ~/.mux/domains/infra.digest.md. The agent runs java
# itself, never a shell, because TCC attributes a screen capture to the agent's responsible process.
#
#   run.sh probe|auto <out dir> [app|external|off]   start, wait for LAYOUT-TEST DONE, boot out
#   run.sh manual <out dir>                          start and return; the window stays until closed or `run.sh stop`
#   run.sh stop
set -euo pipefail
LABEL=dev.supermux.webview.layouttest
DOMAIN="gui/$(id -u)"
# This module's copy on the Mac: ~/work/webview-compose/… or a worktree's ~/work/webview-compose-<hash>/….
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java
LIB="$MODULE/build/native/macos-arm64/libwebview_layout_test.dylib"
LIBDIR="$MODULE/build/install/webview-layout-test/lib"

stop() { launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true; }

if [[ "${1:-}" == stop ]]; then
  stop
  echo "stopped $LABEL"
  exit 0
fi
MODE="${1:?usage: run.sh probe|auto|manual <out dir> [app|external|off] | run.sh stop}"
OUT="${2:?usage: run.sh probe|auto|manual <out dir> [app|external|off]}"
SHOTS="${3:-app}"
case "$MODE" in probe | auto | manual) ;; *) echo "run.sh: unknown mode $MODE" >&2; exit 2 ;; esac
[[ -x "$JAVA" ]] || { echo "run.sh: no java at $JAVA" >&2; exit 1; }
[[ -f "$LIB" ]] || { echo "run.sh: no $LIB: run native/macos/build.sh first" >&2; exit 1; }
[[ -d "$LIBDIR" ]] || { echo "run.sh: no $LIBDIR: run webview-mac-gradle :webview-layout-test:installDist first" >&2; exit 1; }
if ioreg -n Root -d1 | grep -q '"CGSSessionScreenIsLocked"=Yes'; then
  echo "run.sh: the screen is locked: ask the user to unlock the Mac" >&2
  exit 3
fi
mkdir -p "$OUT/shots"
: > "$OUT/app.log"
: > "$OUT/app.err"
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
    <string>-Xmx1g</string>
    <string>-Dlayouttest.nativeLibrary=$LIB</string>
    <string>-Dlayouttest.out=$OUT</string>
    <string>-Dlayouttest.mode=$MODE</string>
    <string>-Dlayouttest.shots=$SHOTS</string>
    <string>-Dskiko.renderApi=METAL</string>
    <string>-cp</string>
    <string>$LIBDIR/*</string>
    <string>dev.supermux.webview.layouttest.MainKt</string>
  </array>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
  <key>ProcessType</key><string>Interactive</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><false/>
  <key>StandardOutPath</key><string>$OUT/app.log</string>
  <key>StandardErrorPath</key><string>$OUT/app.err</string>
</dict>
</plist>
EOF_PLIST
plutil -lint "$PLIST" >/dev/null
stop
launchctl bootstrap "$DOMAIN" "$PLIST"
echo "started $LABEL ($MODE, shots=$SHOTS): $OUT/app.log"
if [[ "$MODE" == manual ]]; then exit 0; fi

LIMIT=180
if [[ "$MODE" == auto ]]; then LIMIT=900; fi
# Keep the Mac and its display awake: an occluded or sleeping window is throttled, and its numbers mean nothing.
caffeinate -d -i -u -t "$LIMIT" &
CAFFEINATE=$!
trap 'kill "$CAFFEINATE" 2>/dev/null || true; stop' EXIT
for ((i = 0; i < LIMIT; i++)); do
  if grep -q 'LAYOUT-TEST DONE' "$OUT/app.log"; then break; fi
  if grep -q NEEDS-USER-CLICK "$OUT/app.log" && [[ ! -e "$OUT/.asked" ]]; then
    echo "NEEDS-USER-CLICK: ask the user to click once on the 'webview layout test' window"
    touch "$OUT/.asked"
  fi
  if ((i > 20)) && ! launchctl print "$DOMAIN/$LABEL" 2>/dev/null | grep -q 'state = running'; then
    echo "run.sh: the app stopped without LAYOUT-TEST DONE" >&2
    break
  fi
  sleep 1
done
if grep -q 'LAYOUT-TEST DONE' "$OUT/app.log"; then
  echo "done: $OUT"
else
  echo "run.sh: no LAYOUT-TEST DONE; the end of app.err:" >&2
  tail -40 "$OUT/app.err" >&2
  exit 4
fi
