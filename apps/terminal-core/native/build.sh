#!/usr/bin/env bash
# Build pinned upstream libghostty-vt + the supermux st_* wrapper for
# one target and emit it to apps/terminal-core/build/native/<target>/.
#
#   bash apps/terminal-core/native/build.sh <target> [--test]
#
# Targets: linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64
#          android-arm64 android-x64 ios-arm64 ios-simulator-arm64
#
# Everything is resolved from native/upstream.lock.json: the Ghostty commit
# (never a moving branch), the Zig version and its tarball SHA256, and the
# per-target Zig triple. --test runs the native smoke test and the st_*
# bridge test only when the target matches this host (or, for Android, an
# attached adb device with a matching ABI); on a Linux host with gcc it also
# runs the bridge test under ASan/UBSan. A cross-compiled artifact is never
# reported as runtime-tested.
#
# Environment overrides:
#   ST_ZIG_JOBS       parallel zig jobs (default 2 — shared build host)
#   ST_ZIG_HOME       where Zig lives (default ~/.local/zig/<version>)
#   ST_ALLOW_UNVERIFIED_ZIG=1  allow a Zig download without the minisign
#                     check when python3 'cryptography' is missing (sha256 only)
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

# Package-owned ABI version of the supermux wrapper (st_* functions); must
# match ST_ABI_VERSION in include/supermux_terminal.h (checked below).
ST_ABI_VERSION=1

# Wrapper sources: compiled and linked against the static libghostty-vt into
# lib<WRAPPER_NAME> (static archive with libghostty-vt folded in, plus a
# shared library exporting only st_*/Java_*/JNI_On* where the target has
# one). The JNI glue (terminal_jni.c) joins this list in the next task.
WRAPPER_NAME="supermux_terminal"
WRAPPER_SOURCES=("$NATIVE_DIR/src/terminal_bridge.c")
FIXTURES_DIR="$PKG_DIR/fixtures/codec"

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

header_abi="$(sed -n 's/^#define ST_ABI_VERSION \([0-9]*\)u$/\1/p' "$NATIVE_DIR/include/supermux_terminal.h")"
[[ "$header_abi" == "$ST_ABI_VERSION" ]] || die "ST_ABI_VERSION=$ST_ABI_VERSION but supermux_terminal.h says '$header_abi'"

# Compile a C source for the target with the given extra flags into $2.
cc_target() {
  local src="$1" obj="$2"; shift 2
  if [[ "$TARGET" == android-* ]]; then
    "$NDK_BIN/clang" "--target=$(lock "targets.$TARGET.ndk_clang_target")" "$@" -c "$src" -o "$obj"
  else
    "$ZIG" cc -target "$ZIG_TARGET" "-mcpu=$ZIG_CPU" "$@" -c "$src" -o "$obj"
  fi
}

# Every symbol the shared library exports must be ours.
verify_exports() {
  local lib="$1" nm readobj syms
  nm="$(dirname "$LLVM_OBJCOPY")/llvm-nm"; [[ -x "$nm" ]] || nm="$(command -v llvm-nm || command -v nm)"
  readobj="$(dirname "$LLVM_OBJCOPY")/llvm-readobj"; [[ -x "$readobj" ]] || readobj="$(command -v llvm-readobj || true)"
  case "$lib" in
    *.so) syms="$("$nm" -D --defined-only "$lib" | awk '{print $NF}')" ;;
    *.dylib) syms="$("$nm" -g --defined-only "$lib" | awk '{print $NF}')" ;;
    *.dll) [[ -n "$readobj" ]] || die "llvm-readobj needed to verify $lib exports"
           syms="$("$readobj" --coff-exports "$lib" | sed -n 's/^ *Name: //p')" ;;
  esac
  local bad
  bad="$(printf '%s\n' "$syms" | grep -v '^$' | grep -Ev '^_?(st_|Java_|JNI_On)' || true)"
  [[ -z "$bad" ]] || die "$(basename "$lib") exports foreign symbols: $(echo "$bad" | head -5 | tr '\n' ' ')"
  local n
  n="$(printf '%s\n' "$syms" | grep -Ec '^_?st_' || true)"
  [[ "$n" -ge 18 ]] || die "$(basename "$lib") exports only $n st_* symbols"
  log "$(basename "$lib"): exports $n st_* symbols and nothing else"
}

build_wrapper() {
  [[ ${#WRAPPER_SOURCES[@]} -gt 0 ]] || { log "wrapper: no sources (ST_ABI_VERSION=$ST_ABI_VERSION)"; return 0; }
  local wdir="$WORK_DIR/wrapper" objs=() src obj
  rm -rf "$wdir"; mkdir -p "$wdir"
  local cflags=(-std=c11 -O2 -g0 -Wall -Wextra -Werror -fvisibility=hidden -DGHOSTTY_STATIC
                "-ffile-prefix-map=$PKG_DIR/=" -I "$NATIVE_DIR/include" -I "$OUT_DIR/include")
  [[ "$TARGET" == windows-* ]] && cflags+=(-DST_BUILDING_SHARED) || cflags+=(-fPIC)
  log "wrapper: compile ${#WRAPPER_SOURCES[@]} source(s)"
  for src in "${WRAPPER_SOURCES[@]}"; do
    obj="$wdir/$(basename "${src%.c}").o"
    cc_target "$src" "$obj" "${cflags[@]}"
    objs+=("$obj")
  done
  cp "$NATIVE_DIR/include/supermux_terminal.h" "$OUT_DIR/include/"

  # Static: libghostty-vt folded in, one archive to link.
  local ghostty_static="$OUT_DIR/lib/libghostty-vt.a" ar_format=gnu
  WRAPPER_STATIC="$OUT_DIR/lib/lib$WRAPPER_NAME.a"
  case "$TARGET" in
    windows-*) ghostty_static="$OUT_DIR/lib/ghostty-vt-static.lib"; WRAPPER_STATIC="$OUT_DIR/lib/$WRAPPER_NAME-static.lib"; ar_format=coff ;;
    macos-*|ios-*) ar_format=darwin ;;
  esac
  python3 "$NATIVE_DIR/scripts/combine_archive.py" "$ZIG" "$ar_format" "$ghostty_static" "$WRAPPER_STATIC" \
    "$wdir/combine" "${objs[@]}"

  # Shared: only st_*/Java_*/JNI_On* exported (version script / exported
  # symbols list / dllexport). iOS has no shared library (cinterop links the
  # static archive).
  local shared=""
  case "$TARGET" in
    linux-*)
      shared="$OUT_DIR/lib/lib$WRAPPER_NAME.so"
      "$ZIG" cc -target "$ZIG_TARGET" "-mcpu=$ZIG_CPU" -shared "${objs[@]}" "$ghostty_static" -lc++ \
        "-Wl,--version-script=$NATIVE_DIR/exports/$WRAPPER_NAME.map" -Wl,--gc-sections \
        "-Wl,-soname,lib$WRAPPER_NAME.so" -o "$shared" ;;
    android-*)
      shared="$OUT_DIR/lib/lib$WRAPPER_NAME.so"
      "$NDK_BIN/clang++" "--target=$(lock "targets.$TARGET.ndk_clang_target")" -shared "${objs[@]}" "$ghostty_static" \
        -static-libstdc++ "-Wl,--version-script=$NATIVE_DIR/exports/$WRAPPER_NAME.map" -Wl,--gc-sections \
        -Wl,-z,max-page-size=16384 "-Wl,-soname,lib$WRAPPER_NAME.so" -o "$shared" ;;
    macos-*)
      shared="$OUT_DIR/lib/lib$WRAPPER_NAME.dylib"
      "$ZIG" cc -target "$ZIG_TARGET" "-mcpu=$ZIG_CPU" -shared "${objs[@]}" "$ghostty_static" -lc++ \
        "-Wl,-exported_symbols_list,$NATIVE_DIR/exports/$WRAPPER_NAME.exp" \
        "-Wl,-install_name,@rpath/lib$WRAPPER_NAME.dylib" -o "$shared" ;;
    windows-*)
      shared="$OUT_DIR/lib/$WRAPPER_NAME.dll"
      "$ZIG" cc -target "$ZIG_TARGET" "-mcpu=$ZIG_CPU" -shared "${objs[@]}" "$ghostty_static" -lc++ \
        -lntdll -lkernel32 -o "$shared"
      rm -f "${shared%.dll}.pdb" ;;
  esac
  if [[ -n "$shared" ]]; then
    "$LLVM_OBJCOPY" --strip-debug "$shared"
    verify_exports "$shared"
  fi
  WRAPPER_SHARED="$shared"
}

# Load the shared library on its own (dlopen) and drive one terminal through
# it: proves the export list is complete for a JNI-style consumer.
run_shared_load_check() {
  [[ -n "${WRAPPER_SHARED:-}" && "$WRAPPER_SHARED" != *.dll ]] || return 0
  log "dlopen $(basename "$WRAPPER_SHARED") and drive a terminal through it"
  python3 - "$WRAPPER_SHARED" <<'PY'
import ctypes, sys
lib = ctypes.CDLL(sys.argv[1])
assert lib.st_abi_version() == 1, "abi"
h = ctypes.c_uint32(0)
lib.st_create.argtypes = [ctypes.c_uint32] * 6 + [ctypes.c_uint64, ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32)]
assert lib.st_create(1, 80, 24, 8, 16, 100, 1 << 20, None, ctypes.byref(h)) == 0 and h.value, "create"
data = b"\x1b[31mred\x1b[0m\x1b[6n"
assert lib.st_feed(h, data, len(data), 0) == 0, "feed"
buf, n = ctypes.POINTER(ctypes.c_uint8)(), ctypes.c_uint32(0)
assert lib.st_drain_effects(h, ctypes.byref(buf), ctypes.byref(n)) == 0, "drain"
raw = bytes(buf[:n.value])
assert raw[:4] == b"TVTS" and raw.endswith(b"\x1b[1;4R"), raw
lib.st_free_buffer(buf)
assert lib.st_read_viewport(h, 1, ctypes.byref(buf), ctypes.byref(n), None) == 0 and n.value > 12, "read"
lib.st_free_buffer(buf)
assert lib.st_destroy(h) == 0 and lib.st_destroy(h) == -1, "destroy"
print("shared library load check OK", file=sys.stderr)
PY
}

# The st_* contract test, linked against the combined static archive.
build_bridge_test() {
  [[ ${#WRAPPER_SOURCES[@]} -gt 0 ]] || return 0
  local exe="terminal_bridge_test" obj="$WORK_DIR/terminal_bridge_test.o"
  [[ "$TARGET" == windows-* ]] && exe="terminal_bridge_test.exe"
  BRIDGE_EXE="$OUT_DIR/bin/$exe"
  local cflags=(-std=c11 -O2 -g0 -Wall -Wextra -Werror "-ffile-prefix-map=$PKG_DIR/="
                -I "$NATIVE_DIR/include" -I "$OUT_DIR/include")
  log "compile + link st_* bridge test against lib$WRAPPER_NAME"
  cc_target "$NATIVE_DIR/tests/terminal_bridge_test.c" "$obj" "${cflags[@]}"
  if [[ "$TARGET" == android-* ]]; then
    "$NDK_BIN/clang++" "--target=$(lock "targets.$TARGET.ndk_clang_target")" "$obj" "$WRAPPER_STATIC" \
      -static-libstdc++ -Wl,-z,max-page-size=16384 -o "$BRIDGE_EXE"
  else
    local extra=(-lc++)
    [[ "$TARGET" == windows-* ]] && extra+=(-lntdll -lkernel32)
    "$ZIG" cc -target "$ZIG_TARGET" "$obj" "$WRAPPER_STATIC" "${extra[@]}" -o "$BRIDGE_EXE"
  fi
  "$LLVM_OBJCOPY" --strip-debug "$BRIDGE_EXE"
  rm -f "${BRIDGE_EXE%.exe}.pdb"
}

# Sanitizer build of the bridge test (Linux host only: gcc ships ASan/UBSan
# runtimes, zig cc has UBSan but no ASan runtime). Not an artifact.
build_bridge_asan() {
  BRIDGE_ASAN_EXE=""
  [[ ${#WRAPPER_SOURCES[@]} -gt 0 && "$TARGET" == linux-* && "$host_os" == linux ]] || return 0
  [[ "$TARGET" == linux-x64 && "$host_arch" == x86_64 || "$TARGET" == linux-arm64 && "$host_arch" == aarch64 ]] || return 0
  command -v gcc >/dev/null || { log "gcc not found: sanitizer run skipped"; return 0; }
  BRIDGE_ASAN_EXE="$WORK_DIR/terminal_bridge_test_asan"
  log "compile bridge test with ASan + UBSan (gcc)"
  gcc -std=c11 -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize-recover=undefined \
    -Wall -Wextra -Werror -I "$NATIVE_DIR/include" -I "$OUT_DIR/include" \
    "${WRAPPER_SOURCES[@]}" "$NATIVE_DIR/tests/terminal_bridge_test.c" "$OUT_DIR/lib/libghostty-vt.a" -lm \
    -o "$BRIDGE_ASAN_EXE" || { log "sanitizer build failed"; return 1; }
}

# CodecGolden.kt must be generated from the checked-in fixtures/codec/*.bin.
check_codec_golden() {
  [[ -d "$FIXTURES_DIR" ]] || return 0
  python3 "$NATIVE_DIR/scripts/gen_codec_golden.py" --check "$FIXTURES_DIR" \
    "$PKG_DIR/src/commonTest/kotlin/dev/supermux/terminal/CodecGolden.kt" ||
    die "CodecGolden.kt is out of date: run native/scripts/gen_codec_golden.py"
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
  TEST_HOST="$HOST_KEY $(uname -r)"
  if ! "$SMOKE_EXE"; then
    TEST_RESULT="failed"
    return 1
  fi
  if [[ -n "${BRIDGE_EXE:-}" ]]; then
    log "running st_* bridge test on host $HOST_KEY"
    if ! ST_FIXTURES_DIR="$FIXTURES_DIR" "$BRIDGE_EXE"; then
      TEST_RESULT="failed"
      return 1
    fi
  fi
  run_shared_load_check || { TEST_RESULT="failed"; return 1; }
  if [[ -n "${BRIDGE_ASAN_EXE:-}" ]]; then
    log "running st_* bridge test under ASan + UBSan (heavy fixtures skipped)"
    if ! ST_FIXTURES_DIR="$FIXTURES_DIR" ST_SKIP_HEAVY=1 ASAN_OPTIONS=detect_leaks=1:abort_on_error=1 \
         UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1 "$BRIDGE_ASAN_EXE"; then
      TEST_RESULT="failed"
      return 1
    fi
  fi
  RUNTIME_TESTED=true; TEST_RESULT="passed"
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
  log "running smoke + bridge tests on adb device $serial ($want_abi)"
  adb -s "$serial" push "$SMOKE_EXE" /data/local/tmp/ghostty_smoke_test >/dev/null
  adb -s "$serial" push "$BRIDGE_EXE" /data/local/tmp/terminal_bridge_test >/dev/null
  if adb -s "$serial" shell 'cd /data/local/tmp && chmod 755 ghostty_smoke_test terminal_bridge_test && ./ghostty_smoke_test && ./terminal_bridge_test; echo "exit=$?"' | tee "$WORK_DIR/android-test.log" | grep -q '^exit=0'; then
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
build_bridge_test
check_no_abs_paths
check_codec_golden
if [[ $RUN_TEST -eq 1 ]]; then
  build_bridge_asan
fi

status=0
if [[ $RUN_TEST -eq 1 ]]; then
  run_smoke || status=$?
fi
write_manifest
if [[ $RUN_TEST -eq 1 ]]; then
  log "test result: $TEST_RESULT (runtime_tested=$RUNTIME_TESTED)"
fi
exit $status
