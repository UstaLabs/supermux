#!/usr/bin/env bash
# scripts/dev-reload.sh — headless on-device hot code-swap for the Android app (no IDE).
#
# Drives Android Studio's Apply-Changes backend (com.android.tools.apkdeployer:apkdeployer 9.4.0)
# through a tiny wrapper that fixes its CLI-only race (DeployerRunner deploys before ddmlib's JDWP
# client list is populated → "No PIDs needs to be swapped"; the wrapper blocks until the client
# for the package is visible, then delegates — public API only). Engineering notes:
# ~/.cache/hotdeploy/NOTES.md.
#
#   usage: scripts/dev-reload.sh <adb-serial>          # e.g. 100.123.34.70:38999
#
# Loop: incremental :android:assembleDebug → fullswap the RUNNING app in place (pid kept, Compose
# recomposes; ~6 s deploy + Gradle time). Exit code 16 from the deployer = manifest change → falls
# back to a plain install -r + relaunch. Requires: the phone runs a DEBUGGABLE build of the same
# code lineage (install one with:  scripts/deploy-android.sh --debug <serial>).
set -eu
SERIAL="${1:?usage: scripts/dev-reload.sh <adb-serial>}"
REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$REPO_ROOT"
HD="$HOME/.cache/hotdeploy"
ADB="$HOME/Android/Sdk/platform-tools/adb"
PKG=dev.supermux.android
APK=apps/android/build/outputs/apk/debug/android-debug.apk
[ -d "$HD/jars" ] || { echo "missing $HD (jars/wrapper) — see NOTES.md provenance" >&2; exit 2; }
CP="$(ls "$HD"/jars/*.jar | tr '\n' ':')"
# versionCode must match what's installed or the deployer treats it as a different app; the swap
# path diffs dex, so keep the code constant per hot-swap session.
VC="$("$ADB" -s "$SERIAL" shell dumpsys package $PKG 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)"
echo "==> incremental build (versionCode=${VC:-2002})"
( cd apps && ./gradlew :android:assembleDebug -PsupermuxVersionCode="${VC:-2002}" -PsupermuxVersionName=dev.hotswap -q )
echo "==> fullswap → $SERIAL"
set +e
java -cp "$HD/wrapper:$CP" HotSwap "$ADB" "$SERIAL" $PKG fullswap "$REPO_ROOT/$APK" \
  --device="$SERIAL" --adb="$ADB" \
  --installers-path="$HD/installers/tools/base/deploy/installer/android-installer"
rc=$?
set -e
if [ "$rc" = "16" ] || [ "$rc" = "51" ]; then
  echo "==> manifest or structural change — falling back to install -r + relaunch"
  "$ADB" -s "$SERIAL" install -r "$APK"
  "$ADB" -s "$SERIAL" shell am start -n $PKG/.MainActivity
elif [ "$rc" != "0" ]; then
  echo "swap failed rc=$rc (app not running or not debuggable? start it, or reinstall with deploy-android.sh --debug)" >&2
  exit "$rc"
fi
echo "==> done"
