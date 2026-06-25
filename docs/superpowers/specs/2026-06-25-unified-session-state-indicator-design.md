# Unified per-session state indicator (working + git, cloud for remote) — Design (2026-06-25)

## Goal

Make each session row's leading element a single **"session state"** indicator that, at a glance, tells you what the session is doing or whether it's finished — replacing the agent avatar. It folds three things into one slot, by priority:

1. **Working** — the agent is actively running → animated spinner.
2. **Git status** (when idle):
   - **Worktree** (vs `dev`): 🟢 ✓ done / 🟡 ⎇ not-done (branch glyph) — *unchanged from the shipped feature*.
   - **Remote** (vs origin): ☁️ **synced** / ☁️ **not-synced** with the **push/pull counts** (`↑N ↓N`) — *new (cloud)*.
3. **Neutral** — non-repo session, idle → a faint dot (or nothing).

## Context

Builds directly on the shipped glanceable-status feature (`2026-06-25-glanceable-session-status-design.md`): worktree sessions already show ✓/⎇ from `sessionDoneState`, rendered as a leading rail+icon; the exact counts are in a tooltip. This iteration adds three things the user requested:
- **Cloud status for remote (non-worktree) sessions** — they currently show no indicator (`sessionDoneState` returns null for `mode != "base"`).
- **A "working" state** — surfaced in the same slot.
- **Removal of the agent avatar** — the status becomes the row's dominant left element. The avatar (`AgentLogo`/`SessionAvatar`) currently also carries the working spinner, connected dot, and suspended marker. Working moves into the new indicator; **connected and suspended cues are dropped** (system-managed; no user-facing change). The agent *type* remains visible in the chat header.

The branch-vs-cloud split keeps the established metaphor: **branch glyph = local (vs dev)**, **cloud = remote (vs origin)**.

## The unified indicator — states & rendering

Priority order (first match wins):

| Priority | Condition | Visual |
|---|---|---|
| 1 | agent working (phase ∈ working set) | animated spinner, teal (accent) |
| 2 | worktree (`mode==base`), `ahead==0 && dirty==0` | ✓ check, **green** |
| 2 | worktree, else | ⎇ branch, **amber** |
| 3 | remote (`mode==remote`), synced (`ahead==0 && behind==0 && dirty==0 && !unpublished`) | cloud-check, **green** |
| 3 | remote, not synced | cloud + `↑N ↓N` (only nonzero; `·D` if dirty; `unpublished` if no upstream), **amber** |
| 4 | `git==null`, not working | faint neutral dot (or nothing) |

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

/** Unified per-session git status for the list indicator. null when no indicator applies (git==null). */
data class SessionStatus(val kind: SessionStatusKind, val done: Boolean)

fun sessionStatus(git: GitLiteStatusDto?): SessionStatus? {
    if (git == null) return null
    return if (git.mode == "base") {
        SessionStatus(SessionStatusKind.WORKTREE, git.ahead == 0 && git.dirty == 0)
    } else {
        val synced = git.ahead == 0 && git.behind == 0 && git.dirty == 0 && git.unpublished != true
        SessionStatus(SessionStatusKind.REMOTE, synced)
    }
}
```
Keep the existing `sessionDoneState` or replace its callers with `sessionStatus` (the plan will migrate the 3 rows). The remote count text reuses the existing `gitBadge(git).text` (already `↑N ↓N ·D` / `unpublished` for remote mode). Web mirrors `sessionStatus` in TS. **Working** is not part of this helper — it comes from each platform's existing agent-phase check; the UI composes `working ? spinner : statusFrom(sessionStatus)`.

## Components (session list row)

- **Shared:** `sessionStatus` + `SessionStatusKind`/`SessionStatus` + unit tests.
- **Web** `SessionRow.vue`: remove `<SessionAvatar>`; the leading slot renders spinner (if working) else the status icon (worktree check/branch, remote cloud + `gitBadge` counts) with rail tint; reflow name/preview. Mirror `sessionStatus` in `gitBadge.ts`; import `Cloud`/`CloudCheck`.
- **iOS** `SessionsListView.swift` + `SessionStatusRail.swift`: drop `AgentLogo` from the row; `SessionStatusRail` gains the working spinner (priority) + the remote cloud branch (`checkmark.icloud`/`icloud` + counts). Working flag from the row's existing `working` computed.
- **Android** `SessionListScreen.swift`→`.kt` + `SessionStatusRail.kt`: drop `SessionAvatar` from the row; `SessionStatusRail` gains the working spinner (priority) + the remote cloud branch (`ic_cloud_done`/`ic_cloud_off` + counts). Working flag from the agent state.

## Error handling

- `git == null` & not working → faint neutral dot / nothing (no crash).
- Remote with no upstream (`unpublished`) → cloud + "unpublished" (not-synced, amber).
- Live updates already flow via the `session_git` frame + agent-state frames.

## Testing

- **Shared `sessionStatus`** unit tests: worktree done/not-done; remote synced (all zero + published); remote not-synced (ahead / behind / dirty / unpublished each); null → null. `cd apps && ./gradlew :shared:jvmTest`.
- **Web** TS mirror unit test; component typecheck via `cd src/web-app && bun run build`.
- **Android** `:android:compileDebugKotlin` / `assembleDebug`; **iOS** remote-Mac simulator build (Watch excluded).
- Working-overrides-status is UI composition; verified by build + visual.

## Out of scope

- Chat-header changes (keeps the detailed line + the agent identity).
- Push-vs-pull as *separate icons* (cloud-up/cloud-down) — we show the cloud + numeric `↑N ↓N` instead.
- Re-surfacing connected/suspended as icons (intentionally dropped; suspended text badge stays).
- Squash-merge detection (inherited limitation).

## Open questions

None — cloud status + push/pull counts for remote, the working state (overrides git status), avatar removal, and dropping connected/suspended cues are all decided.
