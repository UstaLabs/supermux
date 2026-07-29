#!/usr/bin/env bash
# feel-deploy.sh — deploy ANY session/worktree/branch to devices for feel-testing.
#
# Resolves a source (session name, branch, or path), optionally starts an isolated
# shadow backend from that tree (live :9898 untouched), and builds/installs clients
# from the same tree.
#
# Usage:
#   scripts/feel-deploy.sh "Read Aloud TTS Feature" --android
#   scripts/feel-deploy.sh mux/supermux-60 --android --backend shadow
#   scripts/feel-deploy.sh /path/to/worktree --android --ios --backend none
#   scripts/feel-deploy.sh tts --android --connect 100.x.x.x:PORT
#   scripts/feel-deploy.sh --resolve-only "Android Chat Bubbles"
#   scripts/feel-deploy.sh --stop-shadow [slug]
#
# Backend modes:
#   auto    (default) — shadow if source has src/ changes vs base_branch; else none
#   none    — clients only; pair stays on live broker
#   shadow  — isolated port + state COPY (migrations on copy only); re-pair clients
#   swap    — preview-broker live-port swap (disruptive; needs detached unit)
#
# Clients:
#   --android / --ios / --mac / --web
#   (default if none: --android when a device is present, else resolve-only)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAIN_ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel 2>/dev/null || realpath "$SCRIPT_DIR/..")"
RESOLVE="$SCRIPT_DIR/lib/resolve-source.sh"
SHADOW="$SCRIPT_DIR/shadow-broker.sh"
DEPLOY_ANDROID="$SCRIPT_DIR/deploy-android.sh"
_real_home() {
  local h
  for h in "${MUX_USER_HOME:-}" "$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f6)" "$HOME"; do
    [[ -n "$h" && -d "$h/.mux/state" ]] && { echo "$h"; return; }
  done
  for h in /home/ahmet "$HOME"; do [[ -d "$h" ]] && { echo "$h"; return; }; done
  echo "$HOME"
}
USER_HOME="$(_real_home)"
PREVIEW_BROKER="${PREVIEW_BROKER:-$USER_HOME/.mux/plugins/mux-core/skills/preview-broker/preview-broker.sh}"

QUERY=""
BACKEND=auto
DO_ANDROID=0
DO_IOS=0
DO_MAC=0
DO_WEB=0
RESOLVE_ONLY=0
STOP_SHADOW=0
CONNECT=""
SERIAL=""
NO_LAUNCH=0
SHADOW_SLUG=""
ANDROID_DEBUG=0

log(){ echo "==> $*"; }
die(){ echo "error: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend) BACKEND="${2:-}"; shift 2 ;;
    --android) DO_ANDROID=1; shift ;;
    --ios) DO_IOS=1; shift ;;
    --mac) DO_MAC=1; shift ;;
    --web) DO_WEB=1; shift ;;
    --all-clients) DO_ANDROID=1; DO_IOS=1; DO_MAC=1; shift ;;
    --resolve-only) RESOLVE_ONLY=1; shift ;;
    --stop-shadow) STOP_SHADOW=1; shift ;;
    --connect) CONNECT="${2:-}"; shift 2 ;;
    --serial) SERIAL="${2:-}"; shift 2 ;;
    --slug) SHADOW_SLUG="${2:-}"; shift 2 ;;
    --debug) ANDROID_DEBUG=1; shift ;;
    --no-launch) NO_LAUNCH=1; shift ;;
    -h|--help)
      sed -n '2,35p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    -*)
      die "unknown flag: $1"
      ;;
    *)
      if [[ -z "$QUERY" ]]; then
        QUERY="$1"
      elif [[ -z "$SERIAL" && "$1" != -* ]]; then
        # trailing device serial (emulator-5554 / 100.x:port)
        SERIAL="$1"
      else
        die "unexpected arg: $1"
      fi
      shift
      ;;
  esac
done

if [[ "$STOP_SHADOW" -eq 1 ]]; then
  "$SHADOW" stop "${QUERY:-all}"
  exit 0
fi

[[ -n "$QUERY" ]] || die "need a session name / branch / path (see --help)"
[[ -x "$RESOLVE" || -f "$RESOLVE" ]] || die "missing $RESOLVE"
chmod +x "$RESOLVE" "$SHADOW" "$DEPLOY_ANDROID" 2>/dev/null || true

# --- resolve ---
log "resolving source: $QUERY"
eval "$("$RESOLVE" "$QUERY")"
[[ -n "${workdir:-}" && -d "$workdir" ]] || die "resolve failed for: $QUERY"
log "source workdir=$workdir"
log "  name=${name:-?} branch=${branch:-?} session=${session_id:-—}"
base="${base_branch:-dev}"
repo="${repo_root:-$MAIN_ROOT}"

if [[ "$RESOLVE_ONLY" -eq 1 ]]; then
  echo "workdir=$workdir"
  echo "name=$name"
  echo "branch=$branch"
  echo "session_id=$session_id"
  echo "base_branch=$base"
  exit 0
fi

# --- classify changes ---
has_backend=0
has_android=0
has_ios=0
has_mac=0
has_web=0
if git -C "$workdir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  # Prefer merge-base with base branch tip if available
  range=""
  if git -C "$workdir" rev-parse --verify "origin/$base" >/dev/null 2>&1; then
    range="origin/$base...HEAD"
  elif git -C "$workdir" rev-parse --verify "$base" >/dev/null 2>&1; then
    range="$base...HEAD"
  fi
  if [[ -n "$range" ]]; then
    files="$(git -C "$workdir" diff --name-only "$range" 2>/dev/null || true)"
  else
    files="$(git -C "$workdir" diff --name-only HEAD~20..HEAD 2>/dev/null || true)"
  fi
  # also include uncommitted
  files="$(printf '%s\n%s\n' "$files" "$(git -C "$workdir" status --porcelain 2>/dev/null | awk '{print $2}')")"
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    case "$f" in
      src/web-app/*|src/channels/web/static/*) has_web=1 ;;
      src/*) has_backend=1 ;;
      apps/android/*|apps/shared/*) has_android=1; has_ios=1; has_mac=1 ;;
      apps/iosApp/*) has_ios=1; has_mac=1 ;;
      apps/desktop/*) has_mac=1 ;;
      apps/shared/*) has_android=1; has_ios=1; has_mac=1 ;;
    esac
  done <<<"$files"
fi
log "change map: backend=$has_backend android=$has_android ios=$has_ios mac=$has_mac web=$has_web"

# default clients
if [[ $DO_ANDROID -eq 0 && $DO_IOS -eq 0 && $DO_MAC -eq 0 && $DO_WEB -eq 0 ]]; then
  if command -v adb >/dev/null && adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}'; then
    DO_ANDROID=1
    log "default target: android (device attached)"
  else
    log "no client flags and no adb device — resolving only"
    echo "workdir=$workdir branch=$branch"
    echo "hint: re-run with --android / --ios / --mac and optionally --backend shadow"
    exit 0
  fi
fi

# --- backend ---
SHADOW_URL=""
case "$BACKEND" in
  auto)
    if [[ $has_backend -eq 1 ]]; then BACKEND=shadow; else BACKEND=none; fi
    log "backend auto → $BACKEND"
    ;;
esac

case "$BACKEND" in
  none)
    log "backend: live broker (no shadow/swap)"
    ;;
  shadow)
    log "backend: starting SHADOW from worktree (live :9898 untouched)"
    out="$("$SHADOW" start "$workdir" "${SHADOW_SLUG:-$branch}")"
    echo "$out"
    SHADOW_URL="$(echo "$out" | sed -n 's/^SHADOW_URL=//p' | tail -1)"
    SHADOW_SLUG="$(echo "$out" | sed -n 's/^SHADOW_SLUG=//p' | tail -1)"
    if [[ -n "$SHADOW_SLUG" ]]; then
      pair_out="$("$SHADOW" pair "$SHADOW_SLUG" "feel-$(date +%H%M)" || true)"
      echo "$pair_out"
    fi
    ;;
  swap)
    log "backend: LIVE PORT SWAP via preview-broker (brief disconnect; auto-revert)"
    [[ -x "$PREVIEW_BROKER" ]] || die "preview-broker not found at $PREVIEW_BROKER"
    # From an agent session this MUST be detached — see skill SKILL.md.
    systemd-run --user --collect --unit="mux-swap-runner-$(date +%s)" \
      /bin/bash "$PREVIEW_BROKER" full "$workdir" 15m
    log "swap launched as detached unit; poll: $PREVIEW_BROKER status"
    ;;
  *)
    die "unknown --backend $BACKEND (auto|none|shadow|swap)"
    ;;
esac

# --- clients ---
if [[ $DO_ANDROID -eq 1 ]]; then
  log "deploy Android from $workdir"
  args=(--from "$workdir")
  [[ -n "$CONNECT" ]] && args+=(--connect "$CONNECT")
  [[ -n "$SERIAL" ]] && args+=("$SERIAL")
  [[ $NO_LAUNCH -eq 1 ]] && args+=(--no-launch)
  [[ $ANDROID_DEBUG -eq 1 ]] && args+=(--debug)
  "$DEPLOY_ANDROID" "${args[@]}"
  if [[ -n "$SHADOW_URL" ]]; then
    log "Android is installed. Re-pair to shadow: open pair URL above or add host $SHADOW_URL"
  fi
fi

if [[ $DO_WEB -eq 1 ]]; then
  log "building web static in worktree"
  if [[ ! -d "$workdir/src/web-app/node_modules" && -d "$MAIN_ROOT/src/web-app/node_modules" ]]; then
    ln -sfn "$MAIN_ROOT/src/web-app/node_modules" "$workdir/src/web-app/node_modules"
  fi
  ( cd "$workdir/src/web-app" && bun ./node_modules/vite/bin/vite.js build ) \
    || die "web build failed"
  if [[ "$BACKEND" == "none" ]]; then
    log "web static built in worktree — for LIVE serve, also build in $MAIN_ROOT or use --backend shadow/swap"
  fi
fi

if [[ $DO_IOS -eq 1 ]]; then
  log "iOS deploy from worktree → ssh mac + xcodebuild/devicectl"
  log "(syncing worktree to mac and installing — this takes a few minutes)"
  # Lightweight: rsync worktree, build, install if device online.
  REMOTE_DIR="~/supermux-feel/$(echo "${branch:-wt}" | tr '/' '-')"
  ssh mac "mkdir -p $REMOTE_DIR"
  tar -C "$workdir" --exclude .git --exclude 'apps/shared/build' --exclude 'apps/iosApp/build' \
      --exclude node_modules --exclude '**/node_modules' -czf - . \
    | ssh mac "rm -rf $REMOTE_DIR && mkdir -p $REMOTE_DIR && tar -xzf - -C $REMOTE_DIR"
  ssh mac "source ~/ios-build-env.sh 2>/dev/null; cd $REMOTE_DIR/apps/iosApp && xcodegen generate && \
    xcodebuild -scheme Supermux -destination 'generic/platform=iOS' \
      -derivedDataPath build/dd -allowProvisioningUpdates \
      CODE_SIGN_STYLE=Automatic DEVELOPMENT_TEAM=VW4V2VS5ZV build" \
    || die "iOS build failed on mac"
  log "iOS build OK. Install with: ssh mac 'xcrun devicectl device install app --device <id> $REMOTE_DIR/apps/iosApp/build/dd/Build/Products/Debug-iphoneos/Supermux.app'"
  log "Or re-run with a connected phone UDID once wireless CoreDevice is up."
fi

if [[ $DO_MAC -eq 1 ]]; then
  log "macOS app: using mac-app-run style sync from worktree"
  if [[ -x "$MAIN_ROOT/scripts/mac-app-run.sh" ]]; then
    # mac-app-run always tars the CWD root — run from worktree by temporarily
    # pointing it via env override if we add one; for now rsync + remote build.
    REMOTE_DIR="~/supermux-feel-mac"
    tar -C "$workdir" --exclude .git --exclude 'apps/shared/build' --exclude 'apps/iosApp/build' \
        --exclude node_modules -czf - . \
      | ssh mac "rm -rf $REMOTE_DIR && mkdir -p $REMOTE_DIR && tar -xzf - -C $REMOTE_DIR"
    ssh mac "source ~/ios-build-env.sh 2>/dev/null; cd $REMOTE_DIR/apps/iosApp && xcodegen generate && \
      xcodebuild -scheme SupermuxMac -destination 'platform=macOS,arch=arm64' \
        -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO build && \
      codesign --force --sign - --deep build/dd-mac/Build/Products/Debug/Supermux.app && \
      pkill -x Supermux 2>/dev/null || true; open build/dd-mac/Build/Products/Debug/Supermux.app"
  else
    die "mac-app-run.sh missing"
  fi
fi

log "FEEL DEPLOY DONE"
echo "SOURCE=$workdir"
echo "BRANCH=${branch:-}"
echo "BACKEND=$BACKEND"
[[ -n "$SHADOW_URL" ]] && echo "SHADOW_URL=$SHADOW_URL"
echo "stop shadow later: scripts/feel-deploy.sh --stop-shadow ${SHADOW_SLUG:-}"
