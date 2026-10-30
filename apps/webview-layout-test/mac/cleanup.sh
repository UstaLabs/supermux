#!/usr/bin/env bash
# Runs ON the Mac: removes what the layout test left behind:
# - the LaunchAgent and any process of the test;
# - the run directory (plist, logs, screenshots);
# - the module's build output (the app and the dylib);
# - the WebKit and cache entries the test created: only those missing from Task 0's list whose names match
#   java|openjdk|layouttest.
set -euo pipefail
LABEL=dev.supermux.webview.layouttest
# This module's copy on the Mac (~/work/webview-compose/… or a worktree's ~/work/webview-compose-<hash>/…), and the
# run directory beside its apps/.
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$(cd "$MODULE/../.." && pwd)/run"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
pkill -f 'dev.supermux.webview.layouttest.MainKt' 2>/dev/null || true
pkill -f 'webview-layout-test/mac/shoot-loop.sh' 2>/dev/null || true
if [[ -f "$RUN/library-before.txt" ]]; then
  ls -1d ~/Library/WebKit/* ~/Library/Caches/* ~/Library/HTTPStorages/* 2>/dev/null | sort > "$RUN/library-after.txt"
  comm -13 "$RUN/library-before.txt" "$RUN/library-after.txt" | { grep -Ei 'java|openjdk|layouttest' || true; } |
    while IFS= read -r entry; do
      echo "removing $entry"
      rm -rf "$entry"
    done
fi
rm -rf "$RUN" "$MODULE/build"
if launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1; then
  echo "cleanup: $LABEL is still loaded" >&2
  exit 1
fi
if pgrep -f 'dev.supermux.webview.layouttest.MainKt' >/dev/null; then
  echo "cleanup: the app is still running" >&2
  exit 1
fi
echo "clean"
