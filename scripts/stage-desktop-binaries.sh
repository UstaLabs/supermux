#!/bin/sh
# scripts/stage-desktop-binaries.sh — stage the desktop host helper binaries into
# apps/desktop/resources/<target>/ so Compose Desktop's jpackage bundles them into the
# .deb / .msi image. macOS targets are also mirrored into the native SwiftUI app's
# HostResources folder (Plan 3 Task 5, spec §6 / D7 / D11).
#
#   usage: scripts/stage-desktop-binaries.sh <target> [version] [commit]
#   <target> = linux-x64 | linux-arm64 | macos-x64 | macos-arm64 | windows-x64
#
# ONE canonical staging path CI and a local build share (like build-binary.sh). What lands:
#   • supermux-broker[.exe] — the compiled Bun broker. This is the SAME artifact
#     build-binary.sh compiles from src/cli.ts (boots src/main.ts on no subcommand), so the
#     shipped host runs the exact release broker. Omitted on Windows (client-only).
#   • frpc[.exe]            — frp 0.61.1 client, for the relay provider. All targets.
#   • tmux                  — static tmux. Linux/macOS only (the broker execs bare `tmux`).
#
# Binary sourcing is override-first so a headless/offline build can supply prebuilts:
#   SUPERMUX_FRPC=<path>   use this frpc instead of downloading   (per-invocation, one target)
#   SUPERMUX_TMUX=<path>   use this static tmux instead of fetching
#   SUPERMUX_SKIP_BROKER=1 don't compile the broker (wiring/smoke builds)
# Anything not overridden is fetched/built. tmux has no upstream static release; if it can't be
# sourced the slot is left empty (a loud warning) — the host then falls back to a system tmux on
# $PATH (preflight only warns; codex/cursor still work), matching how KCEF isn't bundled either.
set -eu

TARGET="${1:?usage: stage-desktop-binaries.sh <target> [version] [commit]}"
VERSION="${2:-dev}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMIT="${3:-$(cd "$ROOT" && git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
FRP_VERSION="0.61.1"
DEST="$ROOT/apps/desktop/resources/$TARGET"

case "$TARGET" in
  linux-x64|linux-arm64|macos-x64|macos-arm64|windows-x64) : ;;
  *) echo "unknown target '$TARGET' (want linux-x64|linux-arm64|macos-x64|macos-arm64|windows-x64)" >&2; exit 2 ;;
esac
mkdir -p "$DEST"

# frp release archive + arch for this target.
case "$TARGET" in
  linux-x64)    FRP_OS=linux;   FRP_ARCH=amd64; FRP_EXT=tar.gz; EXE="" ;;
  linux-arm64)  FRP_OS=linux;   FRP_ARCH=arm64; FRP_EXT=tar.gz; EXE="" ;;
  macos-x64)    FRP_OS=darwin;  FRP_ARCH=amd64; FRP_EXT=tar.gz; EXE="" ;;
  macos-arm64)  FRP_OS=darwin;  FRP_ARCH=arm64; FRP_EXT=tar.gz; EXE="" ;;
  windows-x64)  FRP_OS=windows; FRP_ARCH=amd64; FRP_EXT=zip;    EXE=".exe" ;;
esac

echo "[stage] target=$TARGET version=$VERSION commit=$COMMIT -> $DEST"

# ── broker (omit on Windows: client-only) ─────────────────────────────────────────────
if [ "$FRP_OS" = "windows" ]; then
  echo "[stage] broker: skipped (Windows is client-only)"
elif [ "${SUPERMUX_SKIP_BROKER:-}" = "1" ]; then
  echo "[stage] broker: skipped (SUPERMUX_SKIP_BROKER=1)"
else
  echo "[stage] broker: compiling via scripts/build-binary.sh"
  "$ROOT/scripts/build-binary.sh" "$DEST/supermux-broker$EXE" "$VERSION" "$COMMIT"
fi

# ── frpc (all targets) ────────────────────────────────────────────────────────────────
if [ -n "${SUPERMUX_FRPC:-}" ]; then
  echo "[stage] frpc: from SUPERMUX_FRPC=$SUPERMUX_FRPC"
  cp "$SUPERMUX_FRPC" "$DEST/frpc$EXE"
else
  ARCHIVE="frp_${FRP_VERSION}_${FRP_OS}_${FRP_ARCH}.${FRP_EXT}"
  URL="https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}/${ARCHIVE}"
  TMP="$(mktemp -d)"
  echo "[stage] frpc: downloading $URL"
  curl -fsSL "$URL" -o "$TMP/$ARCHIVE"
  if [ "$FRP_EXT" = "zip" ]; then
    # Windows runners (Git Bash) ship bsdtar (`tar`) but not always `unzip`; both extract zip.
    if command -v unzip >/dev/null 2>&1; then unzip -q "$TMP/$ARCHIVE" -d "$TMP"; else tar -xf "$TMP/$ARCHIVE" -C "$TMP"; fi
  else
    tar -xzf "$TMP/$ARCHIVE" -C "$TMP"
  fi
  cp "$TMP/frp_${FRP_VERSION}_${FRP_OS}_${FRP_ARCH}/frpc$EXE" "$DEST/frpc$EXE"
  rm -rf "$TMP"
fi
chmod +x "$DEST/frpc$EXE" 2>/dev/null || true
echo "[stage] frpc: $DEST/frpc$EXE ($(wc -c < "$DEST/frpc$EXE") bytes)"

# ── tmux (Linux/macOS only) ───────────────────────────────────────────────────────────
if [ "$FRP_OS" = "windows" ]; then
  echo "[stage] tmux: skipped (Windows is client-only)"
elif [ -n "${SUPERMUX_TMUX:-}" ]; then
  echo "[stage] tmux: from SUPERMUX_TMUX=$SUPERMUX_TMUX"
  cp "$SUPERMUX_TMUX" "$DEST/tmux"; chmod +x "$DEST/tmux" 2>/dev/null || true
else
  # No upstream static tmux release exists; a portable one must be supplied via SUPERMUX_TMUX
  # (a prebuilt static binary) or built from source. Leaving the slot empty is safe — the host
  # falls back to a system tmux on $PATH (preflight warns; codex/cursor still work).
  echo "[stage] tmux: WARNING no static tmux sourced (set SUPERMUX_TMUX=<static-tmux>); leaving slot empty" >&2
fi

echo "[stage] done. contents of $DEST:"
ls -la "$DEST"

# The shipped macOS app is native SwiftUI, not the Compose image. Keep ONE canonical producer for
# the three helpers, then mirror the staged mac arm64 artifacts into its folder resource. They stay
# gitignored in both destinations; Xcode copies HostResources into Supermux.app/Contents/Resources.
case "$TARGET" in
  macos-arm64)
    NATIVE_DEST="$ROOT/apps/iosApp/Supermux/HostResources"
    mkdir -p "$NATIVE_DEST"
    for name in supermux-broker frpc tmux; do
      if [ -f "$DEST/$name" ]; then cp "$DEST/$name" "$NATIVE_DEST/$name"; chmod +x "$NATIVE_DEST/$name"; fi
    done
    echo "[stage] native macOS resources: $NATIVE_DEST"
    ls -la "$NATIVE_DEST"
    ;;
esac
