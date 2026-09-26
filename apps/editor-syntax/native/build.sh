#!/usr/bin/env bash
# build.sh gen            : transform + differential-test every locked grammar  -> build/gen/<lang>/, then ctest
#          build.sh ctest    : the C ABI tests (native/tests/bridge_test.c) under ASan + UBSan
#          build.sh <target> : one native library                               -> build/natives/<target>/lib/
#          build.sh all      : gen + every target this host can build           (+ build/natives/manifest.json)
#          build.sh manifest : rewrite build/natives/manifest.json (libraries + code-only tables blobs)
# Targets (all built on the Mac; the Linux and Windows ones cross-compile with zig):
#   macos-arm64 macos-x64    libsupermux_syntax_jni.dylib (ses_* + JNI; desktop JVM)
#   linux-x64 linux-arm64    libsupermux_syntax_jni.so    (zig, glibc 2.28, zlib compiled in)
#   windows-x64              supermux_syntax_jni.dll      (zig, mingw, zlib compiled in)
#   ios-arm64                libsupermux_syntax.a         (cinterop)
#   ios-simulator-arm64      libsupermux_syntax.a
#   android-arm64            libsupermux_syntax_jni.so    (arm64-v8a)
#   android-x64              libsupermux_syntax_jni.so    (x86_64)
#   wasm32                   supermux-syntax.wasm         (zig, wasm32-wasi reactor, zlib compiled in; the web client)
#                            native/wasm/build.sh --test builds it and runs the Node tests.
# A target links tree-sitter (lib.c), the ses_* bridge and every non-excluded grammar of
# native/grammars.lock.json: the transformed parser_<lang>.c + scanner, plus blob_<lang>.c when bundled.
# Prerequisite for a target: native/fetch.sh, then `build.sh gen`.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TARGETS=(macos-arm64 macos-x64 linux-x64 linux-arm64 windows-x64 ios-arm64 ios-simulator-arm64 android-arm64 android-x64 wasm32)
gen() {
  python3 - "$HERE" <<'PY' | while IFS=$'\t' read -r lang dir; do
import json, sys, os
here = sys.argv[1]
for g in json.load(open(os.path.join(here, "native/grammars.lock.json")))["grammars"]:
    if g["tables"] != "excluded":
        for d in g["parserDirs"]:
            print("%s\t%s" % (g["lang"], os.path.join(here, "build/grammars", g["lang"], d)))
PY
    "$HERE/tools/build-grammar.sh" "$lang" "$dir" || { echo "GEN FAILED: $lang $dir" >&2; exit 1; }
  done
  echo "UBSan reports in the difftests: $(cat "$HERE"/build/gen/*/difftest.log | grep -c 'runtime error:' || true)"
  ctest
}

# The C ABI tests, host-built with ASan + UBSan (every sanitizer report is fatal), against the
# generated javascript, json and html grammars (bundled tables) of build/gen.
ctest() {
  local TS="$HERE/build/tree-sitter/lib" G="$HERE/build/gen" W="$HERE/build/ctest" o objs=()
  local SAN=(-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer -g)
  local CC=(clang -O1 -w -std=gnu11 "${SAN[@]}")
  rm -rf "$W"; mkdir -p "$W"
  c() { o="$W/$1"; shift; "${CC[@]}" "$@" -c -o "$o"; objs+=("$o"); }
  c lib.o -I"$TS/include" -I"$TS/src" "$TS/src/lib.c"
  c bridge.o -I"$TS/include" -I"$HERE/native/include" "$HERE/native/src/syntax_bridge.c"
  c loader.o -I"$HERE/native/include" "$HERE/native/src/ses_grammar.c"
  c test.o -I"$HERE/native/include" "$HERE/native/tests/bridge_test.c"
  for lang in javascript json html; do
    local src; src="$(dirname "$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['parser_c'])" "$G/$lang/$lang.plan.json")")"
    c "parser_$lang.o" -I"$src" -I"$HERE/native/include" "$G/$lang/parser_$lang.c"
    c "blob_$lang.o" "$G/$lang/blob_$lang.c"
    if [ -f "$src/scanner.c" ]; then c "scanner_$lang.o" -I"$src" "$src/scanner.c"; fi
  done
  python3 "$HERE/tools/gen-registry.py" "$W/registry.c" javascript:bundled json:bundled html:bundled
  c registry.o -I"$HERE/native/include" "$W/registry.c"
  "${CC[@]}" "${objs[@]}" -lz -o "$W/bridge_test"
  "$W/bridge_test" "$G"
}

# Every grammar a target links: "<lang as gen produced it>\t<grammar src dir>\t<bundled|code>".
grammars() {
  python3 - "$HERE" <<'PY'
import json, os, sys
here = sys.argv[1]
sys.path.insert(0, os.path.join(here, "tools"))
from sestables import LANG_FN
for g in json.load(open(os.path.join(here, "native/grammars.lock.json")))["grammars"]:
    if g["tables"] == "excluded":
        continue
    for d in g["parserDirs"]:
        src = os.path.join(here, "build/grammars", g["lang"], d)
        m = LANG_FN.search(open(os.path.join(src, "parser.c"), encoding="utf-8").read())
        lang = m.group("lang")
        if not os.path.isfile(os.path.join(here, "build/gen", lang, "parser_%s.c" % lang)):
            sys.exit("build/gen/%s is missing: run native/build.sh gen" % lang)
        print("%s\t%s\t%s" % (lang, src, "bundled" if g["tables"] == "bundled" else "code"))
PY
}

build_target() {
  local T="$1"
  local TS="$HERE/build/tree-sitter/lib" OUT="$HERE/build/natives/$T" ZLIB="$HERE/build/zlib"
  local OBJ="$OUT/obj"; rm -rf "$OUT"; mkdir -p "$OBJ" "$OUT/lib"
  local NDK="${ANDROID_NDK_HOME:-$HOME/devtools/android-sdk/ndk/26.1.10909125}"
  local NDKBIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
  local JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
  local CC=() CXX=() JI=() JNI ZSRC=0
  case "$T" in
    macos-arm64) CC=(clang -arch arm64 -mmacosx-version-min=12.0); JNI=1 ;;
    macos-x64) CC=(clang -arch x86_64 -mmacosx-version-min=12.0); JNI=1 ;;
    ios-arm64) CC=(xcrun --sdk iphoneos clang -target arm64-apple-ios15.0); JNI=0 ;;
    ios-simulator-arm64) CC=(xcrun --sdk iphonesimulator clang -target arm64-apple-ios15.0-simulator); JNI=0 ;;
    android-arm64) CC=("$NDKBIN/aarch64-linux-android26-clang"); JNI=1 ;;
    android-x64) CC=("$NDKBIN/x86_64-linux-android26-clang"); JNI=1 ;;
    linux-x64) CC=(zig cc -target x86_64-linux-gnu.2.28); CXX=(zig c++ -target x86_64-linux-gnu.2.28); JNI=1; ZSRC=1 ;;
    linux-arm64) CC=(zig cc -target aarch64-linux-gnu.2.28); CXX=(zig c++ -target aarch64-linux-gnu.2.28); JNI=1; ZSRC=1 ;;
    windows-x64) CC=(zig cc -target x86_64-windows-gnu); CXX=(zig c++ -target x86_64-windows-gnu); JNI=1; ZSRC=1 ;;
    # Single-threaded wasm32-wasi (zig bundles wasi-libc and a wasi libc++); no C++ exceptions.
    wasm32) local zv; zv="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['zig']['version'])" "$HERE/native/upstream.lock.json")"
      [ "$(zig version)" = "$zv" ] || { echo "wasm32 needs zig $zv (upstream.lock.json), found $(zig version)" >&2; exit 1; }
      CC=(zig cc -target wasm32-wasi); CXX=(zig c++ -target wasm32-wasi -fno-exceptions); JNI=0; ZSRC=1 ;;
    *) echo "unknown target $T" >&2; exit 2 ;;
  esac
  if [ ${#CXX[@]} -eq 0 ]; then CXX=("${CC[@]}"); CXX[0]="${CC[0]/%clang/clang++}"; [ "${CC[0]}" = xcrun ] && CXX=("${CC[@]}"); fi
  case "$T" in
    macos-*) JI=(-I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin") ;;
    # The Mac JDK ships only darwin's jni_md.h, and next to jni.h, where `#include "jni_md.h"` looks
    # first: pair the JDK's jni.h with the vendored linux / win32 jni_md.h in a directory of their own.
    linux-*|windows-*) local P=linux; [[ "$T" == windows-* ]] && P=win32
      mkdir -p "$OUT/jni"; cp "$JAVA_HOME/include/jni.h" "$HERE/native/jni/$P/jni_md.h" "$OUT/jni/"; JI=(-I"$OUT/jni") ;;
    *) JI=() ;;                                                        # Android: the NDK sysroot has jni.h
  esac
  local PIC=(-fPIC); [[ "$T" == windows-* || "$T" == wasm32 ]] && PIC=()   # clang rejects -fPIC for Windows targets (PE code is relocatable anyway)
  # -DNDEBUG like tree-sitter's release builds: an internal assert() must never abort the app.
  local FLAGS=(-Os ${PIC[@]+"${PIC[@]}"} -w -std=gnu11 -ffunction-sections -fdata-sections -fvisibility=hidden -DNDEBUG -DTREE_SITTER_HIDE_SYMBOLS)
  local ZI=(); [ "$ZSRC" = 1 ] && ZI=(-I"$ZLIB")
  objs=()
  cc() { local out="$OBJ/$1"; shift; "${CC[@]}" "${FLAGS[@]}" "$@" -c -o "$out"; objs+=("$out"); }
  cc lib.o -I"$TS/include" -I"$TS/src" "$TS/src/lib.c"
  cc bridge.o -I"$TS/include" -I"$HERE/native/include" "$HERE/native/src/syntax_bridge.c"
  cc loader.o ${ZI[@]+"${ZI[@]}"} -I"$HERE/native/include" "$HERE/native/src/ses_grammar.c"
  [ "$JNI" = 1 ] && cc jni.o ${JI[@]+"${JI[@]}"} -I"$HERE/native/include" "$HERE/native/src/syntax_jni.c"
  [ "$T" = wasm32 ] && cc wasm.o -I"$HERE/native/include" "$HERE/native/src/syntax_wasm.c"
  if [ "$ZSRC" = 1 ]; then  # zlib 1.3.1, statically (only inflate is reachable; gc-sections drops the rest)
    for z in adler32 crc32 inffast inflate inftrees uncompr zutil; do cc "zlib_$z.o" -I"$ZLIB" "$ZLIB/$z.c"; done
  fi
  local spec=() CXXLIB=0 lang G mode W list RN sym
  list="$(grammars)"
  while IFS=$'\t' read -r lang G mode; do
    W="$HERE/build/gen/$lang"
    cc "parser_$lang.o" -I"$G" -I"$HERE/native/include" "$W/parser_$lang.c"
    # A scanner that embeds ANOTHER grammar's scanner (vue vendors html's) would export that
    # grammar's tree_sitter_<other>_external_scanner_* too: rename those to keep one library linkable.
    RN=()
    for sym in $(grep -rhoE 'tree_sitter_[A-Za-z0-9_]+_external_scanner_(create|destroy|serialize|deserialize|scan)' \
                 --include='*.c' --include='*.cc' --include='*.h' "$G" | sort -u); do
      case "$sym" in tree_sitter_${lang}_external_scanner_*) ;; *) RN+=("-D$sym=ses_${lang}_${sym#tree_sitter_}") ;; esac
    done
    if [ -f "$G/scanner.c" ]; then cc "scanner_$lang.o" ${RN[@]+"${RN[@]}"} -I"$G" "$G/scanner.c"
    elif [ -f "$G/scanner.cc" ]; then "${CXX[@]}" -Os ${PIC[@]+"${PIC[@]}"} -w -std=c++14 -fvisibility=hidden -DNDEBUG ${RN[@]+"${RN[@]}"} -I"$G" -c "$G/scanner.cc" -o "$OBJ/scanner_$lang.o"; objs+=("$OBJ/scanner_$lang.o"); CXXLIB=1; fi
    if [ "$mode" = bundled ]; then cc "blob_$lang.o" "$W/blob_$lang.c"; spec+=("$lang:bundled"); else spec+=("$lang"); fi
  done <<< "$list"
  python3 "$HERE/tools/gen-registry.py" "$OBJ/registry.c" "${spec[@]}"
  cc registry.o -I"$HERE/native/include" "$OBJ/registry.c"
  local LIBS=(-lz); [ "$CXXLIB" = 1 ] && LIBS+=(-lc++)
  local LINK=("${CC[@]}"); [ "$CXXLIB" = 1 ] && LINK=("${CXX[@]}")
  # Export ONLY the JNI entry points and the ses_* ABI. Above all, a statically linked libc++ must
  # never be exported, or it could interpose on another library's C++ runtime in the same process.
  printf '_Java_*\n_ses_*\n' > "$OBJ/exports.txt"                                  # Mach-O
  printf '{\n  global: Java_*; ses_*;\n  local: *;\n};\n' > "$OBJ/exports.map"         # ELF version script
  # (zig's linker has no --exclude-libs; its `local: *` already hides the static libc++ it links in.)
  local ELF_EXPORTS=(-Wl,--version-script="$OBJ/exports.map"); [[ "$T" == android-* ]] && ELF_EXPORTS+=(-Wl,--exclude-libs,ALL)
  # PE: only dllexport'ed symbols are exported, i.e. the JNI functions (TREE_SITTER_HIDE_SYMBOLS and
  # sestables.py keep the grammars' tree_sitter_<lang>() from being dllexport'ed).
  case "$T" in
    macos-*) "${CC[@]}" -dynamiclib -Wl,-dead_strip -Wl,-exported_symbols_list,"$OBJ/exports.txt" "${objs[@]}" "${LIBS[@]}" \
      -o "$OUT/lib/libsupermux_syntax_jni.dylib" ;;
    ios-*) xcrun libtool -static -o "$OUT/lib/libsupermux_syntax.a" "${objs[@]}" 2>/dev/null ;;
    android-*) [ "$CXXLIB" = 1 ] && LIBS=(-lz -static-libstdc++)
      "${LINK[@]}" -shared -Wl,--gc-sections -Wl,-z,max-page-size=16384 "${ELF_EXPORTS[@]}" "${objs[@]}" "${LIBS[@]}" \
        -o "$OUT/lib/libsupermux_syntax_jni.so"
      "$NDKBIN/llvm-strip" --strip-unneeded "$OUT/lib/libsupermux_syntax_jni.so" ;;
    linux-*) "${LINK[@]}" -shared -Wl,--gc-sections "${ELF_EXPORTS[@]}" "${objs[@]}" -o "$OUT/lib/libsupermux_syntax_jni.so" ;;  # zig c++ links libc++ statically
    windows-*) "${LINK[@]}" -shared "${objs[@]}" -o "$OUT/lib/supermux_syntax_jni.dll"
      rm -f "$OUT/lib/"*.lib "$OUT/lib/"*.pdb ;;
    wasm32)
      # A reactor (no _start; _initialize runs the constructors) exporting exactly the ses_* ABI
      # (every SES_API function of the header and of syntax_wasm.c) and its memory.
      local WEXP=()
      for sym in $(sed -nE 's/^SES_API [^(]*[ *](ses_[a-z0-9_]+)\(.*/\1/p' "$HERE/native/include/supermux_syntax.h" "$HERE/native/src/syntax_wasm.c" | sort -u); do
        WEXP+=("-Wl,--export=$sym")
      done
      "${LINK[@]}" -mexec-model=reactor -Wl,--gc-sections -Wl,-z,stack-size=1048576 -Wl,--strip-all \
        "${WEXP[@]}" "${objs[@]}" -o "$OUT/lib/supermux-syntax.wasm" ;;
  esac
  check_exports "$T" "$OUT/lib"
  ls -l "$OUT/lib"
  manifest
}

# Fail unless a shared library exports exactly Java_* and ses_* (PE: Java_* only; SES_API is not
# dllexport there).
check_exports() {
  local T="$1" bad NDKBIN
  NDKBIN="${ANDROID_NDK_HOME:-$HOME/devtools/android-sdk/ndk/26.1.10909125}/toolchains/llvm/prebuilt/darwin-x86_64/bin"
  case "$T" in
    macos-*) bad="$(nm -gU "$2/libsupermux_syntax_jni.dylib" | awk '{print $3}' | grep -vE '^_(Java_|ses_)' || true)" ;;
    linux-*|android-*) bad="$("$NDKBIN/llvm-nm" -D --defined-only "$2/libsupermux_syntax_jni.so" | awk '{print $3}' | grep -vE '^(Java_|ses_)' || true)" ;;
    wasm32) bad="$(python3 "$HERE/native/wasm/wasm-info.py" exports "$2/supermux-syntax.wasm" | grep -vE '^(ses_[a-z0-9_]+|memory|_initialize)$' || true)" ;;
    windows-*) bad="$(python3 - "$2/supermux_syntax_jni.dll" <<'PY'
import struct, sys
d = open(sys.argv[1], "rb").read()
pe = struct.unpack_from("<I", d, 0x3C)[0]
nsec, optsz = struct.unpack_from("<H", d, pe + 6)[0], struct.unpack_from("<H", d, pe + 20)[0]
opt = pe + 24
dd = opt + (112 if struct.unpack_from("<H", d, opt)[0] == 0x20B else 96)
exp_rva = struct.unpack_from("<I", d, dd)[0]
secs = [struct.unpack_from("<8sIIII", d, opt + optsz + 40 * i)[1:] for i in range(nsec)]
def off(rva):
    for vs, va, rs, ra in secs:
        if va <= rva < va + max(vs, rs):
            return rva - va + ra
def cstr(rva):
    o = off(rva); return d[o:d.index(b"\0", o)].decode()
if exp_rva:
    e = off(exp_rva)
    n, names = struct.unpack_from("<I", d, e + 24)[0], struct.unpack_from("<I", d, e + 32)[0]
    for i in range(n):
        s = cstr(struct.unpack_from("<I", d, off(names) + 4 * i)[0])
        if not s.startswith("Java_"):
            print(s)
PY
)" ;;
    *) return 0 ;;
  esac
  if [ -n "$bad" ]; then echo "$T exports more than the ABI:" >&2; echo "$bad" | head -20 >&2; exit 1; fi
  echo "$T: exports ok"
}

# build/natives/manifest.json: every library present under build/natives/<target>/lib/, and the
# tables blob (build/gen/<lang>/<lang>.sesz) of every code-only grammar, which the app ships as a
# resource (editor-syntax/tables/<lang>.sesz).
manifest() {
  python3 - "$HERE" <<'PY'
import hashlib, json, os, sys
here = sys.argv[1]; root = os.path.join(here, "build/natives")
sys.path.insert(0, os.path.join(here, "tools"))
from sestables import LANG_FN
ts = json.load(open(os.path.join(here, "native/upstream.lock.json")))["tree-sitter"]["commit"]
def entry(path, **kw):
    b = open(path, "rb").read()
    e = dict(kw, sha256=hashlib.sha256(b).hexdigest(), size=len(b))
    if path.endswith(".wasm"):  # what a browser downloads (gzip -9, as a static server would serve it)
        import gzip
        e["gzip_size"] = len(gzip.compress(b, 9, mtime=0))
    return e
out = []
for t in sorted(os.listdir(root)):
    lib = os.path.join(root, t, "lib")
    if not os.path.isdir(lib):
        continue
    for f in sorted(os.listdir(lib)):
        out.append(entry(os.path.join(lib, f), target=t, file=f))
tables = []
for g in json.load(open(os.path.join(here, "native/grammars.lock.json")))["grammars"]:
    if g["tables"] != "code":
        continue
    for d in g["parserDirs"]:
        src = os.path.join(here, "build/grammars", g["lang"], d, "parser.c")
        lang = LANG_FN.search(open(src, encoding="utf-8").read()).group("lang")
        tables.append(entry(os.path.join(here, "build/gen", lang, lang + ".sesz"), lang=lang, file=lang + ".sesz"))
tables.sort(key=lambda e: e["lang"])
json.dump({"format": 2, "abi_version": 3, "tree_sitter_commit": ts, "libraries": out, "tables": tables},
          open(os.path.join(root, "manifest.json"), "w"), indent=1)
print("manifest: %d libraries, %d code-only tables blobs" % (len(out), len(tables)))
PY
}

case "${1:-}" in
  gen) gen ;;
  ctest) ctest ;;
  manifest) manifest ;;
  all) gen; for t in "${TARGETS[@]}"; do build_target "$t"; done; cat "$HERE/build/natives/manifest.json" ;;
  "") echo "usage: build.sh gen|ctest|<target>|all" >&2; exit 2 ;;
  *) build_target "$1" ;;
esac
