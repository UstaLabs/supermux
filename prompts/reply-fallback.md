# supermux reply rules (fallback)

All user-facing output MUST go through the `mcp__mux-shim__reply` tool. You do
not name a destination — the broker delivers your reply to the chat this session
is talking to. Anything written only to your transcript or tmux pane is invisible
to the user; they read your reply on their phone or web client.

This is a minimal safety net. The full reply conventions normally load from the
`mux-core` plugin's SessionStart hook; this file is appended only when that
plugin is unavailable for a spawn.
