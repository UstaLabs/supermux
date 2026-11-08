#!/usr/bin/env bash
# Builds the macOS engine's glue, on a Mac (Gradle's buildMacGlue runs it):
#   bash apps/webview-compose/native/macos/build.sh <out dir>
# → <out dir>/libwebview_macos.dylib: universal (arm64, x86_64), macOS 14+, ad-hoc signed. It compiles whichever of the
# glue's files exist. It refuses a signature that isn't ad hoc or carries a team identity, and a library whose exported
# JNI entry points differ from the external funs of MacGlue.kt. Never sign it with a team.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="$(cd "$HERE/../.." && pwd)"
OUT="${1:?usage: build.sh <out dir>}"
LIB="$OUT/libwebview_macos.dylib"
KOTLIN="$MODULE/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/MacGlue.kt"
PREFIX=Java_dev_supermux_webview_engine_mac_MacGlue_

[[ "$(uname -s)" == Darwin ]] || { echo "build.sh: macOS only: run it on the Mac" >&2; exit 1; }
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
[[ -f "$JDK/include/jni.h" && -f "$JDK/include/darwin/jni_md.h" ]] ||
  { echo "build.sh: no JNI headers under $JDK/include: set JAVA_HOME to a JDK 17" >&2; exit 1; }

SOURCES=()
for name in WVMain WVJson WVPageView WVInput WVPage WVFrameSync WVTesting; do
  if [[ -f "$HERE/$name.m" ]]; then SOURCES+=("$HERE/$name.m"); fi
done

mkdir -p "$OUT"
rm -f "$LIB.part"
xcrun clang -arch arm64 -arch x86_64 -mmacosx-version-min=14.0 -dynamiclib -fobjc-arc -fvisibility=hidden -O2 -g0 \
  -Wall -Werror -Wno-deprecated-declarations \
  -I "$JDK/include" -I "$JDK/include/darwin" \
  "${SOURCES[@]}" \
  -framework Cocoa -framework WebKit -framework QuartzCore -framework Carbon \
  -Wl,-install_name,@rpath/libwebview_macos.dylib -o "$LIB.part"

codesign --force --sign - --timestamp=none "$LIB.part"
SIGNATURE="$(codesign -dv "$LIB.part" 2>&1)"
grep -q 'Signature=adhoc' <<<"$SIGNATURE" || { printf 'build.sh: not ad-hoc signed:\n%s\n' "$SIGNATURE" >&2; exit 1; }
grep -q 'TeamIdentifier=not set' <<<"$SIGNATURE" ||
  { printf 'build.sh: the dylib carries a team identity:\n%s\n' "$SIGNATURE" >&2; exit 1; }
for arch in arm64 x86_64; do
  lipo "$LIB.part" -verify_arch "$arch" || { echo "build.sh: no $arch slice" >&2; exit 1; }
done

DECLARED="$(grep -oE 'external fun [A-Za-z0-9_]+' "$KOTLIN" | awk '{print $3}' | sort -u)"
EXPORTED="$(nm -gU -arch arm64 "$LIB.part" | awk '{print $3}' | sed -n "s/^_$PREFIX//p" | sort -u)"
if [[ "$DECLARED" != "$EXPORTED" ]]; then
  echo "build.sh: MacGlue.kt's externals (<) and the dylib's entry points (>) differ:" >&2
  diff <(echo "$DECLARED") <(echo "$EXPORTED") >&2 || true
  exit 1
fi

mv -f "$LIB.part" "$LIB"
echo "built $LIB: $(stat -f%z "$LIB") bytes, universal, ad-hoc signed, $(wc -l <<<"$EXPORTED" | tr -d ' ') JNI entry points"
