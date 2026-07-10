# Claude live model/effort switching (no restart) — design

**Date:** 2026-07-10
**Status:** Approved by Ahmet (via supermux chat)
**Problem:** Switching a Claude session's model or thinking effort kills the tmux
window and respawns `claude --resume` — slow, visibly disruptive, loses in-process
state (background tasks, MCP connections), and reads as "kill the session and
restart" in the clients. Codex/cursor/opencode switches feel instant by
comparison.

## Goal

Model and effort switches on a **running Claude session** apply live — no process
restart, ever. Same broker endpoints, so all clients (web, iOS, Android,
Telegram `/switchmodel`) benefit with no protocol change.

## Non-goals

- Codex/cursor/opencode switching (already acceptable; unchanged).
- Re-architecting Claude to run headless (the terminal pane is a product
  feature).
- Mid-turn immediate application (user chose: idle → instant; busy → queued
  until the turn ends).

## Verified mechanism (Claude Code 2.1.206, tested via tmux sandbox)

Claude Code's TUI has live inline commands, driven exactly the way the broker
already types consent keystrokes (`tmux send-keys` + `capture-pane`):

- `/model <full-api-id>` → confirmation line **"Set model to <DisplayName>"**.
  Applies instantly, even with conversation history. Broker model ids
  (`claude-opus-4-8`, …) are accepted directly.
- `/effort <low|medium|high|xhigh|max>` → **"Set effort level to <level>"**.
  With conversation history it may first show a confirm menu —
  **"Change effort level?"** with default selection "1. Yes" — a single Enter
  confirms.
- A typed `/effort` **releases the `--effort` launch pin** (verified; the pin
  only blocks non-interactive applications of effort).
- `Ctrl+U` clears any stray composer draft (Claude shows "Ctrl+Y to paste
  deleted text", so a human can recover their draft).
- Typed slash commands are local: they fire **no hooks and no transcript
  turn**, so the pure-reflector state machine is untouched. ESC is never sent
  (ESC interrupts a running turn).

### Known side effects (accepted)

- Typed `/model` and `/effort` also **save as the user's global default for new
  sessions** (`saved as your default for new sessions`). Mux sessions are
  immune — every broker spawn passes explicit `--model`/`--effort` — but bare
  `claude` runs in a terminal will pick up the last switched value. Accepted +
  documented; not worth racing a config-file restore.
- `CLAUDE_CODE_EFFORT_LEVEL` must never be set on spawned sessions — it blocks
  `/effort` ("overrides effort this session"). The broker uses the `--effort`
  flag today; keep it that way.

## Design

### New module: `src/core/agents/claude/live-switch.ts`

A sequencer in the style of `session-manager/post-spawn-keys.ts` (injectable
`sendKeysFn`/`capturePane` for tests):

```
applyClaudeLiveSwitch(windowId, { model?, effort? }) → { ok } | { ok:false, error }
```

1. **Pane safety check** — capture the pane; require the composer prompt (a
   `❯ ` input line) and require the absence of known dialog/menu markers
   ("Enter to confirm", "Bypass Permissions mode", "Resume from summary",
   numbered `❯ 1.` select menus). Retry briefly (~2s) if unsafe, then fail.
2. **Clear draft** — send `C-u`, then prove the composer is empty via an
   **escape-preserving capture** (`tmux capture-pane -e`): Claude renders a
   **ghost autosuggestion** (dim, SGR 2) in idle composers that a plain capture
   cannot distinguish from a real draft — it is NOT real input (C-u can't clear
   it; typing replaces it), so dim spans are stripped before the emptiness
   check. (Field bug found 2026-07-10: the original plain-capture emptiness
   check refused half the fleet's idle panes.)
3. **Type command** — `send-keys -l '/model <id>'`, then **verify the composer
   shows exactly the typed command** (ghost-stripped, whitespace-collapsed,
   wrapped lines joined; up to 4 checks ~500ms apart) **before** sending
   `Enter`. On mismatch: `C-u` cleanup + explicit failure — a garbage submit is
   structurally impossible. First check delay doubles as the autocomplete
   settle.
4. **Verify by polling** capture every 500ms up to 10s:
   - success marker seen (`Set model to` / `Set effort level to`) → done;
   - effort confirm menu (`Change effort level?`) → send `Enter` (default is
     "Yes"), keep polling;
   - timeout → send `C-u` (clear whatever half-typed state remains) → fail.
5. Model and effort changes in the same request run **sequentially** (model
   first), each individually verified.

### Broker wiring (`src/main.ts`)

- `reapplySessionAgentConfig`'s **claude branch is replaced** by the live
  switch: resolve `wid`, call `applyClaudeLiveSwitch`, broadcast
  `session_state` on success. The kill+respawn claude path is deleted (codex
  branch unchanged).
- **Failure = error, never restart** (user decision): the caller's existing
  rollback (`registry.setModel/setReasoningLevel` to olds) stays; HTTP callers
  get the error (client toast). For the **deferred** path (drained at
  idle, `main.ts` pending-reapply block): on failure roll back the registry and
  broadcast the reverted `session_state` so pills snap back; log
  `live_switch_failed`.
- **Defer semantics unchanged** (user decision): idle → apply immediately;
  mid-turn → `PendingReapply` queues and the idle transition types it. For
  claude, `applyNow` is ignored — there is no longer a "restart now, killing
  the turn" action.

### Client copy (web)

- `ModelSwitcher.vue`: drop "Changing the model restarts the agent for this
  session." for claude sessions; hide the "Change now (ends current turn)"
  button for claude (queued switches show "Will apply after this turn"
  unchanged).
- `EffortSwitcher.vue`: same treatment — it carries the identical "Changing the
  thinking level restarts the agent for this session." line and "Change now
  (ends current turn)" button.
- iOS/Android: no "restarts the agent" copy exists in the native clients
  (verified by grep); broker behavior is client-transparent there.

## Error handling summary

| Case | Behavior |
|---|---|
| Pane in dialog/menu/unknown state | retry ~2s → fail, rollback, error to client |
| No confirmation within 10s | `C-u` cleanup → fail, rollback, error |
| Older CLI without `/effort` | shows as timeout → same explicit failure |
| Session dead / window gone | fail with existing "no such window" error |
| Deferred switch fails at idle | rollback + reverted `session_state` broadcast + warn log |

## Testing

- **Unit**: live-switch sequencer with fake send/capture — happy path (model,
  effort, both), effort confirm menu, draft-clearing, dialog-marker abort,
  timeout cleanup, sequential ordering. Mirror `post-spawn-keys.test.ts`.
- **Unit**: main.ts wiring — claude switch no longer kills the window; failure
  rolls back registry; applyNow ignored for claude; codex path untouched.
- **Live verify** (before Finish): on a real spawned session, switch model and
  effort from the web UI while idle and while mid-turn (queued), confirm via
  pane capture + `session_state` frames; confirm a forced-failure case (open a
  menu in the TUI first) errors without restarting.
