#!/usr/bin/env bash
# Run a command with the user's live supermux / agent state READ-ONLY (bubblewrap bind mounts):
# ~/.mux (state, worktrees, memory), ~/.claude, ~/.claude.json, ~/.codex, ~/.grok, ~/.cursor,
# ~/.config/{opencode,cursor}, ~/.local/share/opencode. Only the scratch dir ($1) is writable
# under $HOME. A write the command attempts there fails with EROFS instead of landing.
#
#   scripts/migration-dry-run/sandbox.sh <scratch-dir> <command...>
set -euo pipefail
scratch=$(realpath "$1"); shift
args=(--dev-bind / /)
for p in "$HOME/.mux" "$HOME/.claude" "$HOME/.claude.json" "$HOME/.codex" "$HOME/.grok" "$HOME/.cursor" \
         "$HOME/.config/opencode" "$HOME/.config/cursor" "$HOME/.local/share/opencode"; do
  [ -e "$p" ] && args+=(--ro-bind "$p" "$p")
done
args+=(--bind "$scratch" "$scratch")
exec bwrap "${args[@]}" -- "$@"
