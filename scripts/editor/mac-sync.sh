#!/usr/bin/env bash
# Sync this worktree's apps/ + docs/ onto the Mac's PRIVATE checkout ~/work/native-editor.
# tar over ssh (the Mac's rsync is openrsync and cannot talk to GNU rsync). Overlay, never --delete:
# the Mac keeps its own local.properties and build caches.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
tar czf - \
  --exclude='build' --exclude='.gradle' --exclude='node_modules' --exclude='.kotlin' \
  --exclude='local.properties' --exclude='graphify-out' \
  apps docs | ssh mac 'mkdir -p ~/work/native-editor && tar xzf - -C ~/work/native-editor'
# sdk.dir must point at the MAC's SDK, never the Linux one.
ssh mac 'cd ~/work/native-editor/apps && if [ ! -f local.properties ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/devtools/android-sdk"; do
    if [ -d "$d" ]; then echo "sdk.dir=$d" > local.properties; break; fi
  done
fi; cat local.properties'
