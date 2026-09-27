#!/bin/bash
# Signed Debug build of the editor sample + install/launch on a paired iPhone/iPad. Runs ON THE MAC.
#   device.sh [devicectl id]   (default: the iPhone 15 Pro)   EDITOR_ROOT=<checkout>/apps to build another tree
#   CONFIG=Release for an optimized build (Kotlin/Native's debug framework is several times slower:
#   measure performance on Release only)
# Adapted from docs/superpowers/notes/m0-artifacts/iosProbe/probe-device.sh: the dedicated signing
# keychain (its password lives only on the Mac, ~/.smux-dist-kc-pass, never in git) and the team's
# App Store Connect API key, because Xcode has "No Accounts" over SSH.
set -o pipefail
[ -f ~/supermux-ios/env.sh ] && source ~/supermux-ios/env.sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH=$JAVA_HOME/bin:/opt/homebrew/bin:$PATH
DEV="${1:-7CFA6CA8-E662-5C77-8AFE-BE12DCF52A16}"
KD="$HOME/Library/Keychains/smux-dist.keychain-db"
KC="$HOME/Library/Keychains/supermux-ci.keychain-db"
KP="$(cat "$HOME/.smux-dist-kc-pass" 2>/dev/null)" || true
[ -n "$KP" ] || { echo "### missing ~/.smux-dist-kc-pass"; exit 1; }
security unlock-keychain -p "$KP" "$KD"
security set-keychain-settings "$KD"
security list-keychains -d user -s "$KD" "$KC" "$HOME/Library/Keychains/login.keychain-db"
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KP" "$KD" >/dev/null 2>&1
ROOT="${EDITOR_ROOT:-$HOME/work/native-editor/apps}"
CONF="${CONFIG:-Debug}"
APPDIR="$ROOT/editor-sample/iosApp"
LOG="$ROOT/editor-sample/build/ios-device-build.log"
mkdir -p "$(dirname "$LOG")"
cd "$APPDIR" && xcodegen generate >/dev/null || { echo "### XCODEGEN_FAILED"; exit 1; }
if [ -z "$SKIP_BUILD" ]; then
  echo "### BUILD $(date)"
  # A new build number every time, or iOS keeps the old build on reinstall.
  xcodebuild -project EditorSample.xcodeproj -scheme EditorSampleApp -configuration "$CONF" \
    -destination "generic/platform=iOS" -derivedDataPath build/dd -allowProvisioningUpdates \
    -authenticationKeyPath "$HOME/.appstoreconnect/private_keys/AuthKey_4RRH24653B.p8" \
    -authenticationKeyID 4RRH24653B -authenticationKeyIssuerID aff45cdc-2e54-499a-9195-4adc1109383e \
    CURRENT_PROJECT_VERSION="$(date +%s)" \
    CODE_SIGN_KEYCHAIN="$KD" OTHER_CODE_SIGN_FLAGS="--keychain $KD" build > "$LOG" 2>&1
  RC=$?
  if [ $RC -ne 0 ]; then grep -E "error:|failed|Undefined|FAILED" "$LOG" | sort -u | tail -40; echo "### BUILD_FAILED"; exit 1; fi
  echo "### BUILD_OK $(date)"
fi
APP=build/dd/Build/Products/$CONF-iphoneos/EditorSampleApp.app
du -sh "$APP"
ls "$APP/editor-syntax/tables" 2>/dev/null | wc -l | xargs echo "grammar tables bundled:"
xcrun devicectl device install app --device "$DEV" "$APP" || { echo "### INSTALL_FAILED"; exit 1; }
xcrun devicectl device process launch --terminate-existing --device "$DEV" dev.supermux.editor.sample || echo "### LAUNCH_FAILED (locked?)"
echo "### DONE"
