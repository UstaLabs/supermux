#!/bin/sh
# Run the Maestro journeys against an Android emulator/device, backed by the same
# hermetic broker fixture the browser journey uses.
#
#   usage: scripts/test-android.sh [flow.yaml ...]
#          (default: every flow in .maestro/)
#
# Requires: an attached device (`adb devices`), maestro on PATH or at
# ~/.maestro/bin/maestro, and a debug APK — built here if missing.
#
# The emulator cannot reach the host's 127.0.0.1, but 10.0.2.2 is mapped to it,
# so the broker still binds loopback and only the URL the APP is told differs.
# A physical device over USB/wifi needs the host's LAN IP instead: pass
# MUX_TEST_HOST_ADDR to override.
set -eu

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$REPO_ROOT"

MAESTRO="${MAESTRO_BIN:-}"
if [ -z "$MAESTRO" ]; then
  if command -v maestro >/dev/null 2>&1; then MAESTRO="$(command -v maestro)"
  elif [ -x "$HOME/.maestro/bin/maestro" ]; then MAESTRO="$HOME/.maestro/bin/maestro"
  else echo "maestro not found — https://maestro.dev/getting-started" >&2; exit 2
  fi
fi

adb devices | awk 'NR>1 && $2=="device"' | grep -q . || {
  echo "no attached adb device — boot an emulator first" >&2; exit 2
}

# A freshly-booted CI emulator reports `device` before it can actually serve adb
# transports: maestro then dies mid-flow with "device offline". Wait for the
# platform to say it finished booting AND for a trivial shell round-trip to work.
adb wait-for-device
i=0
while [ "$i" -lt 120 ]; do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] \
     && adb shell true >/dev/null 2>&1; then
    break
  fi
  i=$((i + 1))
  sleep 1
done
[ "$i" -lt 120 ] || { echo "device never finished booting" >&2; exit 1; }

APK="apps/android/build/outputs/apk/debug/android-debug.apk"
if [ ! -f "$APK" ] || [ "${MUX_TEST_REBUILD_APK:-0}" = "1" ]; then
  echo "building debug APK…" >&2
  # google-services.json is gitignored; the committed example is fine for a
  # journey that never talks to Firebase.
  [ -f apps/android/google-services.json ] || cp apps/android/google-services.json.example apps/android/google-services.json
  (cd apps && ./gradlew :android:assembleDebug --console=plain -q)
fi
[ -f "$APK" ] || { echo "APK not found at $APK" >&2; exit 1; }

APP_ID="dev.supermux.android"

# -g pre-grants runtime permissions so no system dialog can block the flow; -d
# allows a version downgrade, since a dev device often carries a release build
# with a far higher versionCode than a local debug build.
if ! adb install -r -d -g "$APK" >/dev/null 2>&1; then
  # A build signed with a different key cannot be replaced in place at all — the
  # only way through is uninstall + install, which DELETES the existing install
  # (pairing, drafts, everything). On a fresh CI emulator that is free; on a
  # device you actually use it is not, so it is opt-in rather than automatic.
  if [ "${MUX_TEST_FORCE_REINSTALL:-0}" = "1" ]; then
    echo "in-place install refused — uninstalling $APP_ID and reinstalling" >&2
    adb uninstall "$APP_ID" >/dev/null 2>&1 || true
    adb install -g "$APK" >/dev/null
  else
    cat >&2 <<MSG
adb refused to replace the installed $APP_ID (different signing key, or a
downgrade the platform won't allow).

Continuing means UNINSTALLING the copy already on this device, which deletes its
pairing and local state. Re-run with MUX_TEST_FORCE_REINSTALL=1 to allow that,
or point at a device that doesn't already have a release build.
MSG
    exit 1
  fi
fi

# Clear app data here rather than via maestro's launchApp(clearState): `pm clear`
# briefly drops the adb transport, and maestro running it mid-flow died with
# "device offline". Doing it up front, then re-waiting, keeps the flow stable.
adb shell pm clear "$APP_ID" >/dev/null 2>&1 || true
adb wait-for-device
adb shell true >/dev/null 2>&1 || sleep 2

FLOWS="$*"
[ -n "$FLOWS" ] || FLOWS=".maestro"

# test-broker.sh exports MUX_TEST_BASE_URL / MUX_TEST_PAIR_TOKEN /
# MUX_TEST_SESSION_ID for whatever it runs; scripts/lib/maestro-run.sh consumes
# them and does the rendering + maestro invocation.
#
# The native app never fetches the PWA shell, so skip building it: it costs ~30s
# and needs src/web-app/node_modules, which a device lane otherwise has no reason
# to install.
export MUX_TEST_SKIP_WEB_BUILD="${MUX_TEST_SKIP_WEB_BUILD:-1}"
export MUX_TEST_FLOWS="$FLOWS"
export MAESTRO_BIN="$MAESTRO"

exec scripts/test-broker.sh scripts/lib/maestro-run.sh
