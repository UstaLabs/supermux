#!/bin/sh
# scripts/build-binary.sh — build the supermux single-file binary.
#   usage: scripts/build-binary.sh <outfile> [version] [commit]
#
# This is the ONE canonical build path: developers run it locally and CI runs
# the very same script, so "works on my machine" == "works in the release".
#
# Steps, in the required order:
#   1. install the root bun deps
#   2. stage the web client: `:web:stageForBroker` compiles the Kotlin/Wasm
#      Compose app and copies the bundle + PWA shell into
#      src/channels/web/static. That needs a JDK 17+ on PATH (checked below) —
#      there is no fallback ladder anymore: the Vue/Vite PWA is gone and Gradle
#      is the only thing that can produce the bundle.
#   3. compile pty-helper for the native POSIX arch (the committed ELF is x64-only;
#      embedding it raw would break on arm64 — recompile so the right arch is
#      embedded by bun build --compile); Windows uses sessiond and skips it
#   4. fetch + verify the native frpc used by the built-in connectivity relay
#   5. generate the static manifest (turns the committed empty stub into one
#      `with { type: "file" }` import per staged file so the whole web client is
#      embedded)
#   6. bun build --compile (version/commit injected via --define)
#   7. restore the working tree (manifest stub + committed native helpers) — the
#      embedded copies now live INSIDE the binary, the tree goes back to clean.
set -eu

OUT="${1:?usage: build-binary.sh <outfile> [version] [commit]}"
VERSION="${2:-dev}"
COMMIT="${3:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TARGET="${SUPERMUX_TARGET:-}"
if [ -z "$TARGET" ]; then
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64)  TARGET=linux-x64 ;;
    Linux:aarch64|Linux:arm64) TARGET=linux-arm64 ;;
    Darwin:arm64)  TARGET=macos-arm64 ;;
    Darwin:x86_64) TARGET=macos-x64 ;;
    MINGW*:*|MSYS*:*|CYGWIN*:*|Windows_NT:*) TARGET=windows-x64 ;;
    *) echo "unsupported build target: $(uname -s) $(uname -m)" >&2; exit 1 ;;
  esac
fi
case "$TARGET" in
  linux-x64) BUN_TARGET=bun-linux-x64 ;;
  linux-arm64) BUN_TARGET=bun-linux-arm64 ;;
  macos-x64) BUN_TARGET=bun-darwin-x64 ;;
  macos-arm64) BUN_TARGET=bun-darwin-arm64 ;;
  windows-x64) BUN_TARGET=bun-windows-x64 ;;
  *) echo "unsupported SUPERMUX_TARGET '$TARGET'" >&2; exit 2 ;;
esac

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

# Plain install, not --frozen-lockfile: bun.lock IS committed (CI installs
# frozen), but a release build must not abort because a developer's tree is a
# lock refresh behind. CI is where lock drift gets caught.
bun install

# Web client build. The Kotlin/Wasm Compose app is the only web client, and only
# Gradle can build it — no vite fallback ladder to hide behind, so fail loudly
# and early if this host has no JDK instead of dying 40 lines later inside
# Gradle's own launcher.
command -v java >/dev/null || { echo "build-binary.sh: needs a JDK 17+ on PATH for :web:stageForBroker" >&2; exit 1; }
# ...and 17+ specifically: the apps/ build targets JVM 17, so an older JDK gets
# past `command -v` and then dies inside Gradle with an unreadable class-file
# error. `java -version` writes to stderr in one of two shapes — `"1.8.0_392"`
# (8 and older) or `"17.0.20"` / `"21"` (9+) — so take the major accordingly.
java_major=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\)\.\([0-9][0-9]*\).*/\1 \2/p;1s/.*version "\([0-9][0-9]*\)".*/\1/p' | awk '{ if ($1 == 1) print $2; else print $1 }')
case "$java_major" in
  ''|*[!0-9]*) echo "build-binary.sh: could not parse 'java -version' output; needs a JDK 17+ for :web:stageForBroker" >&2; exit 1 ;;
esac
[ "$java_major" -ge 17 ] || { echo "build-binary.sh: java $java_major is too old; :web:stageForBroker needs a JDK 17+" >&2; exit 1; }
( cd apps && ./gradlew :web:stageForBroker --no-daemon --console=plain )

# pty-helper: POSIX-only native-arch compile (Windows persistent terminals use sessiond).
if [ "$TARGET" != "windows-x64" ]; then
  : "${CC:=cc}"
  command -v "$CC" >/dev/null 2>&1 || CC=gcc
  "$CC" -O2 -o src/core/terminal/pty-helper src/core/terminal/pty-helper.c
fi

# frpc: fetch the native-arch helper and embed it beside the pty helper. The
# release binary's own checksum therefore covers the relay executable too.
scripts/fetch-frpc.sh "$TARGET" src/core/relay/frpc-embedded

# Embed the freshly-staged web client: rewrites the committed stub with per-file imports.
bun scripts/generate-static-manifest.ts

# Compile. --define statically replaces the build-info env reads; IS_COMPILED is
# auto-detected at runtime (entry path under /$bunfs/).
bun build --compile --minify src/cli.ts \
  --target="$BUN_TARGET" \
  --define "process.env.SUPERMUX_BUILD_VERSION=\"$VERSION\"" \
  --define "process.env.SUPERMUX_BUILD_COMMIT=\"$COMMIT\"" \
  --outfile "$OUT"

echo "built: $OUT ($VERSION $COMMIT)"
