#!/usr/bin/env bash
# Sourced by the Mac scripts (mac-engine-checks.sh, mac-sample.sh, mac-cleanup.sh): what one run of the sample on the
# Mac owns, so runs from different copies of the tree, and different sessions, never touch each other's.
# - Every run gets a directory of its own, <copy>/runs/<run id>/, next to the copy's apps/ (webview-mac-gradle syncs
#   only apps/, so it never deletes a run's files): the LaunchAgent's plist, its logs, frames and screenshots, and the
#   glue the app extracts (-Ddev.supermux.webview.cacheDir), never the shared ~/Library/Caches.
# - Its LaunchAgent's label names the copy and the run: dev.supermux.webview.sample.<copy>.<kind>.<pid of the script>.
# - The run directory records the label, and the PIDs of what the run started (the app, the shoot loop, caffeinate):
#   mac-cleanup.sh stops exactly those, after checking each is still what was recorded, and nothing else.
# Nothing here touches ~/Library: WebKit keeps an unbundled java process's data in ~/Library/WebKit/java, which every
# java process on this Mac shares, so no run deletes from it.
APPS="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COPY="$(cd "$APPS/.." && pwd)"
COPY_TAG="$(basename "$COPY" | tr -c 'A-Za-z0-9.\n-' '-')"
RUNS="$COPY/runs"
DOMAIN="gui/$(id -u)"
JAVA=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java
LIB="$APPS/webview-sample/build/mac-app/lib"

# new_run <kind>: sets RUN_DIR and LABEL for a new run of <kind> (checks, app) and makes its directory.
new_run() {
  local kind="$1"
  RUN_DIR="$RUNS/$kind-$(date +%Y%m%d-%H%M%S)-$$"
  LABEL="dev.supermux.webview.sample.$COPY_TAG.$kind.$$"
  mkdir -p "$RUN_DIR/cache"
  printf '%s\n' "$LABEL" > "$RUN_DIR/label"
}

# remember <name> <pid>: records a process the run started, for mac-cleanup.sh.
remember() {
  printf '%s\n' "$2" > "$RUN_DIR/$1.pid"
}

# agent_pid <label>: the PID launchd runs the agent as, or nothing.
agent_pid() {
  launchctl print "$DOMAIN/$1" 2>/dev/null | awk '$1 == "pid" && $2 == "=" { print $3; exit }'
}

# agent_running <label>: whether the agent's program is running.
agent_running() {
  launchctl print "$DOMAIN/$1" 2>/dev/null | grep -q 'state = running'
}

# stop_agent <label>
stop_agent() {
  launchctl bootout "$DOMAIN/$1" 2>/dev/null || true
}

# write_plist <plist> <label> <log> <program argument elements>: a transient Aqua LaunchAgent that runs java itself.
write_plist() {
  local plist="$1" label="$2" log="$3" args="$4"
  cat > "$plist" <<EOF_PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$label</string>
  <key>ProgramArguments</key>
  <array>
    $args
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
}

# java_args <extra argument elements>: the sample on the classpath, its glue extracted into the run's own directory.
java_args() {
  printf '%s' "<string>$JAVA</string><string>-Xmx2g</string><string>-Dskiko.renderApi=METAL</string>"
  printf '%s' "<string>-Ddev.supermux.webview.cacheDir=$RUN_DIR/cache</string>"
  printf '%s' "<string>-cp</string><string>$LIB/*</string><string>dev.supermux.webview.sample.MainKt</string>$1"
}

# screen_locked: whether the logged-in user's screen is locked (input can't be checked then).
screen_locked() {
  ioreg -n Root -d1 | grep -q '"CGSSessionScreenIsLocked"=Yes'
}
