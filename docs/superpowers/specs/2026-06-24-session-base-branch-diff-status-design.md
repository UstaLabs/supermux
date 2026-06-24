# Session ⇄ Base-Branch Diff Status — Design (2026-06-24)

## Goal

Surface, **at a glance for every session in the list**, how that session's working
state diverges from the branch it should integrate into — *in sync / N ahead / N
behind / N uncommitted* — without the user opening the Finish sheet.

Today this comparison exists **only** inside the Finish flow (`computeReadiness`,
computed on-demand when the sheet opens) and is surfaced nowhere at a glance. A
separate header pill (`BranchSyncStatus`) compares against the **remote**, not the
base branch.

## Context (current state)

There are two on-demand, non-cached, non-broadcast git-status paths today:

1. **Worktree vs. base branch** — `computeReadiness()`
   (`src/core/worktree/readiness.ts`), the only thing comparing the worktree to its
   base branch. Fired by exactly one trigger: `GET
   /sessions/{id}/finish/readiness`, called by `FinishSheet.vue` on open. Shells out
   to ~8–10 **synchronous** (`execFileSync`) git subprocesses.
2. **Branch vs. remote upstream** — `remoteStatus()` (`src/core/git/remote.ts`) →
   `GET /sessions/{id}/git/status`, rendered by `BranchSyncStatus.vue`. Its `↑/↓` is
   vs `origin/<branch>`, fetched `onMounted` + on session/workdir change.

Neither uses a filesystem watcher, polling, caching, or WS broadcast. Session
records already persist `repo_root` / `base_branch` / `session_branch` (migration
`015_worktree_session.sql`), set once at spawn.

Measured host ceilings (relevant to the trigger choice): `max_user_watches=65536`,
`max_user_instances=128`; **7,658** worktree dirs on disk but only **~19** live git
worktrees; a populated checkout ≈ **240 source dirs**. Recursive working-tree
watching is therefore fragile (ENOSPC) and noisy (build/test event storms); a
targeted `.git`-metadata watcher costs ~6 descriptors/session (~114 total).

## Decisions (and why)

- **One adaptive indicator, two modes** (folds the new base-axis work into the
  existing remote path instead of adding a parallel system):
  - **`mode:"base"`** — worktree-backed session (`repo_root && base_branch &&
    session_branch` present): ahead/behind vs **`base_branch`**. *Never* vs origin.
  - **`mode:"remote"`** — plain git session (workdir is a repo, not worktree-backed):
    ahead/behind vs **`@{upstream}`** (local vs origin), the existing
    `remoteStatus()` behavior.
  - **No indicator** — workdir is not a git repo.

  Same payload, same WS frame, same data — only the label differs
  ("vs main" / "vs origin"). *"Main source branch" = the session's recorded
  `base_branch`* (whatever it was branched from — usually `main`/`dev`), not
  necessarily literally `main`. The same `GitLiteStatus` drives **two surfaces**:
  the **session-list badge** (primary) and the **chat-header pill**.

- **Tiered computation.** The list badge needs only the cheap signals → **2 async
  git calls**: ahead/behind (`git rev-list --count --left-right <ref>...HEAD`) +
  dirty count (`git status --porcelain`, which already respects `.gitignore`). The
  expensive parts (`merge-tree` conflict preflight, `gh` checks, `diff --numstat`)
  **stay on-demand in `computeReadiness` for the Finish sheet — unchanged.** No
  conflict preflight in the badge.

- **Async, never sync, single-flight.** `computeReadiness` is `execFileSync` (blocks
  the event loop). The lite path uses async `execFile` + **single-flight** (one
  in-flight recompute per session, events coalesced) so list-wide recompute can't
  stall the broker. Stale-while-revalidate (serve last cached during recompute).

- **Triggers (v1): `.git`-metadata watcher + turn-end + on-open. No working-tree
  watching.**
  - **`.git`-metadata watcher** — one shared inotify instance over live sessions'
    git-metadata paths. Catches commits, branch switches, and — crucially —
    **base-branch movement** (another session merges into `main` → dependents go +1
    behind), which a turn-end-only trigger misses. ~0 idle CPU.
  - **Turn-end** — recompute a session when its agent goes idle
    (`AgentStateStore` emits `"change"` with `phase === "idle"`, driven by the `Stop`
    hook event — `src/core/session-manager/agent-state-store.ts:40`). Catches
    **dirty**: uncommitted edits don't move HEAD/index, so the watcher alone misses
    them.
  - **On-open / lazy** — recompute on client subscribe / session focus as a
    backstop; the cached value ships in the WS snapshot so the list paints instantly.

- **In-memory cache, no DB.** Lite-status is derived/ephemeral → a
  `Map<sessionId, GitLiteStatus>` in the broker. No migration; rebuilt lazily after
  restart.

- **Swappable trigger.** The cache → broadcast → badge pipeline is identical
  regardless of what fires the recompute. If mid-turn live-dirty ever matters, a
  `git ls-files`-driven tracked-tree watcher (≈240 descriptors/session, linear) can
  be added later with **zero schema/UI change** — documented upgrade path.

- **Scope v1 = broker + web PWA.** The shared Kotlin `SessionInfo` DTO gains the
  field (wire-compatible) so native is a render-only follow-up; iOS/Android badge
  rendering is explicitly out of v1.

## Data model

```ts
interface GitLiteStatus {
  mode: "base" | "remote"   // worktree → base, plain repo → remote
  compareRef: string        // "main" | "origin/feature-x" — for the label
  ahead: number             // commits in HEAD not in compareRef
  behind: number            // commits in compareRef not in HEAD
  dirty: number             // count of uncommitted + untracked (gitignore-respected)
  unpublished?: boolean     // mode:"remote" only — no upstream yet
  computedAt: number        // epoch ms (staleness / debug)
}
```

- Session snapshot gains `git?: GitLiteStatus` (absent for non-git sessions) —
  `src/channels/web/index.ts` `SessionSnapshot` + `src/main.ts`
  `getSessionsSnapshot` (~973–989).
- New WS delta frame `{ type: "session_git", session, git }`.
- Kotlin `SessionInfo` (`apps/shared/.../proto/Frames.kt`) gains the matching field
  (wire-compat; not yet rendered natively).

## Components

- **`src/core/worktree/lite-status.ts` (new)** — `computeLiteStatus(session) =>
  Promise<GitLiteStatus | null>`. Resolves mode from the session record, runs the 2
  async git calls in the worktree/workdir, returns `null` for non-git / on error.
- **`src/core/worktree/status-watcher.ts` (new)** — one consolidated watcher.
  - Worktree session paths: `<common>/.git/worktrees/<id>/HEAD` + shared
    `<common>/.git/refs/heads/<base>` + `<common>/.git/packed-refs`.
  - Plain session paths: `<wd>/.git/HEAD` + `refs/heads/<branch>` +
    `refs/remotes/<upstream>` + `packed-refs`.
  - Debounce (~400ms) + single-flight; on fire → `computeLiteStatus` → update cache
    → broadcast. Maintains the watch-set as sessions spawn/suspend/archive. A
    base-ref change fans out a recompute to all sessions sharing that base.
- **`src/main.ts` (wiring)** — compute + register watch on spawn; subscribe to
  `AgentStateStore` `"change"` (idle → recompute); unregister on suspend/archive;
  include `git` in `getSessionsSnapshot`; broadcast `session_git`.
- **`src/web-app/src/stores/sessions.ts`** — hold per-session `git`; apply
  `session_git` deltas.
- **`src/web-app/src/components/SessionRow.vue` (session list — primary surface)** —
  compact **glyph** badge `↑2 ↓1 ·3` (ahead / behind / dirty), tooltip "2 ahead /
  1 behind main · 3 uncommitted"; **muted "✓ in sync"** when clean (shown, not
  hidden); badge absent only when the session isn't a git repo.
- **`src/web-app/src/components/BranchSyncStatus.vue` (chat header)** — made
  **mode-aware** from the same `GitLiteStatus`: a worktree session's headline shows
  ahead/behind **vs base** ("· ↑2 ↓1 vs main"); a plain session keeps its **vs
  origin** headline. The branch picker + publish/push/pull/fetch actions (remote
  ops) stay — only the headline axis adapts.

## Data flow

```
spawn │ turn-end (idle) │ .git change
        └────────────► debounce + single-flight
                          └─► computeLiteStatus (2 async git calls)
                                └─► update Map cache
                                      └─► broadcast session_git  (+ next snapshot)
                                            └─► web store applies → SessionRow badge
```

On-open the client already holds the cached value from the snapshot; focusing a
session triggers a fresh recompute as a backstop.

## Error handling

- Any git call fails / detached HEAD / base or upstream missing → return `null` (or
  partial with `mode` + `unpublished`); badge hidden. Never throws into the event
  loop.
- Worktree path missing (the existing recreate scenario) → skip, no status.
- Watcher setup error (ENOSPC / missing path) → log + degrade to turn-end + on-open
  only.
- Single-flight prevents pile-ups; concurrent triggers coalesce to one recompute.

## Testing

- `lite-status.test.ts` — parse ahead/behind/dirty from fixtures, both modes: clean,
  ahead-only, behind-only, dirty, detached HEAD, missing base, unpublished (remote
  mode), non-repo (→ null).
- `status-watcher.test.ts` — debounce coalescing; single-flight; base-ref change
  fans out to dependents; add/remove watch on spawn/suspend (fake watcher + injected
  clock).
- snapshot/broadcast includes `git`; `session_git` applied in the web store.
- `SessionRow` badge variants render (base/remote/in-sync/hidden).

## Out of scope (v1)

- iOS/Android badge rendering (DTO field added; render is a follow-up).
- Conflict preflight / diff stats in the badge (stay in Finish / `computeReadiness`).
- Tracked-tree (gitignore-respecting) watcher for live mid-turn dirty (documented
  upgrade path).
- Tapping the badge to open a full diff (the existing Finish/diff UI covers detail).

## Resolved in review

- **"Main source branch" = the recorded `base_branch`** (whatever the session was
  branched from). ✔
- **Badge style** = glyphs (`↑2 ↓1 ·3`) + muted "✓ in sync" when clean (shown). ✔
- **Surfaces** = session-list badge (primary) **and** the mode-aware chat-header
  pill (the axis rule applies to the header too — it currently shows `origin`). ✔

## Open questions

1. **Commit** — write this spec to `mux/supermux-18` now, or leave uncommitted for
   you to edit first?
