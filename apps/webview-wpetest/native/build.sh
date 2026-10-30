#!/usr/bin/env bash
# Builds the lab's JNI library inside the lab image (lab.sh native): the library's glue (apps/webview-compose/
# native/linux, pushed to /lab/native-glue) with the lab's class name, plus the upload benchmark. Exports JNI_OnLoad only.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
GLUE=/lab/native-glue
JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
gcc -std=gnu11 -O2 -g -fPIC -shared -fvisibility=hidden \
  -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -Werror=implicit-function-declaration \
  -DWV_JNI_CLASS='"dev/supermux/webview/wpetest/WpeNative"' -DWV_GLBENCH \
  -I"$GLUE" -I"$JDK/include" -I"$JDK/include/linux" \
  $(pkg-config --cflags wpe-webkit-2.0 wpe-platform-2.0) \
  "$GLUE"/wv_*.c wv_glbench.c \
  -o out/libwvlab.so \
  $(pkg-config --libs wpe-webkit-2.0 wpe-platform-2.0) -lEGL -lGLESv2 -Wl,-z,defs
echo "build: out/libwvlab.so $(stat -c %s out/libwvlab.so) bytes, exports: $(nm -D --defined-only out/libwvlab.so | awk '{print $3}' | tr '\n' ' ')"
