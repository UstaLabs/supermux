# Unified per-session state indicator (working + git, cloud for remote) — Design (2026-06-25)

## Goal

Make each session row's leading element a single **"session state"** indicator that, at a glance, tells you what the session is doing or whether it's finished — replacing the agent avatar. It folds three things into one slot, by priority:

1. **Working** — the agent is actively running → animated spinner.
2. **Git status** (when idle):
   - **Worktree** (vs `dev`): 🟢 ✓ done / 🟡 ⎇ not-done (branch glyph) — but a **pristine** worktree (never committed, clean) shows a **neutral dot**, NOT ✓ (the ✓ is earned by having done work).
   - **Remote** (vs origin): ☁️ **synced** / ☁️ **not-synced** with the **push/pull counts** (`↑N ↓N`) — *new (cloud)*.
3. **Neutral** — non-repo session, or a pristine worktree, idle → a faint dot (or nothing).

## Context

Builds directly on the shipped glanceable-status feature (`2026-06-25-glanceable-session-status-design.md`): worktree sessions already show ✓/⎇ from `sessionDoneState`, rendered as a leading rail+icon; the exact counts are in a tooltip. This iteration adds these the user requested:
- **Cloud status for remote (non-worktree) sessions** — they currently show no indicator (`sessionDoneState` returns null for `mode != "base"`).
- **A "working" state** — surfaced in the same slot.
- **A "pristine" worktree state** — a worktree that has never committed (and is clean) shows a neutral dot, NOT ✓; the green ✓ is reserved for worktrees that *did* work and merged it. Computed from the already-recorded `base_commits` (HEAD SHA per repo at creation; `main.ts:1950`) via a new `touched` flag — see below.
- **Removal of the agent avatar** — the status becomes the row's dominant left element. The avatar (`AgentLogo`/`SessionAvatar`) currently also carries the working spinner, connected dot, and suspended marker. Working moves into the new indicator; **connected and suspended cues are dropped** (system-managed; no user-facing change). The agent *type* remains visible in the chat header.

The branch-vs-cloud split keeps the established metaphor: **branch glyph = local (vs dev)**, **cloud = remote (vs origin)**.

## The unified indicator — states & rendering

Priority order (first match wins):

| Priority | Condition | Visual |
|---|---|---|
| 1 | agent working (phase ∈ working set) | animated spinner, teal (accent) |
| 2 | worktree (`mode==base`), `ahead>0 || dirty>0` | ⎇ branch, **amber** (not-done) |
| 2 | worktree, clean & merged (`ahead==0 && dirty==0`), **touched** | ✓ check, **green** (done) |
| 2 | worktree, clean & merged, **pristine** (never committed) | faint **neutral dot** |
| 3 | remote (`mode==remote`), synced (`ahead==0 && behind==0 && dirty==0 && !unpublished`) | cloud-check, **green** |
| 3 | remote, not synced | cloud + `↑N ↓N` (only nonzero; `·D` if dirty; `unpublished` if no upstream), **amber** |
| 4 | `git==null`, not working | faint neutral dot (or nothing) |

- **Touched vs pristine:** `touched` = the worktree branch has at least one commit since it was created (`<base_commit>..HEAD > 0`). This *persists through a merge* (the commits stay on the branch even once they're in `dev`), so it cleanly separates "did work, now merged" (✓) from "never touched" (neutral) — which the live `ahead`/`dirty` numbers can't do on their own (both read zero after a merge). `base_commit` is **already recorded**: `main.ts:1950` stores `base_commits` = `git rev-parse HEAD` per repo at session creation.
- **Worktree** stays icon-only (counts in tooltip). **Remote** shows the cloud icon **plus** the `↑N ↓N` counts inline (the user wants push/pull visible). "Synced" = even both ways (nothing to push *or* pull) + clean + published.
- **Working overrides git status** while active; when the agent goes idle the git/cloud status reappears.

**Icons (each platform's native cloud + the existing branch/check):**
- Worktree: check (done) / branch (not-done) — already in place (web `Check`/`GitBranch`, iOS `checkmark`/`arrow.triangle.branch`, Android `ic_check`/`ic_git_branch`).
- Remote: cloud-check (synced) / cloud (not-synced) — web lucide `CloudCheck`/`Cloud` (or `CloudOff`), iOS SF `checkmark.icloud`/`icloud` (or `exclamationmark.icloud`), Android `cloud_done`/`cloud_off` (or `cloud_sync`). **Implementation note:** confirm exact icon availability and add drawables (Android `ic_cloud_done`/`ic_cloud_off`) / verify lucide exports during the plan.
- Working: each platform's existing spinner (web `Loader2`/CSS spin, iOS `ProgressView`, Android `CircularProgressIndicator`).

## Removed: the agent avatar

`AgentLogo`/`SessionAvatar` is removed from the session-list row. Reflow: the state indicator becomes the leading element, then name/preview. Consequences:
- **Working** → moves into the indicator (priority 1).
- **Connected** → dropped (system-managed).
- **Suspended** → dropped from the avatar; the row's existing `status` **text badge** ("suspended") is kept (separate element, unaffected).
- **Agent type** → no longer in the list row; still shown in the chat header.

(The `SessionAvatar`/`AgentLogo` components stay defined — still used in the chat header — just not in the list row.)

## Shared status derivation

Generalize the shipped helper in `apps/shared/.../proto/GitBadge.kt`:
```kotlin
enum class SessionStatusKind { WORKTREE, REMOTE }
// DONE = done (worktree, merged+clean) / synced (remote); NOT_DONE = not-done / not-synced;
// PRISTINE = worktree that has never committed (clean) — neutral, no ✓.
enum class SessionStatusLevel { PRISTINE, DONE, NOT_DONE }

/** Unified per-session git status for the list indicator. null when no indicator applies (git==null). */
data class SessionStatus(val kind: SessionStatusKind, val level: SessionStatusLevel)

fun sessionStatus(git: GitLiteStatusDto?): SessionStatus? {
    if (git == null) return null
    return if (git.mode == "base") {
        val level = when {
            git.ahead > 0 || git.dirty > 0 -> SessionStatusLevel.NOT_DONE   // un-integrated work
            git.touched -> SessionStatusLevel.DONE                          // clean + merged + has commits since creation
            else -> SessionStatusLevel.PRISTINE                            // clean + never committed
        }
        SessionStatus(SessionStatusKind.WORKTREE, level)
    } else {
        val synced = git.ahead == 0 && git.behind == 0 && git.dirty == 0 && git.unpublished != true
        SessionStatus(SessionStatusKind.REMOTE, if (synced) SessionStatusLevel.DONE else SessionStatusLevel.NOT_DONE)
    }
}
```

**New `touched` field on the status (small backend addition, uses existing data):**
- `GitLiteStatus`/`GitLiteStatusDto` gains `touched: Boolean` (worktree mode only; `false` otherwise). The broker computes it in `src/core/worktree/lite-status.ts` (base-mode branch) as `git rev-list --count <baseCommit>..HEAD > 0`, where `baseCommit` is the session's creation SHA from the **already-recorded** `base_commits` map (`registry.get(id).base_commits[repoRelPath]`). `main.ts` plumbs that base commit into the lite-status input (alongside `base_branch`/`session_branch`). If `base_commits` is missing (older sessions created before this), default `touched = (ahead > 0)` so they don't regress to a neutral dot when they actually have unmerged work — and once merged they fall to neutral (acceptable for legacy sessions).
- The web `GitLiteStatus` interface gets the same `touched?: boolean`.

The remote count text reuses the existing `gitBadge(git).text` (already `↑N ↓N ·D` / `unpublished` for remote mode). Web mirrors `sessionStatus` in TS. **Working** is not part of this helper — it comes from each platform's existing agent-phase check; the UI composes `working ? spinner : statusFrom(sessionStatus)`.

## Components

- **Broker (`src/core/worktree/lite-status.ts` + `src/main.ts`):** add `touched: boolean` to `GitLiteStatus`; compute it in base-mode as `rev-list --count <baseCommit>..HEAD > 0` (fallback `ahead>0` when no baseCommit); plumb the session's creation SHA from `registry.get(id).base_commits` into the lite-status input. Mirror `touched` in the Kotlin `GitLiteStatusDto` (`apps/shared/.../proto/Frames.kt`) and the web `GitLiteStatus` interface.
- **Shared:** `sessionStatus` + `SessionStatusKind`/`SessionStatusLevel`/`SessionStatus` + unit tests.
- **Web** `SessionRow.vue`: remove `<SessionAvatar>`; the leading slot renders spinner (if working) else the status icon (worktree check/branch, remote cloud + `gitBadge` counts) with rail tint; reflow name/preview. Mirror `sessionStatus` in `gitBadge.ts`; import `Cloud`/`CloudCheck`.
- **iOS** `SessionsListView.swift` + `SessionStatusRail.swift`: drop `AgentLogo` from the row; `SessionStatusRail` gains the working spinner (priority) + the remote cloud branch (`checkmark.icloud`/`icloud` + counts). Working flag from the row's existing `working` computed.
- **Android** `SessionListScreen.swift`→`.kt` + `SessionStatusRail.kt`: drop `SessionAvatar` from the row; `SessionStatusRail` gains the working spinner (priority) + the remote cloud branch (`ic_cloud_done`/`ic_cloud_off` + counts). Working flag from the agent state.

## Error handling

- `git == null` & not working → faint neutral dot / nothing (no crash).
- Remote with no upstream (`unpublished`) → cloud + "unpublished" (not-synced, amber).
- Live updates already flow via the `session_git` frame + agent-state frames.

## Testing

- **Shared `sessionStatus`** unit tests: worktree DONE (clean+merged+`touched`), NOT_DONE (ahead>0; dirty>0), PRISTINE (clean+`!touched`); remote DONE/synced (all zero + published), NOT_DONE/not-synced (ahead / behind / dirty / unpublished each); null → null. `cd apps && ./gradlew :shared:jvmTest`.
- **Broker `lite-status` `touched`** test: a worktree with a commit since `baseCommit` → `touched=true` (even after the commit is merged into the base); a pristine worktree (HEAD==baseCommit) → `touched=false`; missing baseCommit → fallback `touched=(ahead>0)`. `bun test` (the lite-status test file).
- **Web** TS mirror unit test; component typecheck via `cd src/web-app && bun run build`.
- **Android** `:android:compileDebugKotlin` / `assembleDebug`; **iOS** remote-Mac simulator build (Watch excluded).
- Working-overrides-status is UI composition; verified by build + visual.

## Out of scope

- Chat-header changes (keeps the detailed line + the agent identity).
- Push-vs-pull as *separate icons* (cloud-up/cloud-down) — we show the cloud + numeric `↑N ↓N` instead.
- Re-surfacing connected/suspended as icons (intentionally dropped; suspended text badge stays).
- Squash-merge detection (inherited limitation).

## Open questions

None — cloud status + push/pull counts for remote, the working state (overrides git status), the pristine→neutral worktree state (via the existing `base_commits`), avatar removal, and dropping connected/suspended cues are all decided.
