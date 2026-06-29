# Address tmux windows by window-id, not by name — Design

**Date:** 2026-06-29
**Status:** Approved (web chat, ipad5)
**Branch:** `mux/supermux-16`

## Problem

The broker addresses a session's tmux window in two different ways:

- **By window id** (`@N`) — robust: `sendKeysToWindowId`, `killWindowById`,
  `livePanePid`. tmux window ids are stable and unambiguous.
- **By the `session:window` name string** (`mux:<name>`) — fragile. This breaks
  when the window name contains a space (tmux's `target` parser does not match a
  spaced window name through the `session:window` form: `send-keys -t
  "mux:My Session"` returns exit 0 but delivers nothing — verified empirically),
  and is also vulnerable to name collisions and the documented `new-window`
  index-collision footgun.

Today a regular `spawn_session name:"My Session"` keeps the space in the tmux
window name (unlike the PA path, which slugifies via `normalizeName`), so its
one-time spawn "consent-Enter" keystroke silently mis-routes. More broadly, any
code path that addresses a window by name is a latent bug.

## Goal / guiding principle

**The tmux window id (`@N`) is the single source of truth for addressing a
window. No code path may depend on the window *name* for correctness** —
addressing, input delivery, liveness, or teardown.

Window names remain useful as human-readable labels in `tmux ls`. We keep
slugifying them exactly as today; they are cosmetic and nothing routes by them.

## Scope

### In scope (the only changes)

Convert every name-based tmux operation to a window-id operation:

1. `sendChannelConsentEnter` (`src/core/session-manager/post-spawn-keys.ts`) —
   `capture-pane` and `send-keys` by `@id` instead of `${session}:${name}`.
2. `claudeTmuxTarget` + `interruptClaudePane` (`src/main.ts:758`, `:780`) — make
   `@id` authoritative; drop the `mux:<name>` string fallback.
3. Kill paths — replace `killSessionWindow({ session, window: name })`
   (`src/main.ts:1582`, `:2540`, `:3080`) and the "kill stale window by name"
   loops (`src/core/session-manager/supervisor.ts:83-87`, `:166-170`) with
   `killWindowById(storedId)`.
4. Liveness — `listSessionWindows().includes(name)` (`src/main.ts:2506`) → check
   the stored `@id`.
5. `tmux_target` string field — stop reading it for routing. Keep the DB column
   (no migration) as a vestigial display hint.

### Out of scope (deliberately unchanged)

- **Window-name slugging** — `normalizeName` at `supervisor.ts:82/94/164` and the
  display-name uniquification (`ensureUnique`) stay. Window names remain unique,
  cosmetic slugs. (Decision D1.)
- **Worktree directory** — already a pure `randomUUID()` (`worktree/manager.ts:45`);
  the session name never appears in the path. Nothing to do.
- **Git branch slug** — `normalizeName` at `worktree/manager.ts:36` slugifies the
  `mux/<slug>` branch; git forbids spaces in ref names, so this is a hard
  requirement, not cosmetic. Stays.
- **`deriveName`** (`naming.ts:7`) — default display name from a folder basename;
  cosmetic, no tmux dependency. Unchanged.
- **Renaming the tmux window on display-rename** — moot (D2): names are cosmetic,
  so a display-rename leaves the tmux window alone, exactly as today.

## Decisions

- **D1 — keep slugifying window names.** They don't matter once nothing depends
  on them. No naming code changes.
- **D2 — no `rename-window` on display-rename.** Moot given D1.
- **D3 — legacy rows with null `tmux_window_id`** (pre-migration-014, or a spawn
  where the id capture failed): on first touch, *discover* the id once via a
  `list-windows` name match, persist it (`setTmuxWindowId`), then address by id
  forever. The name is used only to find the id, never to route. Window names are
  unique (D1), so the match is unambiguous. If no match is found, log and treat
  as gone (best-effort), never fall back to name-string send-keys.

## Detailed change set

### `tmux.ts` — helpers

`sendKeysToWindowId`, `killWindowById`, `livePanePid` already exist and address
by id. Add:

- `capturePaneById(windowId): Promise<string>` — `capture-pane -t <windowId> -p
  -S -150` (moves the scrollback capture used by consent-enter onto id form).
- `resolveWindowIdByName(session, name): Promise<string | null>` — `list-windows
  -t <session> -F '#{window_id} #{window_name}'`, return the `@id` whose name
  equals `name`, else null. (Used only by the D3 healer.)

`killSessionWindow`, `listSessionWindows`, `sendKeys` (target-string forms) may
remain exported for now but must no longer be called for per-session addressing.

### `post-spawn-keys.ts` — `sendChannelConsentEnter`

Change the signature from `(tmuxTarget: string, opts)` to `(windowId: string,
opts)`. Internally use `capturePaneById(windowId)` and send via a
`sendKeysFn(windowId, keys)` that defaults to `sendKeysToWindowId`. The existing
injectable `sendKeysFn` / `capturePane` test seams stay (signatures now take a
window id). All callers already have `tmuxWindow.windowId` in hand at the call
site (spawn-helper, supervisor, main resume/onRegister).

### `main.ts`

- `claudeTmuxTarget(session)`: return `session.tmux_window_id` when present; when
  absent, run the D3 healer (`resolveWindowIdByName` → `setTmuxWindowId`) and
  return the resolved id; if still none, signal "no target" so callers no-op +
  log (no `mux:<name>` return).
- `interruptClaudePane`: always `sendKeysToWindowId(id, ["Escape"])`; if no id
  after healing, log and return.
- Kill sites (`:1582`, `:2540`, `:3080`): `killWindowById(id)` (heal first if
  needed); drop the `killSessionWindow({ window: name })` else-branches.
- Liveness (`:2506`): `stillAlive` checks the stored id via `livePanePid(id)` (or
  presence in `list-windows -F '#{window_id}'`), not `includes(name)`.
- Consent-enter callers (`:1677`, `:1769`): pass the freshly-captured
  `windowId`.

### `supervisor.ts`

- Stale-window cleanup loops (`:83-87`, `:166-170`): instead of
  `while (windows.includes(slug)) killSessionWindow({ window: slug })`, kill the
  prior window by its stored id when known (`pa.tmux_window_id` →
  `killWindowById`); the slug-based listing is no longer the cleanup key.
- Consent-enter call (`:121`): pass `tmuxWindow.windowId`.

### `spawn-helper.ts`

- Consent-enter calls (`:375`, `:408`): pass `tmuxWindow.windowId`. Window naming
  (`ensureUnique` + slug) unchanged.

## Testing strategy

TDD, one module at a time. Tests assert the *mechanism* (id-based addressing),
which the existing injectable seams make observable.

- **post-spawn-keys.test.ts** — assert `sendChannelConsentEnter` calls
  `sendKeysFn` / `capturePane` with the **window id** (`@7`), not a
  `session:name` string. Reuse the existing fake-pane harness.
- **tmux.ts** — unit test `resolveWindowIdByName` (name→id match, no-match →
  null) and `capturePaneById` (target arg is the id) via the injected runner.
- **main.ts addressing** — extract/curry the pure pieces where practical
  (`claudeTmuxTarget` is already a free function) and unit-test: id present → id;
  id absent + healer finds it → heals + returns id; id absent + no match → "no
  target". Kill/liveness covered by targeted tests on the helpers they call.
- **supervisor.ts** — test that respawn kills the prior window by id (spy on
  `killWindowById`) and never calls `killSessionWindow` by name.
- Full suite: `bun test` green; `tsc --noEmit` clean for touched files.

## Risks & rollback

- **Risk:** a spawn path that doesn't capture a window id would, post-change,
  have no addressable target. *Mitigation:* every claude spawn already calls
  `setTmuxWindowId` (verified: spawn-helper, supervisor, resume, onRegister); the
  D3 healer covers legacy/edge rows.
- **Risk:** removing name-string fallbacks could regress a path we didn't map.
  *Mitigation:* the grep map in this spec enumerates every `sendKeys(`,
  `killSessionWindow`, `${TMUX_SESSION}:`, and `includes(name)` site; each is
  addressed or explicitly out of scope.
- **Rollback:** isolated to this branch; revert the commits. No schema migration,
  so no data rollback needed (the `tmux_window_id` column already exists via
  migration 014).

## Out of scope / possible follow-ups

- Optionally slugify the window name in `spawnClaudeSession` for consistency with
  the PA path. Not required (id-addressed), so deferred.
- Optionally drop the now-vestigial `tmux_target` column in a later migration.

## Implementation amendments (2026-06-29, post-review)

Three refinements surfaced during implementation/review and are now in the code:

1. **Pre-registration liveness (`live-window.ts`).** The spawn post-check
   (`waitForRegisteredSession`) polls liveness *before* the session is registered,
   so a registry-only window-id lookup is null during that window and would throw
   "spawn failed" on every claude spawn. Fixed with `liveWindowId(sessionId,
   getRegistered, getPending)` which reads the registry OR the `pendingTmuxWindowId`
   map (where the id lives until `onRegister` drains it). Unit-tested.

2. **D3 heal slug-fallback.** D3 assumed the lookup name equals the window name —
   false for PA windows, which are named with `normalizeName(name)` (the slug)
   while the registry stores the free-form display name. `ensureWindowId` now tries
   the display name, then the slug, so legacy/null-id PA rows heal correctly
   instead of returning null (which would 404 the agent terminal). Unit-tested.

3. **`getSessionTmuxTarget` heal-on-read (Task 8).** Made async and routed through
   the heal so the web agent-terminal attach never falls back to a name string and
   legacy rows don't 404. (`agent-tmux.ts` already accepts window-id targets.)

4. **Dead name-target primitives removed.** `sendKeys(target)` and
   `killSessionWindow({session,window})` had zero runtime callers after the
   refactor and were the exact string/name-target helpers that could reintroduce
   the spaced-name bug, so they were deleted from `tmux.ts` (`listSessionWindows`
   stays — still used for cosmetic window-name uniquification).
