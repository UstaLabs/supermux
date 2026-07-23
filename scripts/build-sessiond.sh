#!/bin/sh
# Build the persistent Windows ConPTY owner as one Bun executable.
# usage: scripts/build-sessiond.sh <outfile>
set -eu

OUT="${1:?usage: build-sessiond.sh <outfile>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mkdir -p "$(dirname "$OUT")"
case "${SUPERMUX_TARGET:-}" in
  "") BUN_TARGET="" ;;
  linux-x64) BUN_TARGET=bun-linux-x64 ;;
  linux-arm64) BUN_TARGET=bun-linux-arm64 ;;
  macos-x64) BUN_TARGET=bun-darwin-x64 ;;
  macos-arm64) BUN_TARGET=bun-darwin-arm64 ;;
  windows-x64) BUN_TARGET=bun-windows-x64 ;;
  *) echo "unsupported SUPERMUX_TARGET '$SUPERMUX_TARGET'" >&2; exit 2 ;;
esac

if [ -n "$BUN_TARGET" ]; then
  bun build --compile --minify src/core/sessiond/main.ts --target="$BUN_TARGET" --outfile "$OUT"
else
  bun build --compile --minify src/core/sessiond/main.ts --outfile "$OUT"
fi
echo "built: $OUT"
