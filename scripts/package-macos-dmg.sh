#!/usr/bin/env bash
# Build a Finder-friendly Supermux DMG: Supermux.app + Applications symlink,
# with a background that shows "drag the app into Applications".
#
# Usage:
#   scripts/package-macos-dmg.sh <path-to-Supermux.app> <output.dmg>
#
# Requires macOS (hdiutil + osascript). Safe to re-run; overwrites the output.

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "error: package-macos-dmg.sh must run on macOS (needs hdiutil/osascript)" >&2
  exit 1
fi

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <Supermux.app> <output.dmg>" >&2
  exit 1
fi

APP_SRC="$1"
OUT_DMG="$2"
VOL_NAME="Supermux"

if [[ ! -d "$APP_SRC" ]]; then
  echo "error: app not found: $APP_SRC" >&2
  exit 1
fi

# Repo root (script lives in scripts/)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BG_SRC="$ROOT/assets/dmg/background.png"
if [[ ! -f "$BG_SRC" ]]; then
  echo "error: missing DMG background: $BG_SRC" >&2
  exit 1
fi

WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/supermux-dmg.XXXXXX")"
cleanup() {
  # Best-effort unmount + temp cleanup.
  if [[ -n "${MOUNT_DIR:-}" && -d "$MOUNT_DIR" ]]; then
    hdiutil detach "$MOUNT_DIR" -quiet -force 2>/dev/null || true
  fi
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

STAGE="$WORKDIR/stage"
mkdir -p "$STAGE"
ditto "$APP_SRC" "$STAGE/Supermux.app"
ln -s /Applications "$STAGE/Applications"

# RW intermediate so we can set Finder layout + background.
RW_DMG="$WORKDIR/rw.dmg"
hdiutil create \
  -volname "$VOL_NAME" \
  -srcfolder "$STAGE" \
  -fs HFS+ \
  -fsargs "-c c=64,a=16,e=16" \
  -format UDRW \
  -size 300m \
  "$RW_DMG" >/dev/null

# Attach read-write, no auto-open Finder window.
ATTACH_OUT="$(hdiutil attach -readwrite -noverify -noautoopen "$RW_DMG")"
# Device line looks like: /dev/diskN   Apple_HFS   /Volumes/Supermux
MOUNT_DIR="$(echo "$ATTACH_OUT" | awk 'END{print $NF}')"
if [[ ! -d "$MOUNT_DIR" ]]; then
  echo "error: failed to mount RW DMG" >&2
  echo "$ATTACH_OUT" >&2
  exit 1
fi

# Background lives in a hidden folder so Finder doesn't show the PNG as a file.
mkdir -p "$MOUNT_DIR/.background"
cp "$BG_SRC" "$MOUNT_DIR/.background/background.png"

# Icon positions + window chrome via Finder AppleScript.
# Coordinates are relative to the window content area (background is 660×400).
# App sits left, Applications right, with the arrow between them on the bg art.
# On headless CI this can fail — fall back to a plain app+Applications DMG rather
# than aborting the whole release.
if ! osascript <<EOF
tell application "Finder"
  tell disk "$VOL_NAME"
    open
    set current view of container window to icon view
    set toolbar visible of container window to false
    set statusbar visible of container window to false
    set the bounds of container window to {200, 120, 860, 520}
    set viewOptions to the icon view options of container window
    set arrangement of viewOptions to not arranged
    set icon size of viewOptions to 96
    set background picture of viewOptions to file ".background:background.png"
    set position of item "Supermux.app" of container window to {180, 200}
    set position of item "Applications" of container window to {480, 200}
    update without registering applications
    delay 1
    close
    open
    delay 1
    close
  end tell
end tell
EOF
then
  echo "warning: Finder layout AppleScript failed; shipping DMG without styled background" >&2
fi

# Flush .DS_Store before detach.
sync
hdiutil detach "$MOUNT_DIR" -quiet
MOUNT_DIR=""

# Compress to the final UDZO image.
mkdir -p "$(dirname "$OUT_DMG")"
rm -f "$OUT_DMG"
hdiutil convert "$RW_DMG" \
  -format UDZO \
  -imagekey zlib-level=9 \
  -o "$OUT_DMG" >/dev/null

echo "wrote $OUT_DMG"
