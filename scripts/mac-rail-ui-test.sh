#!/usr/bin/env bash
# scripts/mac-rail-ui-test.sh — real macOS UI check for session-list rail icons.
#
# XCUITest (SupermuxMacUITests/SessionListRailUITests) is preferred when Mac
# Development signing + Developer Mode are available. Headless over SSH we often
# hit signing / childPID UI-runner failures; this script is the proven path:
#
#   1. Build SupermuxMac unsigned (CODE_SIGNING_ALLOWED=NO)
#   2. Ad-hoc re-sign (same recipe as scripts/mac-app-run.sh)
#   3. Launch with SM_UITEST_RAIL_FIXTURE=1 (real SessionStatusRail views)
#   4. Assert Accessibility identifiers: session-rail-working / -unread / -idle
#   5. Screenshot ~/sm-rail-ui-fixture.png
#
# Usage (from Linux host with ssh mac + source tree):
#   scripts/mac-rail-ui-test.sh
# Or on the Mac after tar-sync:
#   bash ~/supermux-mac/scripts/mac-rail-ui-test.sh --local
set -euo pipefail

LOCAL=0
if [[ "${1:-}" == "--local" ]]; then LOCAL=1; fi

REMOTE_ROOT='~/supermux-mac'
APP_REL='apps/iosApp/build/dd-mac-rail-ui/Build/Products/Debug/Supermux.app'

run_remote() {
  ssh mac "bash -lc $(printf '%q' "$1")"
}

if [[ "$LOCAL" -eq 0 ]]; then
  ROOT="$(git rev-parse --show-toplevel)"
  cd "$ROOT"
  echo "==> [1/4] sync -> mac:${REMOTE_ROOT}"
  tar --exclude .git --exclude 'apps/*/build' --exclude 'apps/shared/build' \
      --exclude node_modules --exclude '.gradle' --exclude '**/build' \
      -czf - . \
    | ssh mac "rm -rf ${REMOTE_ROOT} && mkdir -p ${REMOTE_ROOT} && tar -xzf - -C ${REMOTE_ROOT}"
  echo "==> [2/4] remote UI test"
  ssh mac "bash ${REMOTE_ROOT}/scripts/mac-rail-ui-test.sh --local"
  echo "==> [3/4] pull screenshot"
  scp mac:~/sm-rail-ui-fixture.png /tmp/sm-rail-ui-fixture.png
  scp mac:~/sm-rail-ui-assert.txt /tmp/sm-rail-ui-assert.txt
  echo "screenshot: /tmp/sm-rail-ui-fixture.png"
  exit 0
fi

# ---- runs ON the Mac ----
source ~/ios-build-env.sh
# Prefer synced tree
if [[ -d "${HOME}/supermux-mac/apps/iosApp" ]]; then
  cd "${HOME}/supermux-mac/apps/iosApp"
elif [[ -d "$(dirname "$0")/../apps/iosApp" ]]; then
  cd "$(dirname "$0")/../apps/iosApp"
else
  echo "iosApp not found" >&2
  exit 1
fi

echo "==> xcodegen + build (unsigned)"
xcodegen generate >/dev/null
xcodebuild -scheme SupermuxMac -destination "platform=macOS,arch=arm64" \
  -derivedDataPath build/dd-mac-rail-ui \
  CODE_SIGNING_ALLOWED=NO build

APP="$(pwd)/build/dd-mac-rail-ui/Build/Products/Debug/Supermux.app"
test -d "$APP"
echo "==> ad-hoc re-sign"
codesign --force --sign - --deep "$APP" >/dev/null
security delete-generic-password -s "dev.supermux.app" ~/Library/Keychains/login.keychain-db 2>/dev/null || true
security delete-generic-password -s "dev.supermux.app.push" ~/Library/Keychains/login.keychain-db 2>/dev/null || true

echo "==> launch fixture UI"
pkill -x Supermux 2>/dev/null || true
sleep 1
open --env SM_UITEST_RAIL_FIXTURE=1 "$APP"
sleep 8
pgrep -x Supermux >/dev/null || { echo "Supermux did not stay running" >&2; exit 1; }

screencapture -x ~/sm-rail-ui-fixture.png || true

echo "==> Accessibility assert"
osascript <<'OSA' | tee ~/sm-rail-ui-assert.txt
tell application "System Events"
  if not (exists process "Supermux") then error "Supermux process not running"
  tell process "Supermux"
    set frontmost to true
    delay 0.5
    if not (exists window 1) then error "no window"
    set descs to ""
    set els to entire contents of window 1
    set n to count of els
    set descs to "ELEMENT_COUNT=" & n & linefeed
    repeat with i from 1 to n
      try
        set el to item i of els
        set r to ""
        try
          set r to role of el as string
        end try
        set nm to ""
        try
          set nm to name of el as string
        end try
        set idf to ""
        try
          set idf to (value of attribute "AXIdentifier" of el) as string
        end try
        if nm is not "" or idf is not "" then
          set descs to descs & r & " name=" & nm & " id=" & idf & linefeed
        end if
      end try
    end repeat
    return descs
  end tell
end tell
OSA

python3 - <<'PY'
from pathlib import Path
import sys
text = Path.home().joinpath("sm-rail-ui-assert.txt").read_text(errors="replace")
checks = {
    "fixture_title": "Session list rail fixture" in text,
    "working_label": "Working Chat" in text,
    "unread_label": "Unread Chat" in text,
    "read_label": "Read Chat" in text,
    "working_id": "session-rail-working" in text,
    "unread_id": "session-rail-unread" in text,
    "idle_id": "session-rail-idle" in text,
}
for k, v in checks.items():
    print(("PASS" if v else "FAIL") + ":", k)
ok = all(checks.values())
print("OVERALL:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
PY

echo "==> done — screenshot ~/sm-rail-ui-fixture.png"
pkill -x Supermux 2>/dev/null || true
