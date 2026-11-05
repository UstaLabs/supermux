#!/usr/bin/env bash
# The macOS engine's checks, in the desktop sample, in a real window. Runs ON THE MAC, after
# `webview-mac-gradle :webview-sample:installMacApp`:
#   bash apps/webview-sample/device-checks/mac-engine-checks.sh [check name]
#   MEASURE=1 runs the measurements instead, LAB=1 the perf lab's runs, SKEW=1 the chat scrolled by trackpad gestures
#   with its pages measured against their cards (-chat-skew); SKEW=off the same with page views moving at their layout,
#   not with skiko's frames (-no-frame-sync), its baseline. SHIFT=1 reads the chat by trackpad gestures with its probe on
#   (-chat-shift): what moved under the reader's eyes, the previews' resizes and the slow frames (`chat-probe:` lines);
#   with BASELINE=1 on still pictures instead (-chat-baseline), and FRAME_SYNC=off without frame sync (-no-frame-sync).
#   JFR=1 records the run with Java Flight Recorder, into the run's directory (run.jfr). CHAT_RUN=read|scroll|fling runs
#   that scripted run of the chat with its probe on, then exits; WINDOW=<width>x<height> (dp) sizes the window, a
#   phone's width (393x900) putting the previews at a phone's card width.
#   APP=<path to a packaged .app> runs that app's own launcher instead of java on the classpath (the release check).
#   OUT=<dir> for the log and frames (default: this run's own directory, <copy>/runs/checks-<time>-<pid>/).
# An SSH session has no window server (AWT throws HeadlessException): the app starts in the logged-in user's GUI
# session as a transient Aqua LaunchAgent, which runs java itself, never a shell. Its label names this copy of the tree
# and this run, so concurrent runs from other copies or sessions are never touched (mac-runs.sh). It prints the app's
# CHECK lines and boots the agent out. Exit status: 0 when every check passed.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/mac-runs.sh"
ONLY="${1:-}"
APP="${APP:-}"

if [[ -n "$APP" ]]; then
  LAUNCHER="$(find "$APP/Contents/MacOS" -maxdepth 1 -type f -perm -u+x 2>/dev/null | head -1)"
  [[ -n "$LAUNCHER" ]] || { echo "mac-engine-checks.sh: no launcher in $APP/Contents/MacOS" >&2; exit 1; }
else
  [[ -x "$JAVA" ]] || { echo "mac-engine-checks.sh: no java at $JAVA" >&2; exit 1; }
  [[ -d "$LIB" ]] || { echo "mac-engine-checks.sh: no $LIB: run webview-mac-gradle :webview-sample:installMacApp first" >&2; exit 1; }
fi
if screen_locked; then
  echo "mac-engine-checks.sh: the screen is locked: ask the user to unlock the Mac" >&2
  exit 3
fi

new_run checks
OUT="${OUT:-$RUN_DIR}"
mkdir -p "$OUT"
LOG="$OUT/checks.log"
: > "$LOG"
: > "$LOG.err"
MODE=-engine-checks
if [[ -n "${MEASURE:-}" ]]; then MODE=-engine-measure; fi
if [[ -n "${LAB:-}" ]]; then MODE=-perf-lab; fi
if [[ -n "${SKEW:-}" ]]; then MODE=-chat-skew; fi
if [[ -n "${SHIFT:-}" ]]; then MODE=-chat-shift; fi
if [[ -n "${CHAT_RUN:-}" ]]; then MODE="-chat-run</string><string>$CHAT_RUN</string><string>-chat-probe</string><string>-exit-after-run"; fi
if [[ -n "${WINDOW:-}" ]]; then MODE="$MODE</string><string>-window</string><string>$WINDOW"; fi
EXTRA="<string>$MODE</string><string>-out</string><string>$OUT</string>"
if [[ -n "$ONLY" ]]; then EXTRA="$EXTRA<string>-only</string><string>$ONLY</string>"; fi
if [[ "${SKEW:-}" == off || "${FRAME_SYNC:-}" == off ]]; then EXTRA="$EXTRA<string>-no-frame-sync</string>"; fi
if [[ -n "${BASELINE:-}" ]]; then EXTRA="$EXTRA<string>-chat-baseline</string>"; fi
MORE=""
if [[ -n "$APP" ]]; then
  # The packaged app's launcher runs its bundled runtime with the app's own options; the glue still goes to this run.
  ARGS="<string>$LAUNCHER</string>$EXTRA"
  MORE="<key>EnvironmentVariables</key><dict><key>JAVA_TOOL_OPTIONS</key><string>-Ddev.supermux.webview.cacheDir=$RUN_DIR/cache</string></dict>"
  echo "mac-engine-checks.sh: the packaged app $APP" >&2
else
  ARGS="$(java_args "$EXTRA")"
fi
PLIST="$RUN_DIR/agent.plist"
write_plist "$PLIST" "$LABEL" "$LOG" "$ARGS" "$MORE" || { echo "mac-engine-checks.sh: bad plist $PLIST" >&2; exit 1; }
stop_agent "$LABEL"
launchctl bootstrap "$DOMAIN" "$PLIST" || { echo "mac-engine-checks.sh: launchctl bootstrap failed" >&2; exit 1; }
# Keep the Mac and its display awake: an occluded or sleeping window is throttled, and its numbers mean nothing.
caffeinate -d -i -u -t 1500 &
CAFFEINATE=$!
remember caffeinate "$CAFFEINATE"
trap 'kill "$CAFFEINATE" 2>/dev/null || true; stop_agent "$LABEL"' EXIT
echo "mac-engine-checks.sh: run $RUN_DIR, agent $LABEL" >&2
ASKED=""
for ((i = 0; i < 1500; i++)); do
  if [[ ! -s "$RUN_DIR/app.pid" ]]; then
    PID="$(agent_pid "$LABEL")"
    [[ -n "$PID" ]] && remember app "$PID"
  fi
  if grep -q "CHECKS DONE" "$LOG"; then break; fi
  if [[ -z "$ASKED" ]] && grep -q NEEDS-USER-CLICK "$LOG"; then
    echo "NEEDS-USER-CLICK: ask the user to click once on the 'webview-sample' window"
    ASKED=1
  fi
  if ((i > 30)) && ! agent_running "$LABEL"; then
    echo "mac-engine-checks.sh: the app stopped without CHECKS DONE" >&2
    break
  fi
  sleep 1
done
grep -E "^CHECK |^CHECKS |^MEASURE |^perf-lab: |^chat-probe: " "$LOG"
if ! grep -q "CHECKS DONE" "$LOG"; then
  echo "mac-engine-checks.sh: no CHECKS DONE; the end of checks.log.err:" >&2
  tail -40 "$LOG.err" >&2
  exit 4
fi
grep -qE "^CHECKS DONE passed=[0-9]+ failed=0" "$LOG"
