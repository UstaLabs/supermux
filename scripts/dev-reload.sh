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
# back to a plain install -r + relaunch; so do 23/25 (a removed field / method). Exit 5 = the
# deployer has no baseline for this device yet: it primes one from the installed APK and retries.
# Requires: the phone runs a DEBUGGABLE build of the same code lineage (install one with:
# scripts/deploy-android.sh --debug <serial>).
#
# DEV_RELOAD_BUILD_HOST=mac builds on that ssh host instead (this box is often too loaded for
# Gradle), in DEV_RELOAD_REMOTE_DIR (default ~/projects/supermux-dev-reload), and swaps from here.
# The remote tree needs what git does not carry: apps/android/{keystore.properties,
# upload-keystore.jks,google-services.json} and apps/terminal-core/build/native/android-* (copy
# the .so files from this checkout so the swap stays a pure dex diff).
#
# Timing runs: a swap leaves Apply Changes' startup agent in the app's code_cache, which adds
# ~0.3 s to every cold start until the next plain install — measure on a clean install -r.
set -eu
set -o pipefail
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
GRADLE_ARGS=":android:assembleDebug -PsupermuxVersionCode=${VC:-2002} -PsupermuxVersionName=dev.hotswap -q"
if [ -n "${DEV_RELOAD_BUILD_HOST:-}" ]; then
  REMOTE="${DEV_RELOAD_REMOTE_DIR:-projects/supermux-dev-reload}"
  rsync -az --exclude='build/' --exclude='.gradle/' --exclude='node_modules/' --exclude='graphify-out/' \
    --exclude='.kotlin/' --exclude='local.properties' --exclude='apps/android/google-services.json' \
    --exclude='apps/android/keystore.properties' --exclude='apps/android/upload-keystore.jks' \
    --exclude='src/channels/web/static/' --exclude='apps/terminal-core/build/' \
    ./ "$DEV_RELOAD_BUILD_HOST:$REMOTE/"
  ssh "$DEV_RELOAD_BUILD_HOST" "source ~/ios-build-env.sh 2>/dev/null; cd $REMOTE/apps && ./gradlew $GRADLE_ARGS"
  mkdir -p "$(dirname "$APK")"
  scp -q "$DEV_RELOAD_BUILD_HOST:$REMOTE/$APK" "$APK"
else
  ( cd apps && ./gradlew $GRADLE_ARGS )
fi
echo "==> fullswap → $SERIAL"
set +e
INSTALLERS="$HD/installers/tools/base/deploy/installer/android-installer"
swap() {
  java -cp "$HD/wrapper:$CP" HotSwap "$ADB" "$SERIAL" $PKG fullswap "$REPO_ROOT/$APK" \
    --device="$SERIAL" --adb="$ADB" --installers-path="$INSTALLERS" 2>&1 | tee "$HD/last-swap.log"
  return "${PIPESTATUS[0]}"
}
swap; rc=$?
# 5 = no baseline yet; OVERLAY_ID_MISMATCH = the APK was replaced outside the deployer since its
# last swap (a plain install -r). Either way: re-baseline from what is installed, then retry.
if [ "$rc" = "5" ] || grep -q OVERLAY_ID_MISMATCH "$HD/last-swap.log"; then
  echo "==> deployer baseline missing or stale — priming it from the installed APK"
  BASE="$("$ADB" -s "$SERIAL" shell pm path $PKG | head -1 | sed 's/package://' | tr -d '\r')"
  "$ADB" -s "$SERIAL" pull "$BASE" "$HD/primed-$PKG.apk" >/dev/null
  java -cp "$CP" com.android.tools.deployer.DeployerRunner install --device="$SERIAL" --adb="$ADB" \
    --installers-path="$INSTALLERS" $PKG "$HD/primed-$PKG.apk"
  "$ADB" -s "$SERIAL" shell am start -n $PKG/.MainActivity >/dev/null
  sleep 4
  swap; rc=$?
fi
set -e
if [ "$rc" = "16" ] || [ "$rc" = "23" ] || [ "$rc" = "25" ] || [ "$rc" = "51" ]; then
  echo "==> manifest or structural change — falling back to install -r + relaunch"
  "$ADB" -s "$SERIAL" install -r "$APK"
  "$ADB" -s "$SERIAL" shell am start -n $PKG/.MainActivity
elif [ "$rc" != "0" ]; then
  echo "swap failed rc=$rc (app not running or not debuggable? start it, or reinstall with deploy-android.sh --debug)" >&2
  exit "$rc"
fi
echo "==> done"
