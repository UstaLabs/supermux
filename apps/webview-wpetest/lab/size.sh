#!/usr/bin/env bash
# Z1 (lab.sh size): what a bundled WPE runtime weighs, measured on the lab's installed 2.54.2 (Debian's build): the
# WPE core, then the libraries an Ubuntu 24.04 desktop lacks or has under another soname (ICU 78, libsoup 3,
# libxml2 16). Uncompressed, zstd -19, xz -6 and gzip -6 (what a jar's deflate would roughly give).
set -euo pipefail
stamp="$(date -u +%Y%m%d-%H%M%S)"
out="/lab/results/size-$stamp.txt"
arch="$(dpkg --print-architecture)"
lib="$(dirname "$(readlink -f "$(ls /usr/lib/*-linux-gnu/libWPEWebKit-2.0.so.1)")")"
tmp="$(mktemp -d)"
mkdir -p "$tmp/core" "$tmp/deps"
cp -L "$lib/libWPEWebKit-2.0.so.1" "$tmp/core/"
cp "$lib/wpe-webkit-2.0/WPEWebProcess" "$lib/wpe-webkit-2.0/WPENetworkProcess" \
   "$lib/wpe-webkit-2.0/injected-bundle/libWPEInjectedBundle.so" "$tmp/core/"
cp /usr/share/wpe-webkit-2.0/inspector.gresource "$tmp/core/"
for so in libicudata.so.78 libicuuc.so.78 libicui18n.so.78 libsoup-3.0.so.0 libxml2.so.16; do cp -L "$lib/$so" "$tmp/deps/"; done
mb() { awk -v b="$1" 'BEGIN { printf "%.1f", b / 1000000 }'; }
measure() {
  local name="$1"; shift
  local raw zst xzs gz
  raw=$(cd "$tmp" && du -cb "$@" | tail -1 | cut -f1)
  zst=$(tar -C "$tmp" -cf - "$@" | zstd -19 -T2 -q -c | wc -c)
  xzs=$(tar -C "$tmp" -cf - "$@" | xz -6 -T2 -c | wc -c)
  gz=$(tar -C "$tmp" -cf - "$@" | gzip -6 -c | wc -c)
  local verdict=INFO
  if [ "$name" = all ]; then
    verdict=PASS
    awk -v r="$raw" -v z="$zst" 'BEGIN { exit !(r > 200000000 || z > 60000000) }' && verdict=FAIL
  fi
  echo "WPE size Z1-$name $verdict host=${LAB_HOST_NAME:-unknown} arch=$arch raw_mb=$(mb "$raw") zstd19_mb=$(mb "$zst") xz6_mb=$(mb "$xzs") gzip6_mb=$(mb "$gz")" | tee -a "$out"
}
echo "WPE size Z1-version INFO host=${LAB_HOST_NAME:-unknown} wpe=$(dpkg-query -W -f='${Version}' libwpewebkit-2.0-1) glibc_needed=$(objdump -T "$tmp/core/libWPEWebKit-2.0.so.1" | grep -o 'GLIBC_2\.[0-9]*' | sort -t. -k2 -n -u | tail -1) needed=$(readelf -d "$tmp/core/libWPEWebKit-2.0.so.1" | grep -c NEEDED)" | tee -a "$out"
measure core core
measure deps deps
measure all core deps
readelf -d "$tmp/core/libWPEWebKit-2.0.so.1" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' >"/lab/results/size-$stamp-needed.txt"
rm -rf "$tmp"
