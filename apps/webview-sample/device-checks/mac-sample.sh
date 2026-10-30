#!/usr/bin/env bash
# Runs ON THE MAC, after `webview-mac-gradle :webview-sample:installMacApp`: the desktop sample on the real engine, in the
# logged-in user's GUI session as a transient Aqua LaunchAgent (an SSH session has no window server).
#   mac-sample.sh shots     two evidence screenshots (the perf lab, the overlay demo) into ~/work/webview-mac-shots
#   mac-sample.sh manual    opens the sample for the manual checklist and returns; it stays until closed or `stop`
#   mac-sample.sh stop
set -euo pipefail
LABEL=dev.supermux.webview.sample.app
DOMAIN="gui/$(id -u)"
APPS="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$APPS/webview-sample/build/mac-app/lib"
JAVA=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java
SHOTS="$HOME/work/webview-mac-shots"

stop() { launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true; }

# start <log> <extra program arguments as <string> elements>
start() {
  local log="$1" extra="$2" plist="$HOME/work/$LABEL.plist"
  mkdir -p "$HOME/work"
  : > "$log"
  cat > "$plist" <<EOF_PLIST
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
    $extra
  </array>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
  <key>ProcessType</key><string>Interactive</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><false/>
  <key>StandardOutPath</key><string>$log</string>
  <key>StandardErrorPath</key><string>$log.err</string>
</dict>
</plist>
EOF_PLIST
  plutil -lint "$plist" >/dev/null
  stop
  launchctl bootstrap "$DOMAIN" "$plist"
}

MODE="${1:?usage: mac-sample.sh shots|manual|stop}"
if [[ "$MODE" == stop ]]; then
  stop
  echo "stopped $LABEL"
  exit 0
fi
[[ -x "$JAVA" ]] || { echo "mac-sample.sh: no java at $JAVA" >&2; exit 1; }
[[ -d "$LIB" ]] || { echo "mac-sample.sh: no $LIB: run webview-mac-gradle :webview-sample:installMacApp first" >&2; exit 1; }
if ioreg -n Root -d1 | grep -q '"CGSSessionScreenIsLocked"=Yes'; then
  echo "mac-sample.sh: the screen is locked: ask the user to unlock the Mac" >&2
  exit 3
fi
case "$MODE" in
  manual)
    start "$HOME/work/webview-mac-sample.log" ""
    echo "started the sample on the real engine: close its window, or run mac-sample.sh stop"
    ;;
  shots)
    rm -rf "$SHOTS"
    mkdir -p "$SHOTS"
    bash "$APPS/webview-sample/device-checks/mac-shoot-loop.sh" "$SHOTS" &
    LOOP=$!
    caffeinate -d -i -u -t 120 &
    CAFFEINATE=$!
    trap 'touch "$SHOTS/DONE"; kill "$CAFFEINATE" 2>/dev/null || true; stop' EXIT
    for screen in perflab overlays; do
      start "$SHOTS/$screen.log" "<string>-screen</string><string>$screen</string><string>-shots</string><string>$SHOTS</string>"
      for ((i = 0; i < 60; i++)); do
        if grep -qE "^SHOT" "$SHOTS/$screen.log"; then break; fi
        sleep 1
      done
      grep -E "^SHOT" "$SHOTS/$screen.log" || { echo "mac-sample.sh: no shot of $screen" >&2; tail -20 "$SHOTS/$screen.log.err" >&2; }
      stop
      sleep 2
    done
    touch "$SHOTS/DONE"
    wait "$LOOP" || true
    ls -l "$SHOTS"/*.png
    ;;
  *)
    echo "mac-sample.sh: unknown mode $MODE" >&2
    exit 2
    ;;
esac
