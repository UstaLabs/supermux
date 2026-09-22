#!/usr/bin/env bash
# Build pinned upstream libghostty-vt (+ supermux wrapper, once it exists) for
# one target and emit it to apps/terminal-core/build/native/<target>/.
#
#   bash apps/terminal-core/native/build.sh <target> [--test]
#
# Targets: linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64
#          android-arm64 android-x64 ios-arm64 ios-simulator-arm64
#
# Everything is resolved from native/upstream.lock.json: the Ghostty commit
# (never a moving branch), the Zig version and its tarball SHA256, and the
# per-target Zig triple. --test runs the native smoke test only when the
# target matches this host (or, for Android, an attached adb device with a
# matching ABI). A cross-compiled artifact is never reported as runtime-tested.
#
# Environment overrides:
#   ST_ZIG_JOBS       parallel zig jobs (default 2 — shared build host)
#   ST_ZIG_HOME       where Zig lives (default ~/.local/zig/<version>)
#   ANDROID_NDK_HOME  NDK root for android-* (default: lock's NDK version under
#                     $ANDROID_HOME / $ANDROID_SDK_ROOT / ~/Android/Sdk)
set -euo pipefail

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

[[ $# -ge 1 ]] || usage
TARGET="$1"; shift
RUN_TEST=0
for arg in "$@"; do
  case "$arg" in
    --test) RUN_TEST=1 ;;
    *) echo "unknown argument: $arg" >&2; usage ;;
  esac
done

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$NATIVE_DIR/common.sh"
OUT_DIR="$BUILD_DIR/native/$TARGET"
WORK_DIR="$BUILD_DIR/work/$TARGET"

# Package-owned ABI version of the supermux wrapper (st_* functions). 0 means
# "no wrapper yet: raw libghostty-vt only". Bumped by the wrapper task.
ST_ABI_VERSION=0

# Wrapper sources hook: later tasks add native/src/terminal_bridge.c and
# native/src/terminal_jni.c here. When non-empty, they are compiled and linked
# against the static libghostty-vt into lib<WRAPPER_NAME> for the target.
WRAPPER_NAME="supermux_terminal"
WRAPPER_SOURCES=()

ZIG_TARGET="$(lock_opt "targets.$TARGET.zig_target")"
[[ -n "$ZIG_TARGET" ]] || die "unknown target '$TARGET'"
ZIG_CPU="$(lock "targets.$TARGET.cpu")"

# ------------------------------------------------------ target setup -----
ensure_android_ndk() {
  [[ "$TARGET" == android-* ]] || return 0
  if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    local ndk_ver sdk
    ndk_ver="$(lock "targets.$TARGET.ndk")"
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
      if [[ -n "$sdk" && -d "$sdk/ndk/$ndk_ver" ]]; then
        export ANDROID_NDK_HOME="$sdk/ndk/$ndk_ver"
        break
      fi
    done
  fi
  [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]] ||
    die "Android NDK not found (set ANDROID_NDK_HOME)"
  NDK_ID="$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties" 2>/dev/null || true)"
  NDK_BIN="$(ndk_bin_dir "$ANDROID_NDK_HOME")"
}

ndk_bin_dir() {
  local d
  for d in "$1"/toolchains/llvm/prebuilt/*/bin; do
    [[ -x "$d/clang" ]] && { echo "$d"; return; }
  done
}

# llvm-objcopy (all object formats) for stripping. Resolution: $ST_LLVM_OBJCOPY,
# then the pinned NDK's (same version as the Android targets), then PATH.
ensure_llvm_objcopy() {
  LLVM_OBJCOPY="${ST_LLVM_OBJCOPY:-}"
  if [[ -z "$LLVM_OBJCOPY" ]]; then
    local ndk_ver sdk bin
    ndk_ver="$(lock targets.android-arm64.ndk)"
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
      [[ -n "$sdk" && -d "$sdk/ndk/$ndk_ver" ]] || continue
      bin="$(ndk_bin_dir "$sdk/ndk/$ndk_ver")"
      [[ -n "$bin" && -x "$bin/llvm-objcopy" ]] && { LLVM_OBJCOPY="$bin/llvm-objcopy"; break; }
    done
  fi
  [[ -n "$LLVM_OBJCOPY" ]] || LLVM_OBJCOPY="$(command -v llvm-objcopy || true)"
  [[ -n "$LLVM_OBJCOPY" && -x "$LLVM_OBJCOPY" ]] ||
    die "llvm-objcopy not found (install the pinned NDK or set ST_LLVM_OBJCOPY)"
  LLVM_OBJCOPY_ID="$("$LLVM_OBJCOPY" --version | sed -n 's/.*LLVM version \([^ ]*\).*/\1/p' | head -1)"
  [[ -n "$LLVM_OBJCOPY_ID" ]] || LLVM_OBJCOPY_ID="$("$LLVM_OBJCOPY" --version | head -2 | tail -1 | tr -s ' ')"
}

require_apple_host() {
  case "$TARGET" in
    ios-*)
      [[ "$host_os" == macos ]] || die "$TARGET needs a macOS host with Xcode (upstream links against the Apple SDK); build it on the Mac"
      ;;
  esac
}

ZIG_LOCAL_CACHE="$BUILD_DIR/zig-cache/$TARGET"
# zig cc (probe + smoke link) uses the same caches as zig build.
export ZIG_GLOBAL_CACHE_DIR="$ZIG_GLOBAL_CACHE" ZIG_LOCAL_CACHE_DIR="$ZIG_LOCAL_CACHE"

build_ghostty() {
  local prefix="$WORK_DIR/ghostty"
  rm -rf "$prefix"
  log "zig build libghostty-vt ($ZIG_TARGET, cpu=$ZIG_CPU, -j$ZIG_JOBS)"
  (cd "$UPSTREAM_DIR" && nice -n 15 "$ZIG" build \
      "-j$ZIG_JOBS" \
      --global-cache-dir "$ZIG_GLOBAL_CACHE" \
      --cache-dir "$ZIG_LOCAL_CACHE" \
      --prefix "$prefix" \
      -Demit-lib-vt=true \
      -Doptimize=ReleaseFast \
      "-Dtarget=$ZIG_TARGET" \
      "-Dcpu=$ZIG_CPU" \
      "-Dlib-version-string=$LIBVT_VERSION")
}

# Header-only probe: every pinned declaration the package depends on must
# compile before anything links. Missing API is a reported failure.
header_probe() {
  log "header probe"
  mkdir -p "$WORK_DIR/probe"
  "$ZIG" cc -target "$ZIG_TARGET" -std=c11 -ffreestanding -Wall -Wextra -Werror \
    -I "$UPSTREAM_DIR/include" -c "$NATIVE_DIR/tests/header_probe.c" \
    -o "$WORK_DIR/probe/header_probe.o"
}

build_wrapper() {
  [[ ${#WRAPPER_SOURCES[@]} -gt 0 ]] || { log "wrapper: no sources yet (ST_ABI_VERSION=$ST_ABI_VERSION)"; return 0; }
  die "wrapper build not implemented yet; add it together with WRAPPER_SOURCES"
}

build_smoke() {
  local exe="ghostty_smoke_test"
  [[ "$TARGET" == windows-* ]] && exe="ghostty_smoke_test.exe"
  local static_lib="$OUT_DIR/lib/libghostty-vt.a"
  [[ "$TARGET" == windows-* ]] && static_lib="$OUT_DIR/lib/ghostty-vt-static.lib"
  # Strict flags apply to our test source only: zig cc forwards compile flags
  # to the libc++ it builds on demand, so compile and link in separate steps.
  local cflags=(-std=c11 -O2 -g0 -Wall -Wextra -Werror -DGHOSTTY_STATIC
                "-ffile-prefix-map=$PKG_DIR/=" -I "$OUT_DIR/include")
  local obj="$WORK_DIR/ghostty_smoke_test.o"
  SMOKE_EXE="$OUT_DIR/bin/$exe"
  log "compile + link smoke test against the static library"
  if [[ "$TARGET" == android-* ]]; then
    # Link with the NDK's own clang + libc++, exactly like a Gradle JNI build.
    local clang_target
    clang_target="$(lock "targets.$TARGET.ndk_clang_target")"
    "$NDK_BIN/clang" "--target=$clang_target" "${cflags[@]}" \
      -c "$NATIVE_DIR/tests/ghostty_smoke_test.c" -o "$obj"
    "$NDK_BIN/clang++" "--target=$clang_target" "$obj" "$static_lib" \
      -static-libstdc++ -Wl,-z,max-page-size=16384 -o "$SMOKE_EXE"
  else
    "$ZIG" cc -target "$ZIG_TARGET" "${cflags[@]}" \
      -c "$NATIVE_DIR/tests/ghostty_smoke_test.c" -o "$obj"
    # The static archive bundles C++ SIMD code (highway/simdutf) -> libc++.
    local extra=(-lc++)
    [[ "$TARGET" == windows-* ]] && extra+=(-lntdll -lkernel32)
    "$ZIG" cc -target "$ZIG_TARGET" "$obj" "$static_lib" "${extra[@]}" -o "$SMOKE_EXE"
  fi
  "$LLVM_OBJCOPY" --strip-debug "$SMOKE_EXE"
  rm -f "${SMOKE_EXE%.exe}.pdb"   # Windows: debug-only, contains absolute paths
}

stage_outputs() {
  local prefix="$WORK_DIR/ghostty"
  rm -rf "$OUT_DIR"
  mkdir -p "$OUT_DIR/lib" "$OUT_DIR/bin" "$OUT_DIR/include"
  cp -R "$prefix/include/ghostty" "$OUT_DIR/include/"
  # Libraries (symlinks preserved). pkg-config files are dropped: they embed
  # the absolute --prefix.
  (cd "$prefix/lib" && find . -maxdepth 1 \( -type f -o -type l \) ! -name '*.pc' -print0 |
     xargs -0 -I{} cp -P {} "$OUT_DIR/lib/")
  if [[ -d "$prefix/bin" ]]; then
    (cd "$prefix/bin" && find . -maxdepth 1 \( -type f -o -type l \) -print0 |
       xargs -0 -I{} cp -P {} "$OUT_DIR/bin/")
  fi
}

# Upstream leaves DWARF (with absolute cache/source paths) in lib-vt and its
# combined archive names members by absolute object paths. Strip debug info
# (symbols stay) and rename archive members so artifacts are path-free.
normalize_outputs() {
  local ar_format=gnu f
  case "$TARGET" in macos-*|ios-*) ar_format=darwin ;; esac
  for f in "$OUT_DIR"/lib/*.a "$OUT_DIR"/lib/*.lib; do
    [[ -f "$f" && ! -L "$f" ]] || continue
    # DLL import libraries hold short-import members only (no paths, no DWARF).
    case "$f" in *.dll.a|*/ghostty-vt.lib) continue ;; esac
    python3 "$NATIVE_DIR/scripts/normalize_archive.py" "$ZIG" "$LLVM_OBJCOPY" "$ar_format" \
      "$f" "$f.norm" "$WORK_DIR/ar-normalize"
    mv "$f.norm" "$f"
  done
  while IFS= read -r -d '' f; do
    "$LLVM_OBJCOPY" --strip-debug "$f"
  done < <(find "$OUT_DIR/lib" "$OUT_DIR/bin" -type f \( -name '*.so*' -o -name '*.dylib' -o -name '*.dll' \) -print0)
  # Windows PDBs are debug-only and full of absolute paths; not shipped.
  find "$OUT_DIR" -name '*.pdb' -delete
}

check_no_abs_paths() {
  local bad=0 f
  local needles=("$HOME" "$PKG_DIR" "$BUILD_DIR")
  while IFS= read -r -d '' f; do
    for n in "${needles[@]}"; do
      if grep -aqF "$n" "$f"; then
        log "absolute developer path '$n' found in ${f#"$OUT_DIR"/}"
        bad=1
      fi
    done
  done < <(find "$OUT_DIR" -type f -print0)
  [[ $bad -eq 0 ]] || die "artifacts contain absolute developer paths"
  log "no absolute developer paths in artifacts"
}

# --------------------------------------------------------- run test ------
RUNTIME_TESTED=false
TEST_HOST=""
TEST_RESULT="not-run"

run_smoke() {
  local want_os want_arch
  case "$TARGET" in
    linux-x64) want_os=linux; want_arch=x86_64 ;;
    linux-arm64) want_os=linux; want_arch=aarch64 ;;
    macos-x64) want_os=macos; want_arch=x86_64 ;;
    macos-arm64) want_os=macos; want_arch=aarch64 ;;
    windows-x64) want_os=windows; want_arch=x86_64 ;;
    android-*) run_smoke_android; return ;;
    ios-*) log "--test: $TARGET needs an iOS device/simulator run via Xcode; not runtime-tested"; TEST_RESULT="no-matching-device"; return 3 ;;
  esac
  if [[ "$host_os" != "$want_os" || "$host_arch" != "$want_arch" ]]; then
    log "--test: host $HOST_KEY does not match $TARGET; artifact is NOT runtime-tested"
    TEST_RESULT="host-mismatch"
    return 3
  fi
  log "running smoke test on host $HOST_KEY"
  if "$SMOKE_EXE"; then
    RUNTIME_TESTED=true; TEST_RESULT="passed"; TEST_HOST="$HOST_KEY $(uname -r)"
  else
    TEST_RESULT="failed"; TEST_HOST="$HOST_KEY $(uname -r)"
    return 1
  fi
}

run_smoke_android() {
  local want_abi serial=""
  case "$TARGET" in android-arm64) want_abi=arm64-v8a ;; android-x64) want_abi=x86_64 ;; esac
  if command -v adb >/dev/null; then
    local s
    for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
      if [[ "$(adb -s "$s" shell getprop ro.product.cpu.abi | tr -d '\r')" == "$want_abi" ]]; then
        serial="$s"; break
      fi
    done
  fi
  if [[ -z "$serial" ]]; then
    log "--test: no adb device with ABI $want_abi attached; artifact is NOT runtime-tested"
    TEST_RESULT="no-matching-device"
    return 3
  fi
  log "running smoke test on adb device $serial ($want_abi)"
  adb -s "$serial" push "$SMOKE_EXE" /data/local/tmp/ghostty_smoke_test >/dev/null
  if adb -s "$serial" shell 'chmod 755 /data/local/tmp/ghostty_smoke_test && /data/local/tmp/ghostty_smoke_test; echo "exit=$?"' | tee "$WORK_DIR/android-test.log" | grep -q '^exit=0'; then
    RUNTIME_TESTED=true; TEST_RESULT="passed"
  else
    TEST_RESULT="failed"
  fi
  TEST_HOST="adb $(adb -s "$serial" shell getprop ro.product.model | tr -d '\r') API $(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
  cat "$WORK_DIR/android-test.log"
  [[ "$TEST_RESULT" == passed ]]
}

# --------------------------------------------------------- manifest ------
write_manifest() {
  python3 - "$OUT_DIR" <<PY
import hashlib, json, os, sys
out = sys.argv[1]
files = []
for root, dirs, names in os.walk(out):
    dirs.sort()
    for n in sorted(names):
        p = os.path.join(root, n)
        rel = os.path.relpath(p, out)
        if rel == "manifest.json":
            continue
        if os.path.islink(p):
            files.append({"path": rel, "symlink": os.readlink(p)})
            continue
        h = hashlib.sha256(open(p, "rb").read()).hexdigest()
        files.append({"path": rel, "sha256": h, "size": os.path.getsize(p)})
manifest = {
    "schema": 1,
    "target": "$TARGET",
    "zig_target": "$ZIG_TARGET",
    "cpu": "$ZIG_CPU",
    "abi_version": $ST_ABI_VERSION,
    "wrapper": ${#WRAPPER_SOURCES[@]} > 0,
    "ghostty_commit": "$GHOSTTY_SHA",
    "libghostty_vt_version": "$LIBVT_VERSION",
    "zig_version": "$ZIG_VERSION",
    "optimize": "ReleaseFast",
    "build_host": "$HOST_KEY",
    "android_ndk": "${NDK_ID:-}" or None,
    "strip_tool": "llvm-objcopy $LLVM_OBJCOPY_ID",
    "runtime_tested": "$RUNTIME_TESTED" == "true",
    "test_result": "$TEST_RESULT",
    "test_host": "$TEST_HOST" or None,
    "files": files,
}
with open(os.path.join(out, "manifest.json"), "w") as f:
    json.dump(manifest, f, indent=2, sort_keys=False)
    f.write("\n")
PY
  log "manifest: ${OUT_DIR#"$PKG_DIR"/}/manifest.json"
}

# ------------------------------------------------------------- main ------
require_apple_host
ensure_zig
ensure_upstream
ensure_android_ndk
ensure_llvm_objcopy
header_probe
build_ghostty
stage_outputs
normalize_outputs
build_wrapper
build_smoke
check_no_abs_paths

status=0
if [[ $RUN_TEST -eq 1 ]]; then
  run_smoke || status=$?
fi
write_manifest
if [[ $RUN_TEST -eq 1 ]]; then
  log "test result: $TEST_RESULT (runtime_tested=$RUNTIME_TESTED)"
fi
exit $status
