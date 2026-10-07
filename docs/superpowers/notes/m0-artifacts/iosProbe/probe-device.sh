#!/bin/bash
# Signed Debug device build of the IME probe + install/launch on the iPhone 15 Pro (runs ON the Mac).
# Adapted from mac:~/work/terminal-core-6362d85c/tc-device.sh: same dedicated keychains, no project patching.
# Over SSH Xcode has "No Accounts", so provisioning authenticates with the team's ASC API key. The FIRST
# build had to run once with -destination "platform=iOS,id=00008130-000E619C3609001C" so Xcode added the
# iPhone to the managed "iOS Team Provisioning Profile: *" (it only held one iPad before).
set -o pipefail
source ~/supermux-ios/env.sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH=$JAVA_HOME/bin:/opt/homebrew/bin:$PATH
DEV="${1:-7CFA6CA8-E662-5C77-8AFE-BE12DCF52A16}"
KD="$HOME/Library/Keychains/smux-dist.keychain-db"
KC="$HOME/Library/Keychains/supermux-ci.keychain-db"
# The keychain password lives only on the Mac (never in git): ~/.smux-dist-kc-pass
KP="$(cat "$HOME/.smux-dist-kc-pass" 2>/dev/null)" || true
[ -n "$KP" ] || { echo "### missing ~/.smux-dist-kc-pass"; exit 1; }
security unlock-keychain -p "$KP" "$KD"
security set-keychain-settings "$KD"
security list-keychains -d user -s "$KD" "$KC" "$HOME/Library/Keychains/login.keychain-db"
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KP" "$KD" >/dev/null 2>&1
ROOT=$HOME/work/native-editor/apps
LOG=$ROOT/editor-spike/build/probe-device-build.log
mkdir -p "$(dirname "$LOG")"
cd "$ROOT" && ./gradlew --no-daemon :editor-spike:linkDebugFrameworkIosArm64 --console=plain -q || { echo "### FRAMEWORK_FAILED"; exit 1; }
cd "$ROOT/editor-spike/iosProbe" && xcodegen generate >/dev/null || { echo XCODEGEN_FAILED; exit 1; }
echo "### BUILD $(date)"
xcodebuild -project EditorImeProbe.xcodeproj -scheme EditorImeProbe -configuration Debug \
  -destination "generic/platform=iOS" -derivedDataPath build/dd -allowProvisioningUpdates \
  -authenticationKeyPath "$HOME/.appstoreconnect/private_keys/AuthKey_4RRH24653B.p8" \
  -authenticationKeyID 4RRH24653B -authenticationKeyIssuerID aff45cdc-2e54-499a-9195-4adc1109383e \
  CODE_SIGN_KEYCHAIN="$KD" OTHER_CODE_SIGN_FLAGS="--keychain $KD" build > "$LOG" 2>&1
RC=$?
if [ $RC -ne 0 ]; then grep -E "error:|failed|Undefined" "$LOG" | sort -u | tail -40; echo "### BUILD_FAILED"; exit 1; fi
echo "### BUILD_OK $(date)"
APP=build/dd/Build/Products/Debug-iphoneos/EditorImeProbe.app
du -sh "$APP"
xcrun devicectl device install app --device "$DEV" "$APP" || { echo "### INSTALL_FAILED"; exit 1; }
xcrun devicectl device process launch --terminate-existing --device "$DEV" dev.supermux.spike.EditorImeProbe || echo "### LAUNCH_FAILED (locked?)"
echo "### DONE"
