#!/usr/bin/env bash
# scripts/mac-app-run.sh — build + relaunch the macOS app on the remote Mac for feel-tests.
#   usage: scripts/mac-app-run.sh <pair-token> [broker-base]
#   (default broker-base: http://100.84.92.82:9898 — this host's Tailscale IP)
#
# Syncs this worktree to the remote Mac, compiles SupermuxMac UNSIGNED, re-signs
# it ad-hoc with a minimal sandbox-only entitlements plist, clears stale
# supermux keychain items from any prior differently-signed run, then launches
# via `open --env` against the live broker. Reports the launched PID as proof
# of a live process, best-effort tails the system log for the app, and takes a
# best-effort screenshot. None of the best-effort steps fail the script.
#
# Two findings from docs/superpowers/plans/2026-07-02-macos-desktop-client.md
# are baked in here (both postdate — and correct — an earlier draft of this
# script that this file supersedes):
#
#  - Ad-hoc launch recipe (Task 12 finding): the committed SupermuxMac.entitlements
#    carries aps-environment + keychain-access-groups, which NO ad-hoc signature
#    can satisfy — a build with CODE_SIGN_IDENTITY="-" FAILS AT SIGNING (not at
#    compiling). So we compile with CODE_SIGNING_ALLOWED=NO (unsigned) and
#    re-sign afterward with a minimal plist (app-sandbox, network.client,
#    device.audio-input only). Relatedly, a plain ssh direct-exec of the binary
#    starts the process but never mounts the SwiftUI scene (no Aqua session) —
#    no UI, no WebSocket — so launch goes through `open --env` instead.
#
#  - Keychain recipe (Task 15 finding): stale supermux keychain items left by a
#    prior differently-signed run make SecItemCopyMatching hang forever on a
#    headless ACL prompt. Both services are deleted before every launch; this
#    is safe — the app regenerates both, ACL'd to the new signature.
set -euo pipefail

TOKEN="${1:?usage: mac-app-run.sh <pair-token> [broker-base] [KEY=VAL ...]}"
BASE="${2:-http://100.84.92.82:9898}"
# Any further args are extra env for the app (the SM_* debug hooks: SM_SNAPSHOT=1,
# SM_OPEN_SHEET=settings, SM_IPAD_OPEN_PANES=editor,terminal, …), appended to `open --env`.
EXTRA_ENV=""
for kv in "${@:3}"; do EXTRA_ENV+=" --env '$kv'"; done
cd "$(git rev-parse --show-toplevel)"

# Single-quoted: this must reach the remote shell as a literal `~` and be
# expanded THERE (the Mac's $HOME), not locally (this host's $HOME differs).
REMOTE_APP='~/supermux-mac/apps/iosApp/build/dd-mac/Build/Products/Debug/Supermux.app'

step() { echo "==> $*"; }

step "[1/7] sync worktree -> mac:~/supermux-mac"
tar --exclude .git --exclude 'apps/shared/build' --exclude node_modules --exclude 'apps/iosApp/build' -czf - . \
  | ssh mac 'rm -rf ~/supermux-mac && mkdir -p ~/supermux-mac && tar -xzf - -C ~/supermux-mac'

step "[2/7] build SupermuxMac (compile only, unsigned — ad-hoc signing fails on this entitlements set)"
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate && \
  xcodebuild -scheme SupermuxMac -destination "platform=macOS,arch=arm64" \
    -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO build'

step "[3/7] re-sign ad-hoc with minimal sandbox entitlements (app-sandbox, network.client, device.audio-input)"
ssh mac 'set -e
APP=~/supermux-mac/apps/iosApp/build/dd-mac/Build/Products/Debug/Supermux.app
PLIST=~/supermux-mac/mac-feel-minimal.entitlements
cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>com.apple.security.app-sandbox</key>
	<true/>
	<key>com.apple.security.network.client</key>
	<true/>
	<key>com.apple.security.device.audio-input</key>
	<true/>
</dict>
</plist>
PLIST_EOF
codesign --force --sign - --entitlements "$PLIST" --deep "$APP"'

step "[4/7] delete stale supermux keychain items (both services) before launch"
ssh mac 'security delete-generic-password -s "dev.supermux.app" ~/Library/Keychains/login.keychain-db 2>/dev/null || true
          security delete-generic-password -s "dev.supermux.app.push" ~/Library/Keychains/login.keychain-db 2>/dev/null || true'

step "[5/7] launch via open --env (a direct ssh exec mounts no UI/WS — see header)"
# $REMOTE_APP is deliberately UNQUOTED on the remote command line below (it
# still comes through as one word — it contains no spaces) so the remote
# shell's tilde expansion actually fires; single-quoting it would send the
# literal string "~/supermux-mac/..." to `open`, which does no tilde expansion
# of its own and would fail to find the app.
ssh mac "pkill -x Supermux 2>/dev/null || true; \
  open --env SM_PAIR_TOKEN='$TOKEN' --env SM_PAIR_BASE='$BASE'$EXTRA_ENV $REMOTE_APP"

step "[6/7] PID proof (12s settle) + best-effort log tail"
PID="$(ssh mac 'sleep 12; pgrep -x Supermux' || true)"
if [[ -z "$PID" ]]; then
  echo "FAILED: no 'Supermux' process found 12s after launch (it crashed or never mounted)." >&2
  exit 1
fi
echo "Supermux PID: $PID"
# Full path required: the Mac's login shell (zsh) has a builtin named `log`
# (shell-history logging) that shadows /usr/bin/log — a bare `log show` gets
# swallowed by that builtin and silently prints nothing useful.
ssh mac "/usr/bin/log show --last 1m --predicate 'process == \"Supermux\"' 2>/dev/null | tail -20" \
  || echo "(log tail unavailable — best-effort only, not a failure)"

step "[7/7] screenshot (best-effort — needs Screen Recording permission on the Mac)"
ssh mac 'screencapture -x /tmp/supermux-mac.png 2>/dev/null || true'
if scp -q mac:/tmp/supermux-mac.png /home/ahmet/.cache/supermux-mac.png 2>/dev/null; then
  echo "screenshot: /home/ahmet/.cache/supermux-mac.png"
else
  echo "screenshot unavailable (Screen Recording permission not granted to the SSH/open context) — feel-testing then happens via TestFlight"
fi

echo OK
