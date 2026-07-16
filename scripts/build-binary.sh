#!/bin/sh
# scripts/build-binary.sh — build the supermux single-file binary.
#   usage: scripts/build-binary.sh <outfile> [version] [commit]
#
# This is the ONE canonical build path: developers run it locally and CI runs
# the very same script, so "works on my machine" == "works in the release".
#
# Steps, in the required order:
#   1. install deps (root + web-app)
#   2. build the PWA (vue-tsc → vite fallback ladder for hosts w/o node on PATH)
#   3. compile pty-helper for the NATIVE arch (the committed ELF is x64-only;
#      embedding it raw would break on arm64 — recompile so the right arch is
#      embedded by bun build --compile)
#   4. fetch + verify the native frpc used by the built-in connectivity relay
#   5. generate the static manifest (turns the committed empty stub into 130
#      `with { type: "file" }` imports so the whole PWA is embedded)
#   6. bun build --compile (version/commit injected via --define)
#   7. restore the working tree (manifest stub + committed native helpers) — the
#      embedded copies now live INSIDE the binary, the tree goes back to clean.
set -eu

OUT="${1:?usage: build-binary.sh <outfile> [version] [commit]}"
VERSION="${2:-dev}"
COMMIT="${3:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Restore workspace mutations unconditionally (on success, failure, or signal):
# the embedded copies live inside $OUT now; the tree goes back to its prior state.
# frpc uses an explicit backup so this also preserves an uncommitted local stub.
FRPC_BACKUP="$(mktemp)"
cp src/core/relay/frpc-embedded "$FRPC_BACKUP"
cleanup() {
  git checkout -- src/channels/web/static-manifest.generated.ts src/core/terminal/pty-helper 2>/dev/null || true
  cp "$FRPC_BACKUP" src/core/relay/frpc-embedded 2>/dev/null || true
  rm -f "$FRPC_BACKUP"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# The ROOT bun.lock is GITIGNORED in this repo (only src/web-app/bun.lock is
# committed — see .gitignore + Dockerfile), so --frozen-lockfile would abort the
# root install with "lockfile not found / out of date". Use a plain install at
# the root and keep --frozen-lockfile only for web-app, whose lock IS committed
# and must stay byte-for-byte reproducible.
bun install
( cd src/web-app && bun install --frozen-lockfile )

# PWA build. Graceful ladder: `bun run build` (runs vue-tsc + vite) is the happy
# path on CI runners that have node on PATH. On hosts WITHOUT node (vue-tsc and
# the vite node-shebang launcher both fail), fall through to invoking vite's JS
# entry through bun directly, which needs no node binary.
( cd src/web-app && bun run build ) \
  || ( cd src/web-app && ./node_modules/.bin/vite build ) \
  || ( cd src/web-app && bun node_modules/vite/bin/vite.js build )

# pty-helper: native-arch compile (overwrites the committed x64 ELF in the tree
# so the embed below picks up THIS machine's arch; restored at the end).
: "${CC:=cc}"
command -v "$CC" >/dev/null 2>&1 || CC=gcc
"$CC" -O2 -o src/core/terminal/pty-helper src/core/terminal/pty-helper.c

# frpc: fetch the native-arch helper and embed it beside the pty helper. The
# release binary's own checksum therefore covers the relay executable too.
case "$(uname -s):$(uname -m)" in
  Linux:x86_64)  FRPC_TARGET=linux-x64 ;;
  Linux:aarch64|Linux:arm64) FRPC_TARGET=linux-arm64 ;;
  Darwin:arm64)  FRPC_TARGET=macos-arm64 ;;
  Darwin:x86_64) FRPC_TARGET=macos-x64 ;;
  *) echo "unsupported frpc build target: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
scripts/fetch-frpc.sh "$FRPC_TARGET" src/core/relay/frpc-embedded

# Embed the freshly-built PWA: rewrites the committed stub with per-file imports.
bun scripts/generate-static-manifest.ts

# Compile. --define statically replaces the build-info env reads; IS_COMPILED is
# auto-detected at runtime (entry path under /$bunfs/).
bun build --compile --minify src/cli.ts \
  --define "process.env.SUPERMUX_BUILD_VERSION=\"$VERSION\"" \
  --define "process.env.SUPERMUX_BUILD_COMMIT=\"$COMMIT\"" \
  --outfile "$OUT"

echo "built: $OUT ($VERSION $COMMIT)"
