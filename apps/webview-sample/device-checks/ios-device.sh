#!/bin/bash
# The sample signed, installed and started on paired iPhones and iPads. Runs ON THE MAC, over SSH:
#   device-checks/ios-device.sh [devicectl id ...]   (default: the iPhone 15 Pro)
#   CONFIG=Debug     a debug build (Release by default: Kotlin/Native's debug framework is several times slower)
#   SKIP_BUILD=1     installs the last build again
#   ARGS="-chat-run read"   launch arguments for the app (devicectl's console then prints its output)
# Signing is done here, so nothing asks Apple for anything: team 57L7J9XA89's wildcard development profile ("iOS Team
# Provisioning Profile: *", which lists the iPhone and the iPad), and the identity among its certificates that the
# dedicated keychain smux-dist holds, matched by SHA-1. The keychain's password lives only on the Mac
# (~/.smux-dist-kc-pass). Xcode builds the app unsigned (it won't take an Xcode-managed profile with manual signing),
# then the profile is embedded, the frameworks signed, and the app signed with entitlements narrowed to its own id.
# The build goes to iosApp/build/dd-device, inside this copy of the tree.
# A device that is locked or asleep fails the install ("preparing the device"): the build is kept, so SKIP_BUILD=1
# installs it once the device is awake.
set -o pipefail
[ -f "$HOME/ios-build-env.sh" ] && . "$HOME/ios-build-env.sh"
export PATH="/opt/homebrew/bin:$PATH"
APPS="$(cd "$(dirname "$0")/../.." && pwd)"
DEVICES=("$@")
[ ${#DEVICES[@]} -gt 0 ] || DEVICES=(7CFA6CA8-E662-5C77-8AFE-BE12DCF52A16)
CONF="${CONFIG:-Release}"
TEAM=57L7J9XA89
PROFILE=73ac90fb-dded-47eb-a094-4383cf9e30bc
PROFILE_PATH="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles/$PROFILE.mobileprovision"
KD="$HOME/Library/Keychains/smux-dist.keychain-db"
APP_ID=dev.supermux.webview.sample
DD="$APPS/webview-sample/iosApp/build/dd-device"
APP="$DD/Build/Products/$CONF-iphoneos/WebViewSampleApp.app"
LOG="$APPS/webview-sample/build/ios-device-build.log"

if [ -z "${SKIP_BUILD:-}" ]; then
  [ -f "$PROFILE_PATH" ] || { echo "### NO_PROFILE $PROFILE_PATH"; exit 1; }
  KP="$(cat "$HOME/.smux-dist-kc-pass" 2>/dev/null)"
  [ -n "$KP" ] || { echo "### missing ~/.smux-dist-kc-pass"; exit 1; }
  security unlock-keychain -p "$KP" "$KD" || { echo "### KEYCHAIN_LOCKED"; exit 1; }
  # The profile's certificates, by SHA-1, and the first of them the keychain can sign with.
  CERTS="$(security cms -D -i "$PROFILE_PATH" | python3 -c '
import hashlib, plistlib, sys
profile = plistlib.loads(sys.stdin.buffer.read())
assert profile["TeamIdentifier"] == ["57L7J9XA89"], profile["TeamIdentifier"]
for cert in profile["DeveloperCertificates"]:
    print(hashlib.sha1(cert).hexdigest().upper())
')" || { echo "### PROFILE_UNREADABLE"; exit 1; }
  IDENTITY=""
  for sha in $CERTS; do
    if security find-identity -v -p codesigning "$KD" | grep -q "$sha"; then IDENTITY="$sha"; break; fi
  done
  [ -n "$IDENTITY" ] || { echo "### NO_IDENTITY: none of the profile's certificates is in $KD"; exit 1; }
  echo "### SIGNING team $TEAM, profile $PROFILE, identity $IDENTITY"
  mkdir -p "$(dirname "$LOG")"
  cd "$APPS/webview-sample/iosApp" && xcodegen generate >/dev/null || { echo "### XCODEGEN_FAILED"; exit 1; }
  echo "### BUILD $CONF $(date)"
  # A new build number every time, or iOS keeps the old build on reinstall.
  xcodebuild -project WebViewSample.xcodeproj -scheme WebViewSampleApp -configuration "$CONF" -sdk iphoneos \
    -destination "generic/platform=iOS" -derivedDataPath "$DD" ARCHS=arm64 DEVELOPMENT_TEAM="$TEAM" \
    CODE_SIGNING_ALLOWED=NO CURRENT_PROJECT_VERSION="$(date +%s)" \
    build > "$LOG" 2>&1 || { grep -E "error:|^e: |FAILED" "$LOG" | sort -u | tail -40; echo "### BUILD_FAILED (log: $LOG)"; exit 1; }
  echo "### BUILD_OK $(date)"
  [ -d "$APP" ] || { echo "### NO_APP $APP"; exit 1; }
  cp "$PROFILE_PATH" "$APP/embedded.mobileprovision"
  ENT="$(dirname "$LOG")/ios-device.entitlements"
  cat > "$ENT" <<EOF_ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>application-identifier</key><string>$TEAM.$APP_ID</string>
  <key>com.apple.developer.team-identifier</key><string>$TEAM</string>
  <key>get-task-allow</key><true/>
  <key>keychain-access-groups</key><array><string>$TEAM.$APP_ID</string></array>
</dict>
</plist>
EOF_ENT
  # Inside out: the embedded frameworks first, then the app with its entitlements.
  find "$APP/Frameworks" -maxdepth 1 \( -name '*.framework' -o -name '*.dylib' \) 2>/dev/null | while read -r nested; do
    codesign --force --sign "$IDENTITY" --keychain "$KD" --timestamp=none "$nested" || { echo "### SIGN_FAILED $nested"; exit 1; }
  done || exit 1
  codesign --force --sign "$IDENTITY" --keychain "$KD" --timestamp=none --entitlements "$ENT" "$APP" || { echo "### SIGN_FAILED"; exit 1; }
  codesign --verify --deep --strict "$APP" || { echo "### VERIFY_FAILED"; exit 1; }
  echo "### SIGNED $(date)"
fi
[ -d "$APP" ] || { echo "### NO_APP $APP"; exit 1; }
codesign -dvv "$APP" 2>&1 | grep -E "^(Authority|TeamIdentifier)=" | head -2
du -sh "$APP"
FAILS=0
for DEV in "${DEVICES[@]}"; do
  if xcrun devicectl device install app --device "$DEV" "$APP" > "$LOG.install-$DEV" 2>&1; then
    echo "### INSTALL_OK $DEV"
    # shellcheck disable=SC2086
    if xcrun devicectl device process launch --terminate-existing --device "$DEV" "$APP_ID" ${ARGS:-} > "$LOG.launch-$DEV" 2>&1; then
      echo "### LAUNCH_OK $DEV"
    else
      tail -3 "$LOG.launch-$DEV"; echo "### LAUNCH_FAILED $DEV (locked?)"; FAILS=$((FAILS + 1))
    fi
  else
    tail -5 "$LOG.install-$DEV"; echo "### INSTALL_FAILED $DEV (locked or asleep?)"; FAILS=$((FAILS + 1))
  fi
done
exit $FAILS
