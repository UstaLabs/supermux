#!/bin/sh
# Fetch the pinned frpc binary for a Supermux release target and verify the
# upstream archive before extracting it.
#
#   usage: scripts/fetch-frpc.sh <target> <output>
#   target: linux-x64 | linux-arm64 | macos-x64 | macos-arm64 | windows-x64
set -eu

TARGET="${1:?usage: fetch-frpc.sh <target> <output>}"
OUT="${2:?usage: fetch-frpc.sh <target> <output>}"
FRP_VERSION="0.61.1"

case "$TARGET" in
  linux-x64)    FRP_OS=linux;   FRP_ARCH=amd64; FRP_EXT=tar.gz; EXE="" ;;
  linux-arm64)  FRP_OS=linux;   FRP_ARCH=arm64; FRP_EXT=tar.gz; EXE="" ;;
  macos-x64)    FRP_OS=darwin;  FRP_ARCH=amd64; FRP_EXT=tar.gz; EXE="" ;;
  macos-arm64)  FRP_OS=darwin;  FRP_ARCH=arm64; FRP_EXT=tar.gz; EXE="" ;;
  windows-x64)  FRP_OS=windows; FRP_ARCH=amd64; FRP_EXT=zip;    EXE=".exe" ;;
  *) echo "unknown target '$TARGET'" >&2; exit 2 ;;
esac

ARCHIVE="frp_${FRP_VERSION}_${FRP_OS}_${FRP_ARCH}.${FRP_EXT}"
BASE="https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM

echo "[frpc] downloading $BASE/$ARCHIVE"
curl -fsSL "$BASE/$ARCHIVE" -o "$TMP/$ARCHIVE"
curl -fsSL "$BASE/frp_sha256_checksums.txt" -o "$TMP/checksums.txt"

EXPECTED="$(awk -v name="$ARCHIVE" '$2 == name { print $1 }' "$TMP/checksums.txt")"
[ -n "$EXPECTED" ] || { echo "no upstream checksum for $ARCHIVE" >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL="$(sha256sum "$TMP/$ARCHIVE" | awk '{ print $1 }')"
else
  ACTUAL="$(shasum -a 256 "$TMP/$ARCHIVE" | awk '{ print $1 }')"
fi
[ "$ACTUAL" = "$EXPECTED" ] || { echo "checksum mismatch for $ARCHIVE" >&2; exit 1; }

if [ "$FRP_EXT" = "zip" ]; then
  if command -v unzip >/dev/null 2>&1; then unzip -q "$TMP/$ARCHIVE" -d "$TMP"; else tar -xf "$TMP/$ARCHIVE" -C "$TMP"; fi
else
  tar -xzf "$TMP/$ARCHIVE" -C "$TMP"
fi

mkdir -p "$(dirname "$OUT")"
cp "$TMP/frp_${FRP_VERSION}_${FRP_OS}_${FRP_ARCH}/frpc$EXE" "$OUT"
chmod +x "$OUT" 2>/dev/null || true
echo "[frpc] wrote $OUT ($(wc -c < "$OUT") bytes)"
