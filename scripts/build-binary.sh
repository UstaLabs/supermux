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
#   4. generate the static manifest (turns the committed empty stub into 130
#      `with { type: "file" }` imports so the whole PWA is embedded)
#   5. bun build --compile (version/commit injected via --define)
#   6. restore the working tree (manifest stub + committed pty-helper ELF) — the
#      embedded copies now live INSIDE the binary, the tree goes back to clean.
set -eu

OUT="${1:?usage: build-binary.sh <outfile> [version] [commit]}"
VERSION="${2:-dev}"
COMMIT="${3:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

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

# Restore workspace mutations unconditionally (on success or failure): the
# embedded copies live inside $OUT now; the tree goes back to committed state.
# Registered HERE, right before the mutating steps, so any early exit is covered.
trap 'git checkout -- src/channels/web/static-manifest.generated.ts src/core/terminal/pty-helper 2>/dev/null || true' EXIT

# pty-helper: native-arch compile (overwrites the committed x64 ELF in the tree
# so the embed below picks up THIS machine's arch; restored at the end).
: "${CC:=cc}"
command -v "$CC" >/dev/null 2>&1 || CC=gcc
"$CC" -O2 -o src/core/terminal/pty-helper src/core/terminal/pty-helper.c

# Embed the freshly-built PWA: rewrites the committed stub with per-file imports.
bun scripts/generate-static-manifest.ts

# Compile. --define statically replaces the build-info env reads; IS_COMPILED is
# auto-detected at runtime (entry path under /$bunfs/).
bun build --compile --minify src/cli.ts \
  --define "process.env.SUPERMUX_BUILD_VERSION=\"$VERSION\"" \
  --define "process.env.SUPERMUX_BUILD_COMMIT=\"$COMMIT\"" \
  --outfile "$OUT"

echo "built: $OUT ($VERSION $COMMIT)"
