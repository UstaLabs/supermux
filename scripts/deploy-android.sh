#!/usr/bin/env bash
# scripts/deploy-android.sh — local Android install loop (no GitHub Actions).
#
# Builds a release-key-signed APK (same CN=Supermux key as GitHub/Play sideloads)
# with a versionCode high enough to upgrade whatever is already on the device,
# then `adb install -r` + launches the app.
#
# Usage:
#   scripts/deploy-android.sh                  # auto-pick a device (this checkout)
#   scripts/deploy-android.sh --from <worktree>  # build from any worktree/session tree
#   scripts/deploy-android.sh <serial>         # e.g. 100.110.46.64:41927 or emulator-5554
#   ANDROID_SERIAL=… scripts/deploy-android.sh
#   scripts/deploy-android.sh --connect 100.110.46.64:41927   # adb connect, then deploy
#   scripts/deploy-android.sh --debug          # installDebug (still release-signed when keystore present)
#   scripts/deploy-android.sh --no-launch
#
# Wireless ADB (NetBird phone):
#   1. Phone NetBird app Connected (host peer g903 / etc.)
#   2. Wireless debugging → note connect IP:port (use NetBird 100.x IP, not LAN)
#   3. scripts/deploy-android.sh --connect 100.x.x.x:<port>
#
# Why this exists:
#   - Debug keystore ≠ GitHub upload key → INSTALL_FAILED_UPDATE_INCOMPATIBLE
#   - Local default versionCode (32) << CI (1000+run) → INSTALL_FAILED_VERSION_DOWNGRADE
#   This script always signs with upload-keystore.jks and bumps versionCode past device + published.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAIN_ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel 2>/dev/null || realpath "$SCRIPT_DIR/..")"
ROOT="$MAIN_ROOT"

PKG="dev.supermux.android"
VARIANT="release"   # release | debug
LAUNCH=1
CONNECT=""
SERIAL="${ANDROID_SERIAL:-}"
EXTRA_GRADLE=()
FROM=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --from) FROM="${2:-}"; shift 2 ;;
    --debug) VARIANT=debug; shift ;;
    --release) VARIANT=release; shift ;;
    --no-launch) LAUNCH=0; shift ;;
    --connect) CONNECT="${2:-}"; shift 2 ;;
    -h|--help)
      sed -n '2,30p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    -*)
      echo "Unknown flag: $1" >&2
      exit 2
      ;;
    *)
      SERIAL="$1"
      shift
      ;;
  esac
done

if [[ -n "$FROM" ]]; then
  [[ -d "$FROM" ]] || { echo "--from not a directory: $FROM" >&2; exit 1; }
  ROOT="$(cd "$FROM" && pwd)"
fi
cd "$ROOT"

# Worktrees lack gitignored signing + SDK pointers — link/copy from the main checkout.
ensure_android_secrets() {
  local apps="$ROOT/apps"
  local main_apps="$MAIN_ROOT/apps"
  [[ -d "$apps/android" ]] || { echo "no apps/android in $ROOT" >&2; exit 1; }
  if [[ ! -f "$apps/local.properties" && -f "$main_apps/local.properties" ]]; then
    ln -sfn "$main_apps/local.properties" "$apps/local.properties"
  fi
  if [[ ! -f "$apps/android/keystore.properties" && -f "$main_apps/android/keystore.properties" ]]; then
    ln -sfn "$main_apps/android/keystore.properties" "$apps/android/keystore.properties"
  fi
  if [[ ! -f "$apps/android/upload-keystore.jks" && -f "$main_apps/android/upload-keystore.jks" ]]; then
    ln -sfn "$main_apps/android/upload-keystore.jks" "$apps/android/upload-keystore.jks"
  fi
  if [[ ! -f "$apps/android/google-services.json" ]]; then
    if [[ -f "$main_apps/android/google-services.json" ]]; then
      ln -sfn "$main_apps/android/google-services.json" "$apps/android/google-services.json"
    elif [[ -f "$apps/android/google-services.json.example" ]]; then
      cp "$apps/android/google-services.json.example" "$apps/android/google-services.json"
    fi
  fi
}
ensure_android_secrets
step() { echo "==> $*"; }
step "Android source: $ROOT"

ADB="${ADB:-$(command -v adb)}"
if [[ -z "$ADB" ]]; then
  echo "adb not found on PATH" >&2
  exit 1
fi

# Prefer SDK platform-tools (newer wireless-debug support) when present.
if [[ -x "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" ]]; then
  ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
fi

step() { echo "==> $*"; }

if [[ -n "$CONNECT" ]]; then
  step "adb connect $CONNECT"
  # First attempt often times out over NetBird relay — retry once.
  if ! "$ADB" connect "$CONNECT" | tee /dev/stderr | grep -qi 'connected'; then
    sleep 2
    "$ADB" connect "$CONNECT"
  fi
  SERIAL="$CONNECT"
fi

pick_serial() {
  # Prefer non-emulator "device" rows; fall back to any device.
  local lines devices physical
  lines="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
  if [[ -z "$lines" ]]; then
    return 1
  fi
  physical="$(echo "$lines" | grep -v '^emulator-' | head -1 || true)"
  if [[ -n "$physical" ]]; then
    echo "$physical"
  else
    echo "$lines" | head -1
  fi
}

if [[ -z "$SERIAL" ]]; then
  SERIAL="$(pick_serial || true)"
  if [[ -z "$SERIAL" ]]; then
    echo "No adb device in 'device' state." >&2
    echo "  USB: plug in + allow debugging" >&2
    echo "  Wireless: scripts/deploy-android.sh --connect <netbird-ip>:<port>" >&2
    echo "  Current:" >&2
    "$ADB" devices -l >&2 || true
    exit 1
  fi
fi

export ANDROID_SERIAL="$SERIAL"
step "Target device: $SERIAL"
"$ADB" -s "$SERIAL" get-state >/dev/null

# --- versionCode: max(device installed, published versions.json, gradle default) + 1 ---
device_code=0
if code="$("$ADB" -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionCode=/{print $2; exit}' | tr -dc '0-9')"; then
  [[ -n "$code" ]] && device_code="$code"
fi

published_code=0
if pub="$(curl -fsSL --max-time 5 https://supermux.dev/versions.json 2>/dev/null \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("channels",{}).get("stable",{}).get("clients",{}).get("android",{}).get("versionCode") or 0)' 2>/dev/null)"; then
  published_code="${pub:-0}"
fi

default_code="$(sed -n 's/.*val defaultVersionCode = \([0-9][0-9]*\).*/\1/p' apps/android/build.gradle.kts | head -1)"
default_code="${default_code:-32}"

# Floor 2000 keeps local deploys above early CI codes without colliding with a careful
# hand-bump; still let device/published win if higher.
floor=2000
best="$default_code"
for n in "$device_code" "$published_code" "$floor"; do
  if [[ "${n:-0}" -gt "$best" ]]; then best="$n"; fi
done
version_code=$((best + 1))
git_sha="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo local)"
version_name="dev.${git_sha}"

step "versionCode=$version_code (device=$device_code published=$published_code default=$default_code) versionName=$version_name"

if [[ ! -f apps/android/keystore.properties ]]; then
  echo "WARN: apps/android/keystore.properties missing — APK will be debug-signed and cannot upgrade a GitHub/Play install." >&2
fi

# --- build + install ---
# Assemble then adb-install (clearer errors than installRelease's session API).
# Skip lintVital* — slow, noisy on local deploys, not a shipping gate here.
if [[ "$VARIANT" == "debug" ]]; then
  task=":android:assembleDebug"
  apk="android/build/outputs/apk/debug/android-debug.apk"
else
  task=":android:assembleRelease"
  apk="android/build/outputs/apk/release/android-release.apk"
fi

step "gradle $task (signed; no CI)"
(
  cd apps
  ./gradlew "$task" --console=plain \
    -PsupermuxVersionCode="$version_code" \
    -PsupermuxVersionName="$version_name" \
    -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease \
    "${EXTRA_GRADLE[@]}"
)

APK_PATH="apps/$apk"
if [[ ! -f "$APK_PATH" ]]; then
  # AGP sometimes names the module output differently
  APK_PATH="$(find apps/android/build/outputs/apk -name '*.apk' ! -name '*-androidTest.apk' | head -1)"
fi
if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found after build" >&2
  exit 1
fi

step "adb install -r $APK_PATH"
set +e
install_out="$("$ADB" -s "$SERIAL" install -r "$APK_PATH" 2>&1)"
install_rc=$?
set -e
echo "$install_out"
if [[ $install_rc -ne 0 ]]; then
  if echo "$install_out" | grep -qi 'UPDATE_INCOMPATIBLE\|signatures do not match\|INSTALL_FAILED_UPDATE'; then
    echo "" >&2
    echo "Signing key on the device does not match upload-keystore.jks (GitHub/Play key)." >&2
    echo "One-time fix (wipes app data):  adb -s $SERIAL uninstall $PKG" >&2
    echo "Then re-run this script." >&2
  elif echo "$install_out" | grep -qi 'VERSION_DOWNGRADE'; then
    echo "" >&2
    echo "versionCode too low for the device install. Re-run; this script should auto-bump." >&2
    echo "Or force: adb -s $SERIAL install -r -d $APK_PATH" >&2
  elif echo "$install_out" | grep -qiE 'INSUFFICIENT_STORAGE|not enough space'; then
    echo "" >&2
    echo "Device out of storage. Free space, then re-run." >&2
    echo "  adb -s $SERIAL shell df -h /data" >&2
    echo "  adb -s $SERIAL shell pm trim-caches 2G" >&2
  fi
  exit "$install_rc"
fi

if [[ "$LAUNCH" -eq 1 ]]; then
  step "launch $PKG"
  "$ADB" -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
    || "$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 \
    || true
fi

installed="$("$ADB" -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionName=|versionCode=/{print}' | head -4 | tr -d '\r')"
step "Installed on $SERIAL:"
echo "$installed"
echo "OK — local deploy done (no GitHub Actions)."
