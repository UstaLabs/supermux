#!/usr/bin/env bash
# Builds libeditorgrammars for: macos_arm64 (JVM tests, dylib + JNI), ios_arm64 + ios_simulator_arm64
# (static, cinterop), android arm64-v8a + x86_64 (shared, JNI). The source comes from the npm package,
# which ships parser.c (+ scanner.c when the grammar has one) and queries/.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"; OUT="$HERE/out"; mkdir -p "$SRC" "$OUT"
PKG=tree-sitter-json; VER=0.24.8
if [ ! -f "$SRC/$PKG/src/parser.c" ]; then
  (cd "$SRC" && curl -sL "https://registry.npmjs.org/$PKG/-/$PKG-$VER.tgz" | tar xz && mv package "$PKG")
fi
G="$SRC/$PKG/src"
CFILES=("$G/parser.c"); [ -f "$G/scanner.c" ] && CFILES+=("$G/scanner.c")
CFLAGS=(-Os -fPIC -std=c11 -I"$G" -I"$HERE/include")
JNI_INC=(-I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin")

# macOS arm64 dylib for jvmTest
mkdir -p "$OUT/macos_arm64"
clang -arch arm64 "${CFLAGS[@]}" "${JNI_INC[@]}" -dynamiclib "${CFILES[@]}" "$HERE/jni_shim.c" \
  -o "$OUT/macos_arm64/libeditorgrammars.dylib"

# iOS static libs for cinterop
for T in ios_arm64:iphoneos:arm64-apple-ios15.0 ios_simulator_arm64:iphonesimulator:arm64-apple-ios15.0-simulator; do
  IFS=: read -r DIR SDK TRIPLE <<<"$T"; mkdir -p "$OUT/$DIR" "$OUT/$DIR/obj"
  for f in "${CFILES[@]}"; do
    xcrun --sdk "$SDK" clang -target "$TRIPLE" "${CFLAGS[@]}" -c "$f" -o "$OUT/$DIR/obj/$(basename "$f" .c).o"
  done
  rm -f "$OUT/$DIR/libeditorgrammars.a"
  xcrun ar rcs "$OUT/$DIR/libeditorgrammars.a" "$OUT/$DIR"/obj/*.o
done

# Android shared libs (JNI) with the NDK the SDK already has
SDK_DIR="$(sed -n 's/^sdk.dir=//p' "$HERE/../../local.properties")"
# The Mac's Android SDK has no ndk/; an NDK lives in the older ~/devtools/android-sdk. ANDROID_NDK_HOME wins.
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK_DIR"/ndk/* "$HOME"/devtools/android-sdk/ndk/* 2>/dev/null | sort -V | tail -1 || true)}"
[ -n "$NDK" ] || { echo "no Android NDK found" >&2; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for T in arm64-v8a:aarch64-linux-android26 x86_64:x86_64-linux-android26; do
  IFS=: read -r ABI TRIPLE <<<"$T"; mkdir -p "$OUT/jniLibs/$ABI"
  "$TC/$TRIPLE-clang" "${CFLAGS[@]}" -shared "${CFILES[@]}" "$HERE/jni_shim.c" \
    -o "$OUT/jniLibs/$ABI/libeditorgrammars.so"
done
ls -l "$OUT"/*/libeditorgrammars.* "$OUT"/jniLibs/*/libeditorgrammars.so
