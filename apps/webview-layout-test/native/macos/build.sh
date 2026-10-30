#!/usr/bin/env bash
# Builds the layout test's Objective-C glue, on the Mac:
#   bash apps/webview-layout-test/native/macos/build.sh
# → apps/webview-layout-test/build/native/macos-arm64/libwebview_layout_test.dylib
# Apple clang + ld64, as terminal-core's native/build.sh links its macOS dylibs. Signed ad-hoc ("-") only, never with
# a team identity. Throwaway: the macOS engine plan builds the engine's own library.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="$(cd "$HERE/../.." && pwd)"
OUT="$MODULE/build/native/macos-arm64"
LIB="$OUT/libwebview_layout_test.dylib"
JNI_ENTRY_POINTS=17 # the external funs in src/main/kotlin/dev/supermux/webview/layouttest/Native.kt

[[ "$(uname -s)" == Darwin ]] || { echo "build.sh: macOS only: run it on the Mac" >&2; exit 1; }
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
[[ -f "$JDK/include/jni.h" && -f "$JDK/include/darwin/jni_md.h" ]] ||
  { echo "build.sh: no JNI headers under $JDK/include: set JAVA_HOME to a JDK 17" >&2; exit 1; }

mkdir -p "$OUT"
xcrun clang -arch arm64 -mmacosx-version-min=13.0 -dynamiclib -fobjc-arc -fvisibility=hidden -O2 -g0 \
  -Wall -Werror -Wno-deprecated-declarations \
  -I "$JDK/include" -I "$JDK/include/darwin" \
  "$HERE/LTMain.m" "$HERE/LTPageView.m" "$HERE/LTJni.m" "$HERE/LTInput.m" "$HERE/LTProbe.m" \
  -framework Cocoa -framework WebKit -framework QuartzCore -framework Carbon \
  -Wl,-install_name,@rpath/libwebview_layout_test.dylib -o "$LIB"

codesign --force --sign - --timestamp=none "$LIB"
SIGNATURE="$(codesign -dv "$LIB" 2>&1)"
grep -q 'Signature=adhoc' <<<"$SIGNATURE" || { printf 'build.sh: not ad-hoc signed:\n%s\n' "$SIGNATURE" >&2; exit 1; }
grep -q 'TeamIdentifier=not set' <<<"$SIGNATURE" ||
  { printf 'build.sh: the dylib carries a team identity:\n%s\n' "$SIGNATURE" >&2; exit 1; }

EXPORTED="$(nm -gU "$LIB" | grep -c ' _Java_dev_supermux_webview_layouttest_Native_' || true)"
[[ "$EXPORTED" == "$JNI_ENTRY_POINTS" ]] ||
  { echo "build.sh: $EXPORTED JNI entry points exported, expected $JNI_ENTRY_POINTS" >&2; nm -gU "$LIB" >&2; exit 1; }

echo "built $LIB: $(stat -f%z "$LIB") bytes, ad-hoc signed, $EXPORTED JNI entry points"
