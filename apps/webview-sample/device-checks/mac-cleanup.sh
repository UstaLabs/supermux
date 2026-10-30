#!/usr/bin/env bash
# Runs ON THE MAC: removes what this plan's Mac runs left behind:
# - the LaunchAgents and any process of the sample;
# - the run directories (logs, frames, screenshots) and the extracted glue (~/Library/Caches/dev.supermux.webview);
# - the WebKit and cache entries the app created: only those missing from the list mac-engine-checks.sh took before
#   its first run, and whose names match java|openjdk|webview.
# The layout test's own leftovers (dev.supermux.webview.layouttest) go too.
set -euo pipefail
DOMAIN="gui/$(id -u)"
for label in dev.supermux.webview.sample.checks dev.supermux.webview.sample.app dev.supermux.webview.layouttest; do
  launchctl bootout "$DOMAIN/$label" 2>/dev/null || true
done
pkill -f 'dev.supermux.webview.sample.MainKt' 2>/dev/null || true
pkill -f 'dev.supermux.webview.layouttest.MainKt' 2>/dev/null || true
pkill -f 'mac-shoot-loop.sh' 2>/dev/null || true
BEFORE="$HOME/work/webview-mac-library-before.txt"
if [[ -f "$BEFORE" ]]; then
  ls -1d ~/Library/WebKit/* ~/Library/Caches/* ~/Library/HTTPStorages/* 2>/dev/null | sort > "$HOME/work/webview-mac-library-after.txt"
  comm -13 "$BEFORE" "$HOME/work/webview-mac-library-after.txt" | { grep -Ei 'java|openjdk|webview' || true; } |
    while IFS= read -r entry; do
      echo "removing $entry"
      rm -rf "$entry"
    done
  rm -f "$BEFORE" "$HOME/work/webview-mac-library-after.txt"
fi
rm -rf "$HOME/work/webview-mac-checks" "$HOME/work/webview-mac-shots" "$HOME/Library/Caches/dev.supermux.webview"
for label in dev.supermux.webview.sample.checks dev.supermux.webview.sample.app; do
  if launchctl print "$DOMAIN/$label" >/dev/null 2>&1; then
    echo "mac-cleanup.sh: $label is still loaded" >&2
    exit 1
  fi
done
if pgrep -f 'dev.supermux.webview.sample.MainKt' >/dev/null; then
  echo "mac-cleanup.sh: the sample is still running" >&2
  exit 1
fi
echo "clean"
