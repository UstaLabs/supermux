#!/usr/bin/env bash
# Builds and runs the glue's tests (test/wv_test.c) against a WPE install, where build.sh builds the glue (the build
# image, on desktop). No WebKit process starts; no JVM.
#   native/linux/test.sh PREFIX     e.g. /work/out/stage/opt/wpe
set -euo pipefail
prefix="$1"
here="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp -d)"
JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
gcc -std=gnu11 -O1 -g -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-missing-field-initializers \
  -Werror=implicit-function-declaration \
  -DWV_JNI_CLASS='"dev/supermux/webview/engine/linux/WpeNative"' \
  -I"$here" -I"$JDK/include" -I"$JDK/include/linux" \
  -I"$prefix/include/wpe-webkit-2.0" -I"$prefix/include/wpe-webkit-2.0/wpe-platform" \
  $(pkg-config --cflags glib-2.0 gio-2.0 libsoup-3.0) \
  "$here/test/wv_test.c" -o "$out/wv_test" \
  -L"$prefix/lib" -lWPEWebKit-2.0 $(pkg-config --libs glib-2.0 gobject-2.0 gio-2.0 libsoup-3.0)
LD_LIBRARY_PATH="$prefix/lib" "$out/wv_test"
