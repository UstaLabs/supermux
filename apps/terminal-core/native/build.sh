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
#   ST_GHOSTTY_OPTIMIZE  zig optimize mode for libghostty-vt (default ReleaseFast;
#                     read the note at build_ghostty BEFORE changing it — it
#                     records why, and what ReleaseSafe currently breaks).
#   ST_ZIG_HOME       where Zig lives (default ~/.local/zig/<version>)
#   ST_ALLOW_UNVERIFIED_ZIG=1  allow a Zig download without the minisign
#                     check when python3 'cryptography' is missing (sha256 only)
#   ANDROID_NDK_HOME  NDK root for android-* (default: lock's NDK version under
#                     $ANDROID_HOME / $ANDROID_SDK_ROOT / ~/Android/Sdk)
#   JAVA_HOME         JDK whose include/jni.h builds the JNI library
#                     (default: the JDK `java` resolves to; macOS: java_home)
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
ST_ABI_VERSION=2

# Wrapper sources: compiled and linked against the static libghostty-vt into
# lib<WRAPPER_NAME> (static archive with libghostty-vt folded in, plus a
# shared library exporting only st_*/Java_*/JNI_On* where the target has
# one).
WRAPPER_NAME="supermux_terminal"
WRAPPER_SOURCES=("$NATIVE_DIR/src/terminal_bridge.c")

# EXPLOIT MITIGATIONS, on every C object and every ELF we ship.
#
# These libraries are in the process that renders a terminal, and the bytes they
# parse come off a socket from a shell somebody else's program is writing to.
# They were built with none of the mitigations a distribution would require of
# any other package handling remote input: no stack canaries, no fortified
# string/memory calls, a writable GOT and a PT_GNU_STACK the loader was free to
# map executable.
#
#   -fstack-protector-strong  canary on any frame with an array or an address-
#                             taken local; "strong" rather than "all" is the
#                             modern distribution default and costs ~1-2%.
#   -D_FORTIFY_SOURCE=2       compile-time and cheap runtime bounds on the
#                             mem*/str*/sprintf family. -U first, because some
#                             toolchains (the NDK) define it themselves and a
#                             redefinition warning is fatal under -Werror.
#   -z relro -z now           the GOT is mapped read-only after relocation, so
#                             it stops being a write-what-where target.
#   -z noexecstack            an explicitly non-executable PT_GNU_STACK rather
#                             than whatever the linker inferred.
#
# ELF only. macOS and Windows have their own mechanisms (hardened runtime,
# /NXCOMPAT + /DYNAMICBASE) reached by different flags, and claiming these there
# would be decoration.
HARDEN_CFLAGS=(-fstack-protector-strong -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2)
HARDEN_LDFLAGS=(-Wl,-z,relro,-z,now -Wl,-z,noexecstack)
# JNI glue (Android + desktop JVM): its own shared library lib<JNI_NAME>
# linking the combined static archive above (see build_jni).
JNI_NAME="supermux_terminal_jni"
JNI_SOURCE="$NATIVE_DIR/src/terminal_jni.c"
# Java_* entry points terminal_jni.c defines: 17 NativeTerminal_* (one per
# st_* call) in every library; the -DST_JNI_TEST_HOOKS variant (--test only,
# test-lib/, never packaged) adds 2 NativeTerminalTestHooks_*.
JNI_EXPORT_COUNT=17
JNI_TEST_HOOK_COUNT=2
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
  [[ -n "$LLVM_OBJCOPY" || "$host_os" != macos ]] || apple_strip_shim
  [[ -n "$LLVM_OBJCOPY" && -x "$LLVM_OBJCOPY" ]] ||
    die "llvm-objcopy not found (install the pinned NDK or set ST_LLVM_OBJCOPY)"
  LLVM_OBJCOPY_ID="$("$LLVM_OBJCOPY" --version | sed -n 's/.*LLVM version \([^ ]*\).*/\1/p' | head -1)"
  [[ -n "$LLVM_OBJCOPY_ID" ]] || LLVM_OBJCOPY_ID="$("$LLVM_OBJCOPY" --version | head -2 | tail -1 | tr -s ' ')"
}

# macOS host without LLVM binutils (Xcode ships llvm-nm/llvm-objdump but no
# llvm-objcopy): build.sh only ever runs `llvm-objcopy --strip-debug <file>`
# and `--version`, so a shim maps that to Apple's `strip -S` (which keeps the
# symbol table and re-signs linker-signed binaries) and puts Xcode's llvm-nm
# next to it for verify_exports.
apple_strip_shim() {
  local dir="$BUILD_DIR/tools/apple-strip"
  mkdir -p "$dir"
  cat > "$dir/llvm-objcopy" <<'SH'
#!/bin/sh
# Generated by native/build.sh: llvm-objcopy subset over Apple's strip.
case "$1" in
  --version) echo "LLVM version apple-strip-S"; exit 0 ;;
  --strip-debug) [ $# -eq 2 ] || { echo "apple-strip shim: one file only" >&2; exit 2; }
                 exec xcrun strip -S "$2" ;;
  *) echo "apple-strip shim: unsupported arguments: $*" >&2; exit 2 ;;
esac
SH
  chmod +x "$dir/llvm-objcopy"
  ln -sf "$(xcrun --find llvm-nm)" "$dir/llvm-nm"
  LLVM_OBJCOPY="$dir/llvm-objcopy"
  log "no llvm-objcopy on this macOS host: using Apple strip -S (shim in ${dir#"$PKG_DIR"/})"
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

# THE OPTIMIZE MODE IS A DECISION, and it is ReleaseFast — reluctantly, and not
# for the reason anyone expects.
#
# This library is the one component in the product that parses ARBITRARY REMOTE
# BYTES: whatever a shell on the far end of a terminal socket prints, including
# whatever a program running in it was told to print. Everything else the client
# does with untrusted input goes through Kotlin or JavaScript. ReleaseFast turns
# OFF Zig's bounds, overflow, alignment and unreachable checks, so a parser bug
# reachable from those bytes is undefined behaviour rather than a panic. The zmx
# side of the product made the opposite call for the same reason
# (`scripts/build-zmx.sh`: ReleaseSafe, "the safety checks stay on, because this
# process owns a shell"), and `check-zmx-bundle.sh` refuses to ship a bundle
# built any other way.
#
# So ReleaseSafe was built and measured here rather than argued about, and it
# loses on BOTH counts.
#
#   THROUGHPUT, `:terminal-sample:benchmark --mode=parse --fixture=ansi`, the
#   two builds swapped between runs so the host's load could not favour either
#   (four pairs; full table in `apps/terminal-sample/benchmarks/` §9):
#
#     ReleaseFast  10.52  10.26  10.35  10.44  MiB/s   median 10.40
#     ReleaseSafe   7.42   8.33   7.99   8.03  MiB/s   median  8.01
#
#   A consistent ~23% loss — tight enough across pairs to be the binary and not
#   the box, on a host whose load moved between 12.9 and 18.6 during the run.
#   Not catastrophic, but not free either, and this is the component a build log
#   streams through.
#
# AND THE HARD STOP IS PATH HYGIENE. A ReleaseSafe `libghostty-vt` carries
# the panic/source-location table its safety checks need, and that table sits in
# `.rodata` with the BUILD MACHINE's absolute paths in it — which
# `check_no_abs_paths` below refuses, correctly, and which
# `llvm-objcopy --strip-debug` cannot remove because `.rodata` is not a debug
# section. Upstream's own `-Dstrip` does not reach this target: `GhosttyLibVt`
# builds the `vt`/`vt_c` modules, and `GhosttyZig.initVt` creates them without
# `.strip`, so the option only affects `GhosttyLib` and `GhosttyExe`. Measured:
# `-Doptimize=ReleaseSafe -Dstrip=true` still emits a 2.98 MB
# `libghostty-vt.so.0.1.0` containing `/home/<user>/…/zig-cache/…`, against
# 2.37 MB and nothing for ReleaseFast.
#
# Turning it on therefore needs either a change upstream (set `.strip` on the vt
# modules) or a second vendored patch — and this package deliberately keeps
# ghostty unpatched, unlike zmx. That is a decision for whoever owns the pin,
# not something to slip in beside a cutover. Recorded here so the next person
# does not rediscover it; `ST_GHOSTTY_OPTIMIZE=ReleaseSafe` reproduces the whole
# thing in one command.
#
# The C wrapper beside it is NOT in the same position: it is built with the
# mitigations in HARDEN_CFLAGS/HARDEN_LDFLAGS above, which is what a
# distribution would require of any package handling remote input.
build_ghostty() {
  local prefix="$WORK_DIR/ghostty"
  local optimize="${ST_GHOSTTY_OPTIMIZE:-ReleaseFast}"
  rm -rf "$prefix"
  log "zig build libghostty-vt ($ZIG_TARGET, cpu=$ZIG_CPU, $optimize, -j$ZIG_JOBS)"
  (cd "$UPSTREAM_DIR" && nice -n 15 "$ZIG" build \
      "-j$ZIG_JOBS" \
      --global-cache-dir "$ZIG_GLOBAL_CACHE" \
      --cache-dir "$ZIG_LOCAL_CACHE" \
      --prefix "$prefix" \
      -Demit-lib-vt=true \
      "-Doptimize=$optimize" \
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

# macOS dylibs are linked by Apple's toolchain (xcrun clang -> ld64), on a
# macOS host only: Zig's self-hosted Mach-O linker ignores
# -exported_symbols_list and would export every libghostty-vt/highway/simdutf/
# wuffs global (verified: 587 exports), and llvm-objcopy cannot localize
# Mach-O symbols. Cross builds from Linux still produce the static archive and
# the test executables, just no dylibs (manifest: no jni_library) -- so
# verifyNativeArtifacts only passes with macOS artifacts built on the Mac.
macos_dylib_supported() {
  if [[ "$host_os" != macos ]]; then
    log "macOS dylibs skipped on $HOST_KEY: Zig's Mach-O linker ignores -exported_symbols_list; build $TARGET on a macOS host"
    return 1
  fi
  command -v xcrun >/dev/null || die "xcrun (Xcode command line tools) is needed to link $TARGET dylibs"
}

# link_macos_dylib <out> <install-name> <inputs...>: Apple clang + ld64 with
# the exported-symbols list. The archive's C++ (highway, simdutf) links the
# system libc++.
link_macos_dylib() {
  local out="$1" name="$2"; shift 2
  local arch=arm64
  [[ "$TARGET" == macos-x64 ]] && arch=x86_64
  xcrun clang++ -arch "$arch" -mmacosx-version-min=13.0 -dynamiclib "$@" \
    "-Wl,-exported_symbols_list,$NATIVE_DIR/exports/$WRAPPER_NAME.exp" -Wl,-dead_strip \
    "-Wl,-install_name,@rpath/$name" -o "$out"
}

# Every symbol the shared library exports must be ours. JNI libraries: $2 =
# the exact number of NativeTerminal_* entry points, $3 = of
# NativeTerminalTestHooks_* (0 for anything that may be packaged).
verify_exports() {
  local lib="$1" want_java="${2:-}" want_hooks="${3:-0}" nm readobj syms
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
  if [[ -n "$want_java" ]]; then
    local nj
    local nh nall
    nj="$(printf '%s\n' "$syms" | grep -Ec '^_?Java_dev_supermux_terminal_NativeTerminal_' || true)"
    nh="$(printf '%s\n' "$syms" | grep -Ec '^_?Java_dev_supermux_terminal_NativeTerminalTestHooks_' || true)"
    nall="$(printf '%s\n' "$syms" | grep -Ec '^_?Java_' || true)"
    [[ "$nj" -eq "$want_java" ]] || die "$(basename "$lib") exports $nj NativeTerminal_* entry points, expected $want_java"
    [[ "$nh" -eq "$want_hooks" ]] || die "$(basename "$lib") exports $nh test hooks, expected $want_hooks"
    [[ "$nall" -eq $((nj + nh)) ]] || die "$(basename "$lib") exports unexpected Java_* symbols"
    nj="$nall"
    printf '%s\n' "$syms" | grep -Eq '^_?JNI_OnLoad$' || die "$(basename "$lib") does not export JNI_OnLoad"
    log "$(basename "$lib"): exports $n st_* + $nj Java_* + JNI_OnLoad and nothing else"
    return 0
  fi
  log "$(basename "$lib"): exports $n st_* symbols and nothing else"
}

build_wrapper() {
  [[ ${#WRAPPER_SOURCES[@]} -gt 0 ]] || { log "wrapper: no sources (ST_ABI_VERSION=$ST_ABI_VERSION)"; return 0; }
  local wdir="$WORK_DIR/wrapper" objs=() src obj
  rm -rf "$wdir"; mkdir -p "$wdir"
  local cflags=(-std=c11 -O2 -g0 -Wall -Wextra -Werror -fvisibility=hidden -DGHOSTTY_STATIC
                "${HARDEN_CFLAGS[@]}"
                "-ffile-prefix-map=$PKG_DIR/=" -I "$NATIVE_DIR/include" -I "$OUT_DIR/include")
  [[ "$TARGET" == windows-* ]] && cflags+=(-DST_BUILDING_SHARED) || cflags+=(-fPIC)
  log "wrapper: compile ${#WRAPPER_SOURCES[@]} source(s)"
  for src in "${WRAPPER_SOURCES[@]}"; do
    obj="$wdir/$(basename "${src%.c}").o"
    cc_target "$src" "$obj" "${cflags[@]}"
    # COFF objects carry a CodeView S_OBJNAME record (the absolute temp .obj
    # path) even with -g0; drop all debug sections so artifacts stay path-free.
    "$LLVM_OBJCOPY" --strip-debug "$obj"
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
        "${HARDEN_LDFLAGS[@]}" \
        "-Wl,--version-script=$NATIVE_DIR/exports/$WRAPPER_NAME.map" -Wl,--gc-sections \
        "-Wl,-soname,lib$WRAPPER_NAME.so" -o "$shared" ;;
    android-*)
      shared="$OUT_DIR/lib/lib$WRAPPER_NAME.so"
      "$NDK_BIN/clang++" "--target=$(lock "targets.$TARGET.ndk_clang_target")" -shared "${objs[@]}" "$ghostty_static" \
        -static-libstdc++ "${HARDEN_LDFLAGS[@]}" \
        "-Wl,--version-script=$NATIVE_DIR/exports/$WRAPPER_NAME.map" -Wl,--gc-sections \
        -Wl,-z,max-page-size=16384 "-Wl,-soname,lib$WRAPPER_NAME.so" -o "$shared" ;;
    macos-*)
      if macos_dylib_supported; then
        shared="$OUT_DIR/lib/lib$WRAPPER_NAME.dylib"
        link_macos_dylib "$shared" "lib$WRAPPER_NAME.dylib" "${objs[@]}" "$ghostty_static"
      fi ;;
    windows-*)
      shared="$OUT_DIR/lib/$WRAPPER_NAME.dll"
      "$ZIG" cc -target "$ZIG_TARGET" "-mcpu=$ZIG_CPU" -shared "${objs[@]}" "$ghostty_static" -lc++ \
        -lntdll -lkernel32 -o "$shared"
      # zig also writes a PDB and an import library named after the first
      # object (terminal_bridge.lib); neither is shipped.
      rm -f "${shared%.dll}.pdb" "${shared%.dll}.lib" "$OUT_DIR/lib/terminal_bridge.lib" ;;
  esac
  if [[ -n "$shared" ]]; then
    "$LLVM_OBJCOPY" --strip-debug "$shared"
    verify_exports "$shared"
  fi
  WRAPPER_SHARED="$shared"
}

# JDK headers for the JNI glue: jni.h from $JAVA_HOME (else the JDK `java`
# resolves to; macOS: /usr/libexec/java_home). jni_md.h: the host JDK's
# platform dir. Its linux/darwin variants are equivalent for every target we
# build (jint = int; jlong = long on LP64, long long on LLP64 Windows); only
# JNIEXPORT differs on Windows, where it must be dllexport -> -DJNIEXPORT.
# Android uses the NDK sysroot's own jni.h.
resolve_jni_includes() {
  JNI_CFLAGS=()
  [[ "$TARGET" == android-* ]] && return 0
  local jdk="${JAVA_HOME:-}" java md
  if [[ -z "$jdk" && "$host_os" == macos && -x /usr/libexec/java_home ]]; then
    jdk="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
  if [[ -z "$jdk" ]] && java="$(command -v java)"; then
    java="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$java")"
    jdk="$(dirname "$(dirname "$java")")"
  fi
  [[ -n "$jdk" && -f "$jdk/include/jni.h" ]] ||
    die "JDK headers (include/jni.h) not found: set JAVA_HOME to a JDK (17+)"
  for md in linux darwin win32; do
    [[ -f "$jdk/include/$md/jni_md.h" ]] && break
  done
  [[ -f "$jdk/include/$md/jni_md.h" ]] || die "no jni_md.h under $jdk/include"
  JNI_CFLAGS=(-I "$jdk/include" -I "$jdk/include/$md")
  [[ "$TARGET" == windows-* ]] && JNI_CFLAGS+=("-DJNIEXPORT=__declspec(dllexport)")
  JDK_ID="$(sed -n 's/^JAVA_VERSION="\(.*\)"/\1/p' "$jdk/release" 2>/dev/null || true) ($md jni_md.h)"
}

# lib<JNI_NAME>: terminal_jni.c + the combined static archive, hidden
# visibility, exporting only st_*/Java_*/JNI_OnLoad. Not built for iOS (the
# cinterop binding links the static archive) or wasm. With --test (JVM targets
# only) also test-lib/lib<JNI_NAME>_test.*: the same library compiled with
# -DST_JNI_TEST_HOOKS for the JVM tests (Gradle's jvmTest points
# -Dsupermux.terminal.nativeLibrary at it); never packaged.
build_jni() {
  JNI_SHARED=""; JNI_TEST_SHARED=""
  [[ -n "${WRAPPER_STATIC:-}" ]] || return 0
  case "$TARGET" in ios-*) return 0 ;; esac
  [[ "$TARGET" != macos-* ]] || macos_dylib_supported || return 0
  resolve_jni_includes
  JNI_SHARED="$(link_jni release "$OUT_DIR/lib" "$JNI_NAME")"
  verify_exports "$JNI_SHARED" "$JNI_EXPORT_COUNT" 0
  if [[ $RUN_TEST -eq 1 && "$TARGET" != android-* ]]; then
    JNI_TEST_SHARED="$(link_jni test "$OUT_DIR/test-lib" "${JNI_NAME}_test" -DST_JNI_TEST_HOOKS)"
    verify_exports "$JNI_TEST_SHARED" "$JNI_EXPORT_COUNT" "$JNI_TEST_HOOK_COUNT"
  fi
}

# link_jni <variant> <out dir> <library base name> [extra cflags...]: compile
# terminal_jni.c and link it; prints the library path.
link_jni() {
  local variant="$1" dir="$2" name="$3"; shift 3
  local obj="$WORK_DIR/wrapper/terminal_jni_$variant.o" out
  local cflags=(-std=c11 -O2 -g0 -Wall -Wextra -Werror -fvisibility=hidden -DGHOSTTY_STATIC
                "${HARDEN_CFLAGS[@]}"
                "-ffile-prefix-map=$PKG_DIR/=" -I "$NATIVE_DIR/include" "${JNI_CFLAGS[@]}" "$@")
  [[ "$TARGET" == windows-* ]] || cflags+=(-fPIC)
  mkdir -p "$dir"
  log "jni ($variant): compile terminal_jni.c${JDK_ID:+ (JDK $JDK_ID)}"
  cc_target "$JNI_SOURCE" "$obj" "${cflags[@]}"
  "$LLVM_OBJCOPY" --strip-debug "$obj"
  case "$TARGET" in
    linux-*)
      out="$dir/lib$name.so"
      "$ZIG" cc -target "$ZIG_TARGET" "-mcpu=$ZIG_CPU" -shared "$obj" "$WRAPPER_STATIC" -lc++ \
        "${HARDEN_LDFLAGS[@]}" \
        "-Wl,--version-script=$NATIVE_DIR/exports/$WRAPPER_NAME.map" -Wl,--gc-sections \
        "-Wl,-soname,lib$name.so" -o "$out" >&2 ;;
    android-*)
      out="$dir/lib$name.so"
      "$NDK_BIN/clang++" "--target=$(lock "targets.$TARGET.ndk_clang_target")" -shared "$obj" "$WRAPPER_STATIC" \
        -static-libstdc++ "${HARDEN_LDFLAGS[@]}" \
        "-Wl,--version-script=$NATIVE_DIR/exports/$WRAPPER_NAME.map" -Wl,--gc-sections \
        -Wl,-z,max-page-size=16384 "-Wl,-soname,lib$name.so" -o "$out" >&2 ;;
    macos-*)
      out="$dir/lib$name.dylib"
      link_macos_dylib "$out" "lib$name.dylib" "$obj" "$WRAPPER_STATIC" >&2 ;;
    windows-*)
      out="$dir/$name.dll"
      "$ZIG" cc -target "$ZIG_TARGET" "-mcpu=$ZIG_CPU" -shared "$obj" "$WRAPPER_STATIC" -lc++ \
        -lntdll -lkernel32 -o "$out" >&2
      rm -f "${out%.dll}.pdb" "${out%.dll}.lib" "$dir/terminal_jni_$variant.lib" ;;
  esac
  "$LLVM_OBJCOPY" --strip-debug "$out"
  printf '%s\n' "$out"
}

# Load the shared library on its own (dlopen) and drive one terminal through
# it: proves the export list is complete for a JNI-style consumer.
run_shared_load_check() {
  local lib
  for lib in "${WRAPPER_SHARED:-}" "${JNI_SHARED:-}" "${JNI_TEST_SHARED:-}"; do
    [[ -n "$lib" && "$lib" != *.dll ]] || continue
    run_one_load_check "$lib" || return 1
  done
}

run_one_load_check() {
  log "dlopen $(basename "$1") and drive a terminal through it"
  ${RUN_PREFIX[@]+"${RUN_PREFIX[@]}"} /usr/bin/env python3 - "$1" <<'PY'
import ctypes, sys
lib = ctypes.CDLL(sys.argv[1])
assert lib.st_abi_version() == 2, "abi"
h = ctypes.c_uint32(0)
lib.st_create.argtypes = [ctypes.c_uint32] * 6 + [ctypes.c_uint64, ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32)]
assert lib.st_create(2, 80, 24, 8, 16, 100, 1 << 20, None, ctypes.byref(h)) == 0 and h.value, "create"
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
  ios_skip_test_exe "bridge test" && return 0
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

  # Two-thread/many-handle stress test of the handle table (POSIX threads;
  # built for linux targets, run on a matching host).
  THREADS_EXE=""
  if [[ "$TARGET" == linux-* ]]; then
    THREADS_EXE="$OUT_DIR/bin/terminal_bridge_threads_test"
    cc_target "$NATIVE_DIR/tests/terminal_bridge_threads_test.c" "$WORK_DIR/terminal_bridge_threads_test.o" "${cflags[@]}"
    "$ZIG" cc -target "$ZIG_TARGET" "$WORK_DIR/terminal_bridge_threads_test.o" "$WRAPPER_STATIC" -lc++ -lpthread \
      -o "$THREADS_EXE"
    "$LLVM_OBJCOPY" --strip-debug "$THREADS_EXE"
  fi
}

# Sanitizer build of the bridge test (Linux host only: gcc ships ASan/UBSan
# runtimes, zig cc has UBSan but no ASan runtime). Not an artifact.
build_bridge_asan() {
  BRIDGE_ASAN_EXE=""; THREADS_TSAN_EXE=""
  [[ ${#WRAPPER_SOURCES[@]} -gt 0 && "$TARGET" == linux-* && "$host_os" == linux ]] || return 0
  [[ "$TARGET" == linux-x64 && "$host_arch" == x86_64 || "$TARGET" == linux-arm64 && "$host_arch" == aarch64 ]] || return 0
  command -v gcc >/dev/null || { log "gcc not found: sanitizer run skipped"; return 0; }
  BRIDGE_ASAN_EXE="$WORK_DIR/terminal_bridge_test_asan"
  THREADS_TSAN_EXE="$WORK_DIR/terminal_bridge_threads_test_tsan"
  log "compile bridge test with ASan + UBSan and the threads test with TSan (gcc)"
  gcc -std=c11 -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize-recover=undefined \
    -Wall -Wextra -Werror -I "$NATIVE_DIR/include" -I "$OUT_DIR/include" \
    "${WRAPPER_SOURCES[@]}" "$NATIVE_DIR/tests/terminal_bridge_test.c" "$OUT_DIR/lib/libghostty-vt.a" -lm \
    -o "$BRIDGE_ASAN_EXE" || { log "sanitizer build failed"; return 1; }
  gcc -std=c11 -O1 -g -fsanitize=thread -Wall -Wextra -Werror -I "$NATIVE_DIR/include" -I "$OUT_DIR/include" \
    "${WRAPPER_SOURCES[@]}" "$NATIVE_DIR/tests/terminal_bridge_threads_test.c" "$OUT_DIR/lib/libghostty-vt.a" \
    -lm -lpthread -o "$THREADS_TSAN_EXE" || { log "TSan build failed"; return 1; }
}

# CodecGolden.kt must be generated from the checked-in fixtures/codec/*.bin.
check_codec_golden() {
  [[ -d "$FIXTURES_DIR" ]] || return 0
  python3 "$NATIVE_DIR/scripts/gen_codec_golden.py" --check "$FIXTURES_DIR" \
    "$PKG_DIR/src/commonTest/kotlin/dev/supermux/terminal/CodecGolden.kt" ||
    die "CodecGolden.kt is out of date: run native/scripts/gen_codec_golden.py"
}

# Zig ships no iOS libc headers (stdio.h), and an iOS executable cannot run
# on the host anyway: for ios-* the C test executables are not built; the
# engine is exercised on the simulator by :terminal-core:iosSimulatorArm64Test
# through cinterop instead. (The wrapper itself uses no libc and compiles.)
ios_skip_test_exe() {
  [[ "$TARGET" == ios-* ]] || return 1
  log "$1: not built for $TARGET (no iOS libc headers in Zig; run :terminal-core:iosSimulatorArm64Test)"
}

build_smoke() {
  ios_skip_test_exe "smoke test" && return 0
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
RUN_PREFIX=()   # e.g. (/usr/bin/arch -x86_64) to run macos-x64 tests under Rosetta
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
  RUN_PREFIX=()
  TEST_HOST="$HOST_KEY $(uname -r)"
  # macos-x64 on an Apple-silicon Mac: run the x86_64 executables (and an
  # x86_64 python for the dlopen check) under Rosetta 2.
  if [[ "$TARGET" == macos-x64 && "$host_os" == macos && "$host_arch" == aarch64 ]] &&
     /usr/bin/arch -x86_64 /usr/bin/true 2>/dev/null; then
    RUN_PREFIX=(/usr/bin/arch -x86_64)
    TEST_HOST="$TEST_HOST (x86_64 under Rosetta 2)"
  elif [[ "$host_os" != "$want_os" || "$host_arch" != "$want_arch" ]]; then
    log "--test: host $HOST_KEY does not match $TARGET; artifact is NOT runtime-tested"
    TEST_RESULT="host-mismatch"; TEST_HOST=""
    return 3
  fi
  log "running smoke test on host $TEST_HOST"
  if ! ${RUN_PREFIX[@]+"${RUN_PREFIX[@]}"} "$SMOKE_EXE"; then
    TEST_RESULT="failed"
    return 1
  fi
  if [[ -n "${BRIDGE_EXE:-}" ]]; then
    log "running st_* bridge test on host $HOST_KEY"
    if ! ST_FIXTURES_DIR="$FIXTURES_DIR" ${RUN_PREFIX[@]+"${RUN_PREFIX[@]}"} "$BRIDGE_EXE"; then
      TEST_RESULT="failed"
      return 1
    fi
  fi
  if [[ -n "${THREADS_EXE:-}" ]]; then
    log "running handle-table threads test"
    "$THREADS_EXE" || { TEST_RESULT="failed"; return 1; }
  fi
  run_shared_load_check || { TEST_RESULT="failed"; return 1; }
  if [[ -n "${THREADS_TSAN_EXE:-}" ]]; then
    log "running threads test under ThreadSanitizer"
    TSAN_OPTIONS=halt_on_error=1:second_deadlock_stack=1 "$THREADS_TSAN_EXE" || { TEST_RESULT="failed"; return 1; }
  fi
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
    "optimize": "${ST_GHOSTTY_OPTIMIZE:-ReleaseFast}",
    "build_host": "$HOST_KEY",
    "android_ndk": "${NDK_ID:-}" or None,
    "strip_tool": "llvm-objcopy $LLVM_OBJCOPY_ID",
    "jni_library": "${JNI_SHARED##*/}" or None,
    "jni_headers": "${JDK_ID:-}" or ("android-ndk" if "$TARGET".startswith("android-") and "${JNI_SHARED:-}" else None),
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
build_jni
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
