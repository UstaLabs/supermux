#!/bin/sh
# scripts/stage-desktop-binaries.sh — stage the desktop host helper binaries into
# apps/desktop/resources/<target>/ so Compose Desktop's jpackage bundles them into the
# .deb / .msi / .dmg image (spec §6 / D7 / D11).
#
#   usage: scripts/stage-desktop-binaries.sh <target> [version] [commit]
#   <target> = linux-x64 | linux-arm64 | macos-x64 | macos-arm64 | windows-x64
#
# ONE canonical staging path CI and a local build share (like build-binary.sh). What lands:
#   • supermux-broker[.exe] — the compiled Bun broker. This is the SAME artifact
#     build-binary.sh compiles from src/cli.ts (boots src/main.ts on no subcommand), so the
#     shipped host runs the exact release broker.
#   • mux-sessiond.exe      — the persistent ConPTY/Job Object owner. Windows only.
#   • frpc[.exe]            — frp 0.61.1 client, for the relay provider. All targets.
#   • tmux                  — static tmux. Linux/macOS only, and now for AGENT sessions
#     only: workspace terminals moved to zmx in Plan 4, and the Claude native-terminal
#     retirement — the thing that takes tmux out of the product — has not landed.
#   • zmx, mux-zmx-helper, zmx-manifest.json
#                           — the pinned, patched workspace-terminal daemon, the framed
#     broker helper, and the manifest naming the zmx commit + patch hash they were built
#     from. Linux/macOS only (Windows persistent terminals are sessiond's). Staged FLAT
#     like every other slot; HostBinaries.kt materializes them back into the
#     bin/ + manifest.json layout the broker verifies, and hands the broker that
#     directory through MUX_ZMX_BIN_DIR. Deliberately NOT on PATH: a stray `zmx` a user
#     happens to have installed must never be what a workspace terminal runs.
#
# Binary sourcing is override-first so a headless/offline build can supply prebuilts:
#   SUPERMUX_BROKER=<path>  use this broker instead of compiling
#   SUPERMUX_SESSIOND=<path> use this sessiond instead of compiling
#   SUPERMUX_FRPC=<path>    use this frpc instead of downloading
#   SUPERMUX_TMUX=<path>    use this static tmux instead of fetching
#   SUPERMUX_ZMX_DIR=<dir>  use this prebuilt zmx bundle (bin/ + manifest.json)
#   SUPERMUX_SKIP_BROKER=1  don't compile missing broker/sessiond overrides (wiring tests)
#   SUPERMUX_SKIP_ZMX=1     don't build the zmx bundle (wiring tests only — the packaged
#                           app then has no workspace-terminal backend, and says so)
# Anything not overridden is fetched/built. tmux has no upstream static release; if it can't be
# sourced the slot is left empty (a loud warning) — the host then falls back to a system tmux on
# $PATH (preflight only warns; codex/cursor still work), matching how KCEF isn't bundled either.
set -eu

TARGET="${1:?usage: stage-desktop-binaries.sh <target> [version] [commit]}"
VERSION="${2:-dev}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMIT="${3:-$(cd "$ROOT" && git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
DEST="$ROOT/apps/desktop/resources/$TARGET"

case "$TARGET" in
  linux-x64|linux-arm64|macos-x64|macos-arm64|windows-x64) : ;;
  *) echo "unknown target '$TARGET' (want linux-x64|linux-arm64|macos-x64|macos-arm64|windows-x64)" >&2; exit 2 ;;
esac
mkdir -p "$DEST"

case "$TARGET" in
  linux-x64|linux-arm64|macos-x64|macos-arm64) EXE="" ;;
  windows-x64) EXE=".exe" ;;
esac

echo "[stage] target=$TARGET version=$VERSION commit=$COMMIT -> $DEST"

# ── broker ────────────────────────────────────────────────────────────────────────────
if [ -n "${SUPERMUX_BROKER:-}" ]; then
  echo "[stage] broker: from SUPERMUX_BROKER=$SUPERMUX_BROKER"
  cp "$SUPERMUX_BROKER" "$DEST/supermux-broker$EXE"
elif [ "${SUPERMUX_SKIP_BROKER:-}" = "1" ]; then
  echo "[stage] broker: skipped (SUPERMUX_SKIP_BROKER=1)"
else
  echo "[stage] broker: compiling via scripts/build-binary.sh"
  SUPERMUX_TARGET="$TARGET" "$ROOT/scripts/build-binary.sh" "$DEST/supermux-broker$EXE" "$VERSION" "$COMMIT"
fi
if [ -f "$DEST/supermux-broker$EXE" ]; then chmod +x "$DEST/supermux-broker$EXE" 2>/dev/null || true; fi

# ── sessiond (Windows only) ───────────────────────────────────────────────────────────
if [ "$TARGET" = "windows-x64" ]; then
  if [ -n "${SUPERMUX_SESSIOND:-}" ]; then
    echo "[stage] sessiond: from SUPERMUX_SESSIOND=$SUPERMUX_SESSIOND"
    cp "$SUPERMUX_SESSIOND" "$DEST/mux-sessiond.exe"
  elif [ "${SUPERMUX_SKIP_BROKER:-}" = "1" ]; then
    echo "[stage] sessiond: skipped (SUPERMUX_SKIP_BROKER=1)"
  else
    echo "[stage] sessiond: compiling via scripts/build-sessiond.sh"
    SUPERMUX_TARGET="$TARGET" "$ROOT/scripts/build-sessiond.sh" "$DEST/mux-sessiond.exe"
  fi
  if [ -f "$DEST/mux-sessiond.exe" ]; then chmod +x "$DEST/mux-sessiond.exe" 2>/dev/null || true; fi
else
  rm -f "$DEST/mux-sessiond.exe"
fi

# ── frpc (all targets) ────────────────────────────────────────────────────────────────
if [ -n "${SUPERMUX_FRPC:-}" ]; then
  echo "[stage] frpc: from SUPERMUX_FRPC=$SUPERMUX_FRPC"
  cp "$SUPERMUX_FRPC" "$DEST/frpc$EXE"
else
  "$ROOT/scripts/fetch-frpc.sh" "$TARGET" "$DEST/frpc$EXE"
fi
chmod +x "$DEST/frpc$EXE" 2>/dev/null || true
echo "[stage] frpc: $DEST/frpc$EXE ($(wc -c < "$DEST/frpc$EXE") bytes)"

# ── tmux (Linux/macOS only) ───────────────────────────────────────────────────────────
if [ "$TARGET" = "windows-x64" ]; then
  rm -f "$DEST/tmux" "$DEST/pty-helper" "$DEST/pty-helper.exe"
  echo "[stage] tmux/pty-helper: skipped (Windows uses sessiond)"
elif [ -n "${SUPERMUX_TMUX:-}" ]; then
  echo "[stage] tmux: from SUPERMUX_TMUX=$SUPERMUX_TMUX"
  cp "$SUPERMUX_TMUX" "$DEST/tmux"; chmod +x "$DEST/tmux" 2>/dev/null || true
else
  # No upstream static tmux release exists; a portable one must be supplied via SUPERMUX_TMUX
  # (a prebuilt static binary) or built from source. Leaving the slot empty is safe — the host
  # falls back to a system tmux on $PATH (preflight warns; codex/cursor still work).
  echo "[stage] tmux: WARNING no static tmux sourced (set SUPERMUX_TMUX=<static-tmux>); leaving slot empty" >&2
fi

# ── zmx bundle (Linux/macOS only) ─────────────────────────────────────────────────────
# The workspace-terminal backend. Windows keeps sessiond and gets nothing here.
if [ "$TARGET" = "windows-x64" ]; then
  rm -f "$DEST/zmx" "$DEST/mux-zmx-helper" "$DEST/zmx-manifest.json"
  echo "[stage] zmx: skipped (Windows workspace terminals use sessiond)"
elif [ "${SUPERMUX_SKIP_ZMX:-}" = "1" ]; then
  rm -f "$DEST/zmx" "$DEST/mux-zmx-helper" "$DEST/zmx-manifest.json"
  echo "[stage] zmx: WARNING skipped (SUPERMUX_SKIP_ZMX=1) — this app image has no workspace-terminal backend" >&2
else
  if [ -n "${SUPERMUX_ZMX_DIR:-}" ]; then
    ZMX_DIR="$SUPERMUX_ZMX_DIR"
    echo "[stage] zmx: from SUPERMUX_ZMX_DIR=$ZMX_DIR"
  else
    # Per-target output dir: build/zmx/out is the bundle a source-mode broker and the
    # integration suite exec, and a cross-built binary landing there breaks both quietly.
    ZMX_DIR="$ROOT/build/zmx/out-$TARGET"
    echo "[stage] zmx: building the pinned bundle for $TARGET"
    "$ROOT/scripts/build-zmx.sh" --target "$TARGET" --no-test --out "$ZMX_DIR"
  fi
  # Refuse anything that is not the pinned zmx + patch, before it reaches an installer.
  "$ROOT/scripts/check-zmx-bundle.sh" "$ZMX_DIR" "$TARGET"
  cp "$ZMX_DIR/bin/zmx" "$DEST/zmx"
  cp "$ZMX_DIR/bin/mux-zmx-helper" "$DEST/mux-zmx-helper"
  cp "$ZMX_DIR/manifest.json" "$DEST/zmx-manifest.json"
  chmod +x "$DEST/zmx" "$DEST/mux-zmx-helper" 2>/dev/null || true
  echo "[stage] zmx: $DEST/{zmx,mux-zmx-helper,zmx-manifest.json}"
fi

echo "[stage] done. contents of $DEST:"
ls -la "$DEST"

