#!/usr/bin/env bash
# Migrate from the single-session telegram plugin to agentmux.
# - Stops any running bun server.ts process holding the bot token.
# - Copies bot token + access.json from the old channel dir to the agentmux state dir.
# - Disables the old plugin (manual step: claude --plugins-disable telegram).
set -euo pipefail

OLD="$HOME/.claude/channels/telegram"
NEW="${AGENTMUX_STATE_DIR:-$HOME/.agentmux/state}"

mkdir -p "$NEW"
chmod 700 "$NEW"

# 1. Kill any running bun server.ts for the old plugin
if pgrep -f "bun server.ts" >/dev/null; then
  echo "→ killing old telegram MCP bun process"
  pkill -KILL -f "bun server.ts" || true
fi

# 2. Copy .env (token) if present and not already in new
if [ -f "$OLD/.env" ] && [ ! -f "$NEW/.env" ]; then
  echo "→ copying .env (token)"
  install -m 600 "$OLD/.env" "$NEW/.env"
fi

# 3. Copy access.json (allowlist) if present and not already in new
if [ -f "$OLD/access.json" ] && [ ! -f "$NEW/access.json" ]; then
  echo "→ copying access.json"
  install -m 600 "$OLD/access.json" "$NEW/access.json"
fi

echo
echo "Migration done. Next:"
echo "  1. Disable the old plugin in Claude Code (or remove it from settings)."
echo "  2. Install the agentmux-shim plugin (see README)."
echo "  3. Enable + start the broker:"
echo "       systemctl --user enable --now agentmux"
