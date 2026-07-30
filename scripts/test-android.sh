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

FLOWS="$*"
[ -n "$FLOWS" ] || FLOWS=".maestro"

# test-broker.sh exports MUX_TEST_BASE_URL / MUX_TEST_PAIR_TOKEN / MUX_TEST_SESSION_ID
# for whatever it runs, so the maestro invocation just re-shapes them into flow env.
exec scripts/test-broker.sh sh -c '
  set -eu
  host_addr="${MUX_TEST_HOST_ADDR:-10.0.2.2}"
  port="${MUX_TEST_BASE_URL##*:}"
  base="http://${host_addr}:${port}"
  echo "android journey → $base" >&2
  "$0" test \
    -e BASE_URL="$base" \
    -e PAIR_TOKEN="$MUX_TEST_PAIR_TOKEN" \
    -e SESSION_ID="$MUX_TEST_SESSION_ID" \
    -e PROMPT="maestro-$(date +%s)" \
    $1
' "$MAESTRO" "$FLOWS"
