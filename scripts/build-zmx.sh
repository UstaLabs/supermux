#!/usr/bin/env bash
# Fetch the pinned zmx, apply the supermux session-contract patch, build it and
# run its own test suite.
#
#   scripts/build-zmx.sh                 fetch + patch + build + test
#   scripts/build-zmx.sh --check-patches verify the pin and the patch only
#   scripts/build-zmx.sh --stock-test    build+test the UNPATCHED tree (baseline)
#   scripts/build-zmx.sh --no-test       skip the test step
#
# Everything is resolved from vendor/zmx/upstream.lock.json: the zmx commit
# (never a branch), the patch and its sha256, and the Zig version. The Zig
# toolchain is the one apps/terminal-core/native/common.sh already provisions
# (pinned tarball + sha256 + minisign) -- this script reuses it and never
# installs a second one.
#
# It NEVER installs, replaces or touches a system zmx binary: the build output
# lives under the gitignored build/zmx/ cache and nothing is copied out of it.
#
# Environment overrides:
#   MUX_ZIG_JOBS   parallel zig jobs (default 2 -- shared build host)
#   MUX_ZIG_HOME   where Zig lives (default ~/.local/zig/<version>)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR_DIR="$REPO_ROOT/vendor/zmx"
LOCK="$VENDOR_DIR/upstream.lock.json"
BUILD_DIR="$REPO_ROOT/build/zmx"
UPSTREAM_DIR="$BUILD_DIR/upstream"
ZIG_JOBS="${MUX_ZIG_JOBS:-2}"

log() { printf '[zmx] %s\n' "$*" >&2; }
die() { printf '[zmx] ERROR: %s\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null || die "python3 is required"
command -v git >/dev/null || die "git is required"
[[ -f "$LOCK" ]] || die "missing $LOCK"

lock() { python3 -c 'import json,sys
d=json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."): d=d[k] if not k.isdigit() else d[int(k)]
print(d)' "$LOCK" "$1"; }

ZMX_REPO="$(lock zmx.repository)"
ZMX_SHA="$(lock zmx.commit)"
ZIG_VERSION="$(lock zig.version)"
PATCH_REL="$(lock patches.0.file)"
PATCH_SHA="$(lock patches.0.sha256)"
PATCH_APPLIES_TO="$(lock patches.0.applies_to)"
PATCH="$VENDOR_DIR/$PATCH_REL"
TEST_STEP="$(lock build.test_step)"

MODE=build
for arg in "$@"; do
  case "$arg" in
    --check-patches) MODE=check ;;
    --stock-test)    MODE=stock ;;
    --no-test)       MODE=build-only ;;
    -h|--help)       sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $arg" ;;
  esac
done

# ------------------------------------------------------------------ zig ----
ZIG="${MUX_ZIG_HOME:-$HOME/.local/zig/$ZIG_VERSION}/zig"
[[ -x "$ZIG" ]] || die "Zig $ZIG_VERSION not found at $ZIG.
Provision it with the pinned, signature-verified installer:
  bash apps/terminal-core/native/build.sh linux-x64   # or your target"
[[ "$("$ZIG" version)" == "$ZIG_VERSION" ]] || die "$ZIG is not $ZIG_VERSION"

# ------------------------------------------------------------- upstream ----
# A lockfile pin is only worth something if a stale or hand-edited cache is
# refused rather than silently built. Both are fatal here, never auto-fixed:
# "delete it to refetch" is a decision, not a side effect of a build.
fetch_upstream() {
  if [[ -d "$UPSTREAM_DIR/.git" ]]; then
    local head; head="$(git -C "$UPSTREAM_DIR" rev-parse HEAD 2>/dev/null || true)"
    if [[ "$head" == "$ZMX_SHA" ]]; then return; fi
    die "upstream cache $UPSTREAM_DIR is at $head, lockfile pins $ZMX_SHA; delete it to refetch"
  fi
  log "fetching zmx $ZMX_SHA"
  rm -rf "$UPSTREAM_DIR"; mkdir -p "$UPSTREAM_DIR"
  git -C "$UPSTREAM_DIR" init -q
  git -C "$UPSTREAM_DIR" remote add origin "$ZMX_REPO"
  git -C "$UPSTREAM_DIR" fetch -q --depth 1 origin "$ZMX_SHA"
  git -C "$UPSTREAM_DIR" checkout -q --detach FETCH_HEAD
  [[ "$(git -C "$UPSTREAM_DIR" rev-parse HEAD)" == "$ZMX_SHA" ]] || die "fetched commit mismatch"
}

verify_patch_file() {
  [[ -f "$PATCH" ]] || die "missing patch $PATCH"
  local got
  got="$( (sha256sum "$PATCH" 2>/dev/null || shasum -a 256 "$PATCH") | cut -d' ' -f1)"
  [[ "$got" == "$PATCH_SHA" ]] || die "patch sha256 mismatch: got $got want $PATCH_SHA
If you edited the patch on purpose, update patches[0].sha256 in $LOCK."
  [[ "$PATCH_APPLIES_TO" == "$ZMX_SHA" ]] || die \
    "patch declares applies_to=$PATCH_APPLIES_TO but zmx.commit=$ZMX_SHA"
}

# `git apply --check` against a pristine tree. A patch that applies with fuzz
# to a DIFFERENT tree is exactly the failure this is here to catch, so the
# tree must also be clean.
check_patch_applies() {
  local dirty; dirty="$(git -C "$UPSTREAM_DIR" status --porcelain --untracked-files=no)"
  if [[ -n "$dirty" ]]; then
    # Already patched? Then the patch must REVERSE cleanly, and nothing else
    # may have been changed on top of it.
    if git -C "$UPSTREAM_DIR" apply --check --reverse "$PATCH" 2>/dev/null &&
       [[ "$(git -C "$UPSTREAM_DIR" diff --name-only | sort | tr '\n' ' ')" == "src/ipc.zig src/loop.zig src/util.zig " ]]; then
      log "upstream already carries exactly this patch"
      return 0
    fi
    die "upstream cache $UPSTREAM_DIR is dirty with changes that are not this patch:
$dirty
Delete build/zmx/upstream and re-run."
  fi
  git -C "$UPSTREAM_DIR" apply --check "$PATCH" ||
    die "patch does not apply to $ZMX_SHA"
}

apply_patch() {
  if git -C "$UPSTREAM_DIR" apply --check --reverse "$PATCH" 2>/dev/null; then
    log "patch already applied"
    return
  fi
  log "applying $PATCH_REL"
  git -C "$UPSTREAM_DIR" apply "$PATCH"
}

run_zig() {
  local dir="$1"; shift
  ZIG_GLOBAL_CACHE_DIR="$BUILD_DIR/zig-global-cache" \
    nice -n 10 "$ZIG" "$@" -j"$ZIG_JOBS" --cache-dir "$BUILD_DIR/zig-cache" \
    2>&1 | sed "s|^|[zig] |"
  return "${PIPESTATUS[0]}"
}

case "$MODE" in
  check)
    fetch_upstream
    verify_patch_file
    check_patch_applies
    log "OK: zmx pinned at $ZMX_SHA, patch $PATCH_REL verified and applies cleanly"
    ;;
  stock)
    fetch_upstream
    [[ -z "$(git -C "$UPSTREAM_DIR" status --porcelain --untracked-files=no)" ]] ||
      die "--stock-test needs a pristine tree; delete build/zmx/upstream and re-run"
    log "running the UNPATCHED baseline: $TEST_STEP"
    ( cd "$UPSTREAM_DIR" && run_zig "$UPSTREAM_DIR" build test --summary all )
    ;;
  *)
    fetch_upstream
    verify_patch_file
    apply_patch
    log "building zmx"
    ( cd "$UPSTREAM_DIR" && run_zig "$UPSTREAM_DIR" build --prefix "$BUILD_DIR/out" )
    if [[ "$MODE" != build-only ]]; then
      log "running the upstream test target: $TEST_STEP"
      ( cd "$UPSTREAM_DIR" && run_zig "$UPSTREAM_DIR" build test --summary all )
    fi
    log "binary: $BUILD_DIR/out/bin/zmx (never installed system-wide)"
    ;;
esac
