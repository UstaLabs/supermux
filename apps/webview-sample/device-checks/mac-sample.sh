#!/usr/bin/env bash
# Runs ON THE MAC, after `webview-mac-gradle :webview-sample:installMacApp`: the desktop sample on the real engine, in the
# logged-in user's GUI session as a transient Aqua LaunchAgent (an SSH session has no window server). Each run has a
# directory and a label of its own (mac-runs.sh); mac-cleanup.sh removes them.
#   mac-sample.sh shots     two evidence screenshots (the perf lab, the overlay demo), into the run's shots/ directory
#   mac-sample.sh manual    opens the sample for the manual checklist and returns; it stays until closed or `stop`
#   mac-sample.sh stop      stops this copy's sample runs (never another copy's)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/mac-runs.sh"

# start <log> <extra program arguments as <string> elements>: (re)starts this run's agent.
start() {
  local log="$1" extra="$2"
  : > "$log"
  write_plist "$RUN_DIR/agent.plist" "$LABEL" "$log" "$(java_args "$extra")"
  stop_agent "$LABEL"
  launchctl bootstrap "$DOMAIN" "$RUN_DIR/agent.plist"
  for ((i = 0; i < 20; i++)); do
    local pid
    pid="$(agent_pid "$LABEL")"
    if [[ -n "$pid" ]]; then remember app "$pid"; break; fi
    sleep 0.25
  done
}

MODE="${1:?usage: mac-sample.sh shots|manual|stop}"
if [[ "$MODE" == stop ]]; then
  shopt -s nullglob
  for run in "$RUNS"/app-*/; do
    label="$(cat "$run/label" 2>/dev/null || true)"
    [[ -n "$label" ]] || continue
    stop_agent "$label"
    echo "stopped $label"
  done
  exit 0
fi
[[ -x "$JAVA" ]] || { echo "mac-sample.sh: no java at $JAVA" >&2; exit 1; }
[[ -d "$LIB" ]] || { echo "mac-sample.sh: no $LIB: run webview-mac-gradle :webview-sample:installMacApp first" >&2; exit 1; }
if screen_locked; then
  echo "mac-sample.sh: the screen is locked: ask the user to unlock the Mac" >&2
  exit 3
fi
new_run app
case "$MODE" in
  manual)
    start "$RUN_DIR/sample.log" ""
    echo "started the sample on the real engine ($LABEL): close its window, or run mac-sample.sh stop"
    ;;
  shots)
    SHOTS="$RUN_DIR/shots"
    mkdir -p "$SHOTS"
    bash "$APPS/webview-sample/device-checks/mac-shoot-loop.sh" "$SHOTS" &
    LOOP=$!
    remember shoot "$LOOP"
    caffeinate -d -i -u -t 120 &
    CAFFEINATE=$!
    remember caffeinate "$CAFFEINATE"
    trap 'touch "$SHOTS/DONE"; kill "$CAFFEINATE" 2>/dev/null || true; stop_agent "$LABEL"' EXIT
    for screen in perflab overlays; do
      start "$SHOTS/$screen.log" "<string>-screen</string><string>$screen</string><string>-shots</string><string>$SHOTS</string>"
      for ((i = 0; i < 60; i++)); do
        if grep -qE "^SHOT" "$SHOTS/$screen.log"; then break; fi
        sleep 1
      done
      grep -E "^SHOT" "$SHOTS/$screen.log" || { echo "mac-sample.sh: no shot of $screen" >&2; tail -20 "$SHOTS/$screen.log.err" >&2; }
      stop_agent "$LABEL"
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
