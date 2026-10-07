#!/usr/bin/env bash
# Fetch the pinned zmx, apply the supermux session-contract patch, build it and
# run its own test suite.
#
#   scripts/build-zmx.sh                 fetch + patch + build + test (zmx AND helper)
#   scripts/build-zmx.sh --check-patches verify the pin and the patch only
#   scripts/build-zmx.sh --stock-test    build+test the UNPATCHED tree (baseline)
#   scripts/build-zmx.sh --no-test       skip the test step
#   scripts/build-zmx.sh --test          run the tests (the default; explicit form)
#   scripts/build-zmx.sh --helper-only   build only the broker helper
#   scripts/build-zmx.sh --target NAME   linux-x64 | linux-arm64 | macos-x64 |
#                                        macos-arm64 | native (default: native)
#   scripts/build-zmx.sh --out DIR       where the binaries + manifest land
#                                        (default: build/zmx/out). A CROSS build
#                                        must use its own dir: the default one is
#                                        what the integration suite and a source
#                                        -mode broker exec, and an aarch64 zmx
#                                        sitting there is not runnable here.
#
# The BROKER HELPER (src/core/terminal/zmx/helper) is built here too: it is
# compiled against the patched tree's own src/ipc.zig, so the pin, the patch
# and the helper are one decision and one cache. The build writes
# build/zmx/out/manifest.json naming the zmx commit, the patch sha256, the
# helper ABI and the sha256 of both binaries -- which is exactly what
# src/core/terminal/zmx/helper.ts verifies before it execs anything.
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
# OPTIMIZE MODE IS NOT OPTIONAL. `b.standardOptimizeOption` defaults to Debug,
# and a Debug zmx is not a slow build of the right program -- it is the wrong
# program. Measured on this host with the daemon's own pty read loop and NO
# broker viewer attached: 1 MiB of shell output took 24.2s (~43 KiB/s) from a
# Debug binary, against 0.15s (~28 MiB/s) through a plain pty. A workspace
# terminal that takes half a minute to print a megabyte of build log is a
# product defect, so the mode is passed explicitly and matches what upstream's
# own README tells people to build (`-Doptimize=ReleaseSafe`): the safety
# checks stay on, because this process owns a shell.
#
# Environment overrides:
#   MUX_ZIG_JOBS   parallel zig jobs (default 2 -- shared build host)
#   MUX_ZIG_HOME   where Zig lives (default ~/.local/zig/<version>)
#   MUX_ZIG_OPTIMIZE  zig optimize mode for the SHIPPED binaries
#                     (default ReleaseSafe; the test steps stay in Debug, which
#                      is where a Zig test suite is meant to run)
#   MUX_ZMX_OUT_DIR   same as --out
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR_DIR="$REPO_ROOT/vendor/zmx"
LOCK="$VENDOR_DIR/upstream.lock.json"
BUILD_DIR="$REPO_ROOT/build/zmx"
UPSTREAM_DIR="$BUILD_DIR/upstream"
HELPER_DIR="$REPO_ROOT/src/core/terminal/zmx/helper"
OUT_DIR="${MUX_ZMX_OUT_DIR:-$BUILD_DIR/out}"
ZIG_JOBS="${MUX_ZIG_JOBS:-2}"
ZIG_OPTIMIZE="${MUX_ZIG_OPTIMIZE:-ReleaseSafe}"

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
TARGET_NAME=native
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check-patches) MODE=check ;;
    --stock-test)    MODE=stock ;;
    --no-test)       MODE=build-only ;;
    --test)          MODE=build ;;
    --helper-only)   MODE=helper ;;
    --target)        shift; TARGET_NAME="${1:-}"; [[ -n "$TARGET_NAME" ]] || die "--target needs a name" ;;
    --target=*)      TARGET_NAME="${1#--target=}" ;;
    --out)           shift; OUT_DIR="${1:-}"; [[ -n "$OUT_DIR" ]] || die "--out needs a directory" ;;
    --out=*)         OUT_DIR="${1#--out=}" ;;
    -h|--help)       sed -e '1d' -e '/^[^#]/,$d' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

# Target mapping.
#
# A NAMED TARGET ALWAYS GETS AN EXPLICIT -Dtarget, host or not. It used to skip
# it when the host already matched, on the reasoning that naming the host target
# resolves a libc of its own and can silently recompile ghostty from scratch --
# half an hour on a shared machine, for a binary that was already in the cache.
# True, and the wrong trade for a release: on a linux-x86_64 runner
# `--target linux-x64` produced a binary dynamically linked against THAT
# runner's glibc, while a build of the same name anywhere else produced a
# static musl one -- and the manifest recorded `"target": "linux-x64"` either
# way, so `check-zmx-bundle.sh` could not tell them apart. A glibc-2.36-pinned
# `supermux-linux-x64` fails on any older distro, at the first terminal, with a
# loader error and nothing in the release to explain it.
#
# `--target native` (the DEFAULT, and what a developer runs) is still left
# unspecified, so the fast local path is unchanged. The cost falls exactly where
# it belongs: on the release build that names what it is producing.
HOST_ARCH="$(uname -m)"
HOST_OS="$(uname -s)"
ZIG_TARGET=""
case "$TARGET_NAME" in
  native) ;;
  linux-x64)   ZIG_TARGET="x86_64-linux-musl" ;;
  linux-arm64) ZIG_TARGET="aarch64-linux-musl" ;;
  macos-x64)   ZIG_TARGET="x86_64-macos" ;;
  macos-arm64) ZIG_TARGET="aarch64-macos" ;;
  *) die "unknown --target: $TARGET_NAME (linux-x64|linux-arm64|macos-x64|macos-arm64|native)" ;;
esac
TARGET_ARGS=()
[[ -n "$ZIG_TARGET" ]] && TARGET_ARGS=("-Dtarget=$ZIG_TARGET")

# What the binaries will actually be linked against, recorded in the manifest so
# a stager can gate on it instead of inferring it from a target NAME that two
# different builds can share. `native` is whatever this host's Zig picks --
# glibc on an ordinary Linux -- and is honest about not knowing more than that.
case "$ZIG_TARGET" in
  *-linux-musl) LIBC_NAME="musl" ;;
  *-linux-gnu*) LIBC_NAME="glibc" ;;
  *-macos*)     LIBC_NAME="system" ;;
  "")           LIBC_NAME="native-$(printf '%s' "$HOST_OS" | tr 'A-Z' 'a-z')" ;;
  *)            LIBC_NAME="unknown" ;;
esac

# ------------------------------------------------------------------ zig ----
ZIG="${MUX_ZIG_HOME:-$HOME/.local/zig/$ZIG_VERSION}/zig"
# Missing toolchain: provision it with terminal-core's installer rather than
# telling a packaging script to go and read the docs. That installer is the ONE
# place a Zig is fetched (pinned tarball + sha256 + minisign), and it is reused,
# never duplicated -- so this runs it in a SUBSHELL. common.sh defines its own
# `lock`, `LOCK`, `BUILD_DIR` and friends against the terminal-core lockfile, and
# sourcing it here would silently repoint every lookup below at the wrong pin.
#
# Only when both lockfiles name the same Zig. They are independent pins (the
# daemon-side and client-side VT versions are deliberately not forced equal), so
# a divergence is a decision somebody has to make, not something to paper over.
if [[ ! -x "$ZIG" ]]; then
  TC_LOCK="$REPO_ROOT/apps/terminal-core/native/upstream.lock.json"
  TC_ZIG="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["zig"]["version"])' "$TC_LOCK" 2>/dev/null || true)"
  if [[ "$TC_ZIG" == "$ZIG_VERSION" && -z "${MUX_ZIG_HOME:-}" ]]; then
    log "Zig $ZIG_VERSION not installed; provisioning it with apps/terminal-core/native/common.sh"
    ( TARGET="zmx-toolchain"; . "$REPO_ROOT/apps/terminal-core/native/common.sh"; ensure_zig )
  fi
fi
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
  ( cd "$dir" &&
    ZIG_GLOBAL_CACHE_DIR="$BUILD_DIR/zig-global-cache" \
      nice -n 10 "$ZIG" "$@" -j"$ZIG_JOBS" --cache-dir "$BUILD_DIR/zig-cache" \
      2>&1 | sed "s|^|[zig] |"
    exit "${PIPESTATUS[0]}" )
}

sha256_of() { ( sha256sum "$1" 2>/dev/null || shasum -a 256 "$1" ) | cut -d' ' -f1; }

# ------------------------------------------------------------- helper ------
# The helper is built HERE, not by a separate script: it compiles against the
# patched tree's own src/ipc.zig, so "which zmx" and "which helper" are one
# decision, one pin and one cache.
build_helper() {
  log "building the broker helper against the patched tree"
  run_zig "$HELPER_DIR" build \
    --build-file "$HELPER_DIR/build.zig" \
    "${TARGET_ARGS[@]}" \
    "-Doptimize=$ZIG_OPTIMIZE" \
    -Dzmx-src="$UPSTREAM_DIR/src" \
    -Dzmx-commit="$ZMX_SHA" \
    -Dpatch-sha256="$PATCH_SHA" \
    -Dhelper-version="$TARGET_NAME-$(date -u +%Y%m%dT%H%M%SZ)" \
    --prefix "$OUT_DIR"
}

test_helper() {
  log "running the helper framing tests"
  run_zig "$HELPER_DIR" build test \
    --build-file "$HELPER_DIR/build.zig" \
    "${TARGET_ARGS[@]}" \
    -Dzmx-src="$UPSTREAM_DIR/src" --summary all
}

# The manifest is the ONLY thing the TypeScript side trusts about these
# binaries: it names what they were built from and what they hash to, and
# src/core/terminal/zmx/helper.ts refuses to exec a binary whose bytes do not
# match it.
write_manifest() {
  local helper_bin="$OUT_DIR/bin/mux-zmx-helper" zmx_bin="$OUT_DIR/bin/zmx"
  [[ -x "$helper_bin" ]] || die "helper binary missing at $helper_bin"
  [[ -x "$zmx_bin" ]] || die "zmx binary missing at $zmx_bin"
  # The ABI comes from the helper's own source constant, so a cross-compiled
  # binary we cannot execute still gets a correct manifest.
  local abi
  abi="$(sed -n 's/^pub const ABI_VERSION: u32 = \([0-9]*\);.*/\1/p' "$HELPER_DIR/protocol.zig")"
  [[ -n "$abi" ]] || die "could not read ABI_VERSION from $HELPER_DIR/protocol.zig"
  if [[ -z "$ZIG_TARGET" ]]; then
    # Native build: make the BINARY say it too, and refuse a disagreement.
    local reported
    reported="$("$helper_bin" --version-json)" || die "helper --version-json failed"
    MANIFEST_ABI="$abi" python3 -c '
import json,os,sys
got = json.loads(sys.argv[1])
want = int(os.environ["MANIFEST_ABI"])
if int(got["abi"]) != want:
    raise SystemExit("helper reports ABI %s, source says %d" % (got["abi"], want))
' "$reported" || die "helper ABI does not match its source"
  fi
  python3 -c '
import json, sys, datetime
out, abi, target, optimize, commit, patch, helper_sha, zmx_sha, libc, zig_target = sys.argv[1:11]
with open(out, "w") as fh:
    json.dump({
        "schema": 1,
        "abi": int(abi),
        "target": target,
        # WHAT THIS IS LINKED AGAINST. `target` is a name two different builds
        # can share: `linux-x64` used to mean "static musl" from a cross build
        # and "the glibc on whatever runner built it" from a native one, and
        # nothing downstream could tell. Recorded so check-zmx-bundle.sh can
        # refuse the second.
        "libc": libc,
        "zigTarget": zig_target or "native",
        # Recorded, not decoration: a Debug zmx runs the pty at ~43 KiB/s and is
        # indistinguishable from a release one by sha alone.
        "optimize": optimize,
        "builtAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "helper": {"sha256": helper_sha},
        "zmx": {"commit": commit, "sha256": zmx_sha},
        "patch": {"sha256": patch},
    }, fh, indent=2)
    fh.write("\n")
' "$OUT_DIR/manifest.json" "$abi" "$TARGET_NAME" "$ZIG_OPTIMIZE" "$ZMX_SHA" "$PATCH_SHA" \
    "$(sha256_of "$helper_bin")" "$(sha256_of "$zmx_bin")" "$LIBC_NAME" "$ZIG_TARGET"
  log "manifest: $OUT_DIR/manifest.json"
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
    run_zig "$UPSTREAM_DIR" build test --summary all
    ;;
  helper)
    fetch_upstream
    verify_patch_file
    apply_patch
    build_helper
    [[ "$MODE" == build-only ]] || test_helper
    write_manifest
    log "helper: $OUT_DIR/bin/mux-zmx-helper (never installed system-wide)"
    ;;
  *)
    fetch_upstream
    verify_patch_file
    apply_patch
    log "building zmx for $TARGET_NAME ($ZIG_OPTIMIZE)"
    run_zig "$UPSTREAM_DIR" build "${TARGET_ARGS[@]}" "-Doptimize=$ZIG_OPTIMIZE" --prefix "$OUT_DIR"
    build_helper
    if [[ "$MODE" != build-only ]]; then
      log "running the upstream test target: $TEST_STEP"
      run_zig "$UPSTREAM_DIR" build test --summary all
      test_helper
    fi
    write_manifest
    log "binaries: $OUT_DIR/bin/{zmx,mux-zmx-helper} (never installed system-wide)"
    ;;
esac
