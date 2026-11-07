#!/usr/bin/env bash
# Builds libwebview-wpe.so, the Linux engine's JNI glue, against a WPE WebKit install (build-runtime.sh calls it).
#   native/linux/build.sh PREFIX OUT_DIR
set -euo pipefail
prefix="$1"
out="$2"
here="$(cd "$(dirname "$0")" && pwd)"
JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
gcc -std=gnu11 -O2 -fPIC -shared -fvisibility=hidden \
  -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -Werror=implicit-function-declaration \
  -DWV_JNI_CLASS='"dev/supermux/webview/engine/linux/WpeNative"' \
  -I"$here" -I"$JDK/include" -I"$JDK/include/linux" \
  -I"$prefix/include/wpe-webkit-2.0" -I"$prefix/include/wpe-webkit-2.0/wpe-platform" \
  $(pkg-config --cflags glib-2.0 gio-2.0 libsoup-3.0) \
  "$here"/wv_*.c \
  -o "$out/libwebview-wpe.so" \
  -L"$prefix/lib" -lWPEWebKit-2.0 $(pkg-config --libs glib-2.0 gobject-2.0 gio-2.0 libsoup-3.0) -Wl,-z,defs
echo "glue: $out/libwebview-wpe.so $(stat -c %s "$out/libwebview-wpe.so") bytes, exports: $(nm -D --defined-only "$out/libwebview-wpe.so" | awk '{print $3}' | tr '\n' ' ')"
