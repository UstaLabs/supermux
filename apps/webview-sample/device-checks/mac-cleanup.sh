#!/usr/bin/env bash
# Runs ON THE MAC: removes what this copy's runs of the sample left behind, and nothing else (mac-runs.sh):
# - each run's LaunchAgent, by the label the run recorded;
# - each process the run recorded (the app, the shoot loop), only if it is still that process: its command line names
#   this copy's apps directory or the run's own directory (a run's caffeinate ends by itself);
# - each run's directory: its logs, frames, screenshots, and the glue the app extracted there.
# It never kills by a pattern, never touches another copy's runs, and never deletes from ~/Library: WebKit's data for
# an unbundled java process (~/Library/WebKit/java) is shared by every java process on this Mac.
#   bash apps/webview-sample/device-checks/mac-cleanup.sh
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/mac-runs.sh"
LEFT=0

# stop_recorded <run dir> <name>: kills the recorded process if it is still what the run started.
stop_recorded() {
  local file="$1/$2.pid" pid command
  [[ -s "$file" ]] || return 0
  pid="$(tr -dc '0-9' < "$file")"
  [[ -n "$pid" ]] || return 0
  command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ -n "$command" ]] || return 0
  if [[ "$command" == *"$APPS"* || "$command" == *"$1"* ]]; then
    echo "stopping $2 ($pid) of $(basename "$1")"
    kill "$pid" 2>/dev/null || true
    for ((i = 0; i < 20; i++)); do kill -0 "$pid" 2>/dev/null || return 0; sleep 0.25; done
    kill -9 "$pid" 2>/dev/null || true
  fi
}

shopt -s nullglob
for run in "$RUNS"/*/; do
  run="${run%/}"
  label="$(cat "$run/label" 2>/dev/null || true)"
  if [[ -n "$label" ]]; then
    stop_agent "$label"
  fi
  for name in app shoot; do stop_recorded "$run" "$name"; done
  if [[ -n "$label" ]] && launchctl print "$DOMAIN/$label" >/dev/null 2>&1; then
    echo "mac-cleanup.sh: $label is still loaded" >&2
    LEFT=1
    continue
  fi
  echo "removing $run"
  rm -rf "$run"
done
rmdir "$RUNS" 2>/dev/null || true
if ((LEFT)); then exit 1; fi
echo "clean"
