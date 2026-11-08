#!/usr/bin/env bash
# Builds webview-compose's WPE runtime in the webview-wpe-build image, with /work the host's ~/work/wpe-runtime:
#   runtime/ (these scripts), glue/ (native/linux), src/, build/, ccache/, avif/, out/.
#   build-runtime.sh all    fetch + check + patch + configure + build + install + collect + libavif + glue + manifest + pack
#   build-runtime.sh glue   rebuild only the glue into the collected runtime, then the manifest and the pack
# Resumable: the build tree and the ccache persist in /work; run it again after a crash.
set -euo pipefail
V=2.54.2
SHA=f3284e19e47fcb5ac8c63c2e1c113dbd65b2423eb8a545f5b345707829004f56
# Ubuntu 24.04's libavif: the same tarball as its libavif_1.0.4.orig.tar.gz.
AVIF_V=1.0.4
AVIF_SHA=dc56708c83a4b934a8af2b78f67f866ba2fb568605c7cf94312acf51ee57d146
ARCH=x64
NAME="wpe-runtime-$V-linux-$ARCH"
W=/work
P="$W/out/stage/opt/wpe"
R="$W/out/$NAME"
export CCACHE_DIR="$W/ccache"

fetch_and_configure() {
  if [ ! -d "$W/src/wpewebkit-$V" ]; then
    mkdir -p "$W/src"
    cd "$W/src"
    curl -fsSLO "https://wpewebkit.org/releases/wpewebkit-$V.tar.xz"
    echo "$SHA  wpewebkit-$V.tar.xz" | sha256sum -c -
    tar xf "wpewebkit-$V.tar.xz"
    patch -d "wpewebkit-$V" -p1 <"$W/runtime/relocatable-exec-path.patch"
  fi
  if [ ! -f "$W/build/build.ninja" ]; then
    cmake -S "$W/src/wpewebkit-$V" -B "$W/build" -G Ninja \
      -DPORT=WPE -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX=/opt/wpe -DCMAKE_INSTALL_LIBDIR=lib -DCMAKE_INSTALL_LIBEXECDIR=libexec \
      -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
      -DENABLE_DOCUMENTATION=OFF -DENABLE_INTROSPECTION=OFF -DENABLE_JOURNALD_LOG=OFF \
      -DENABLE_WPE_PLATFORM=ON -DENABLE_WPE_PLATFORM_HEADLESS=ON -DENABLE_WPE_PLATFORM_DRM=OFF \
      -DENABLE_WPE_PLATFORM_WAYLAND=OFF -DENABLE_WPE_LEGACY_API=OFF -DENABLE_WPE_QT_API=OFF \
      -DUSE_ATK=OFF -DUSE_FLITE=OFF -DENABLE_SPEECH_SYNTHESIS=OFF -DUSE_LIBBACKTRACE=OFF -DUSE_LIBHYPHEN=OFF \
      -DENABLE_SPELLCHECK=OFF -DUSE_JPEGXL=OFF -DENABLE_WEBDRIVER=OFF -DENABLE_BUBBLEWRAP_SANDBOX=ON
  fi
}

build_and_install() {
  nice -n 10 ninja -C "$W/build" -j "${JOBS:-4}"
  rm -rf "$W/out/stage"
  DESTDIR="$W/out/stage" ninja -C "$W/build" install >/dev/null
}

collect() {
  rm -rf "$R"
  mkdir -p "$R/lib/wpe-webkit-2.0/injected-bundle" "$R/libexec" "$R/share"
  cp -a "$P"/lib/libWPEWebKit-2.0.so* "$R/lib/"
  cp -a "$P/libexec/wpe-webkit-2.0/WPEWebProcess" "$P/libexec/wpe-webkit-2.0/WPENetworkProcess" "$R/libexec/"
  cp -a "$P/lib/wpe-webkit-2.0/injected-bundle/libWPEInjectedBundle.so" "$R/lib/wpe-webkit-2.0/injected-bundle/"
  [ -d "$P/share/wpe-webkit-2.0" ] && cp -a "$P/share/wpe-webkit-2.0" "$R/share/"
}

# WebKit's libavif, with its decoder only. Ubuntu's libavif16 also links three AV1 encoders (aom, rav1e and SVT-AV1,
# 16 MB of the runtime) and a second decoder, libgav1, with its abseil libraries. WebKit only decodes, and libavif
# decodes with dav1d whenever it has it. So this is Ubuntu's libavif built again, from the same source with the same
# flags, with dav1d its only codec; deps() then bundles Ubuntu's dav1d and libyuv for it, and none of the rest.
avif() {
  local src="$W/src/libavif-$AVIF_V" lib="$W/avif/lib/libavif.so.16"
  if [ ! -f "$lib" ]; then
    if [ ! -d "$src" ]; then
      mkdir -p "$W/src"
      curl -fsSL -o "$src.tar.gz" "https://github.com/AOMediaCodec/libavif/archive/refs/tags/v$AVIF_V.tar.gz"
      echo "$AVIF_SHA  $src.tar.gz" | sha256sum -c -
      tar -C "$W/src" -xzf "$src.tar.gz"
    fi
    rm -rf "$W/avif-build" "$W/avif"
    # Ubuntu's flags (its debian/rules: hardening=+all, build type None), from the source's root as a package build
    # runs, in a subshell: the glue keeps its own.
    (
      cd "$src"
      eval "$(DEB_BUILD_MAINT_OPTIONS=hardening=+all dpkg-buildflags --export=sh)"
      cmake -S "$src" -B "$W/avif-build" -G Ninja -DCMAKE_BUILD_TYPE=None -DCMAKE_INSTALL_PREFIX="$W/avif" \
        -DCMAKE_INSTALL_LIBDIR=lib -DAVIF_CODEC_DAV1D=ON -DAVIF_CODEC_AOM=OFF -DAVIF_CODEC_LIBGAV1=OFF \
        -DAVIF_CODEC_RAV1E=OFF -DAVIF_CODEC_SVT=OFF
      ninja -C "$W/avif-build" install
    )
  fi
  # WebKit was linked against Ubuntu's libavif.so.16: this one must export every symbol that one does.
  local missing
  missing=$(comm -23 <(nm -D --defined-only /usr/lib/x86_64-linux-gnu/libavif.so.16 | awk '{print $3}' | sort -u) \
                     <(nm -D --defined-only "$lib" | awk '{print $3}' | sort -u))
  if [ -n "$missing" ]; then
    echo "avif: exported by Ubuntu's libavif.so.16, not by this one: $missing" >&2
    exit 5
  fi
  cp -L "$lib" "$R/lib/libavif.so.16"
  echo "avif: libavif $AVIF_V, needs $(objdump -p "$lib" | awk '/NEEDED/{printf "%s ", $2}')"
}

glue() {
  bash "$W/glue/build.sh" "$P" "$R/lib"
}

# Every library the runtime's ELF files need that isn't in the system base, copied into lib/, until nothing new is
# needed. The base: glibc, libstdc++, GLib, zlib, expat, fontconfig/freetype, X11, the GL driver stack, GStreamer, and
# systemd's libraries; a desktop has all of them under stable sonames.
BASE='^(linux-vdso|ld-linux|libc\.so|libm\.so|libdl\.so|libpthread\.so|librt\.so|libresolv\.so|libstdc\+\+|libgcc_s|libglib-2\.0|libgobject-2\.0|libgio-2\.0|libgmodule-2\.0|libgthread-2\.0|libz\.so|libexpat|libfontconfig|libfreetype|libEGL|libGL|libGLX|libGLdispatch|libGLESv2|libgbm|libdrm|libgst|liborc|libsystemd|libudev|libseccomp|libmount|libblkid|libselinux|libffi|libpcre2|libX|libxcb|libcap|libgcrypt|libgpg-error|liblzma|libzstd|liblz4|libbz2)'
deps() {
  local round new
  for round in 1 2 3 4 5 6; do
    new=0
    while read -r name path; do
      echo "$name" | grep -Eq "$BASE" && continue
      if [ ! -e "$R/lib/$name" ]; then
        cp -L "$path" "$R/lib/$name"
        new=$((new + 1))
      fi
    done < <(for f in "$R"/lib/*.so* "$R"/libexec/* "$R"/lib/wpe-webkit-2.0/injected-bundle/*.so; do
               LD_LIBRARY_PATH="$R/lib" ldd "$f" 2>/dev/null | awk '/=> \//{print $1" "$3}'
             done | sort -u)
    echo "deps: round $round, $new new"
    [ "$new" -eq 0 ] && break
  done
  if LD_LIBRARY_PATH="$R/lib" ldd "$R"/lib/*.so* "$R"/libexec/* 2>/dev/null | grep -q 'not found'; then
    echo "deps: unresolved libraries:" >&2
    LD_LIBRARY_PATH="$R/lib" ldd "$R"/lib/*.so* "$R"/libexec/* | grep 'not found' | sort -u >&2
    exit 4
  fi
}

# RPATH, not RUNPATH: the dynamic linker searches DT_RPATH before LD_LIBRARY_PATH and DT_RUNPATH after it, so a user's
# LD_LIBRARY_PATH can't put another version of a bundled library in the runtime's place.
relocate() {
  local f
  for f in "$R"/lib/*.so*; do [ -L "$f" ] || { patchelf --force-rpath --set-rpath '$ORIGIN' "$f"; strip --strip-unneeded "$f"; }; done
  for f in "$R"/libexec/*; do patchelf --force-rpath --set-rpath '$ORIGIN/../lib' "$f"; strip --strip-unneeded "$f"; done
  f="$R/lib/wpe-webkit-2.0/injected-bundle/libWPEInjectedBundle.so"
  patchelf --force-rpath --set-rpath '$ORIGIN/../..' "$f"
  strip --strip-unneeded "$f"
}

manifest() {
  local glibc
  glibc=$(find "$R" -type f \( -name '*.so*' -o -path '*/libexec/*' \) -exec objdump -T {} + 2>/dev/null \
    | grep -o 'GLIBC_2\.[0-9]*' | sort -t. -k2 -n -u | tail -1)
  {
    echo "# webview-compose WPE runtime: read by WpeRuntime (engine/linux/WpeRuntime.kt)"
    echo "runtime=wpe"
    echo "version=$V"
    echo "abi=2"
    echo "arch=$ARCH"
    echo "glibc=$glibc"
    (cd "$R" && find . -type f ! -name manifest.properties | sort | while read -r f; do
      echo "file.${f#./}=$(stat -c %s "$f") $(sha256sum "$f" | cut -d' ' -f1)"
    done)
  } >"$R/manifest.properties"
}

# The runtime's archives: .tar.zst to ship, and .tar.gz, which the engine unpacks by itself (WpeExtractor) when an app
# ships the archive instead of the folder.
pack() {
  local raw zst
  tar -C "$W/out" -cf - "$NAME" | zstd -19 -T4 -q -f -o "$W/out/$NAME.tar.zst"
  tar -C "$W/out" -cf - "$NAME" | gzip -9 >"$W/out/$NAME.tar.gz"
  raw=$(du -sb "$R" | cut -f1)
  zst=$(stat -c %s "$W/out/$NAME.tar.zst")
  echo "RUNTIME $NAME raw_mb=$(awk -v b="$raw" 'BEGIN{printf "%.1f", b/1e6}') zstd19_mb=$(awk -v b="$zst" 'BEGIN{printf "%.1f", b/1e6}') files=$(grep -c '^file\.' "$R/manifest.properties") $(grep '^glibc=' "$R/manifest.properties")"
}

case "${1:-all}" in
  all) fetch_and_configure; build_and_install; collect; avif; glue; deps; relocate; manifest; pack ;;
  glue) glue; deps; relocate; manifest; pack ;;
  *) echo "usage: build-runtime.sh all|glue" >&2; exit 2 ;;
esac
