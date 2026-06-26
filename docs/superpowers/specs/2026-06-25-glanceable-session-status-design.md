# Glanceable session "finished vs not" status (session list) — Design (2026-06-25)

## Goal

Make it **immediately obvious, at a glance, which sessions are finished and which are not** when scanning the session list. "Finished" = the session's work is already in `dev` (nothing unmerged) and the tree is clean. Replace the current in-row precise counts (`⎇ +3 −1`) with a single **color + icon** status indicator that reads as the primary signal of the row — not a small secondary detail.

## Context

The git-badge feature (specs `2026-06-24-session-base-branch-diff-status-design.md` + `…-native-git-badge-design.md`) already computes a per-session `GitLiteStatus` and renders it. Two facts that shape this redesign:

- **The comparison is already "live vs dev."** `src/core/worktree/lite-status.ts:49-54` does `git rev-list --count --left-right <base_branch>...HEAD`, where `base_branch` is the **branch name** (e.g. `dev`), resolved at query time. So `ahead` = commits in the session not in the *current* `dev`; the instant the session's work merges into `dev`, `ahead` drops to 0. **No backend change is needed** — "finished = merged into dev" already falls out of the existing `ahead`/`dirty` fields. (Caveat: a *squash* merge rewrites commits, so the originals never become reachable from `dev` and the session would keep reading "not done" — out of scope; the workflow uses real/FF merges.)
- The current list row renders the detailed badge inline (`⎇ +N −M`). This redesign **replaces that inline badge** with the glanceable status indicator. The detailed counts move to a tooltip/long-press and remain in the chat header (the detail view).

## Decisions

- **Two states** (user's choice — maximally glanceable):
  - 🟢 **Done** — `ahead == 0 && dirty == 0` → merged into / on par with `dev`, clean. Icon **✓**, green.
  - 🟡 **Not done** — `ahead > 0` (unmerged commits) **or** `dirty > 0` (uncommitted changes). Icon **⎇**, amber.
  - `behind > 0` alone does NOT make a session "not done" — if your work is in `dev` and `dev` merely moved ahead, you're still done.
- **Applies to worktree-backed sessions only** (`git.mode == "base"`). Non-repo sessions (`git == null`) and plain-repo/remote-mode sessions show **no** status indicator — the "finished vs dev" concept doesn't apply. (Remote-mode keeps today's behavior, untouched.)
- **Visual = A + icons** (left status rail + icon): a **left-edge colored status icon** (✓/⎇) with a **faint matching color tint on the rail** behind it, so the row reads as a scannable color column down the list *and* the icon gives the meaning (works without color too).
- **Icon for "not done" is the branch glyph ⎇, not an arrow** — consistent with the earlier decision that ↑↓ reads as cloud-sync. (Alternative if we dislike ⎇ for the combined state: a filled dot ●. Spec'd as ⎇; trivially swappable.)
- **Counts leave the row.** The exact `+N −M ·D` moves to the row's tooltip (web `title`) / long-press (native); the chat header keeps the detailed mode-aware line (the detail view — unchanged).
- **Deconflict with the unread indicator.** The list already shows unread via a left bar (web/Android) — the left edge now belongs to the **status** rail+icon, so unread moves to a **bold session name + a small dot** to avoid collision.

## The shared status derivation

Add to the shared module (alongside `gitBadge`), so iOS (SKIE) + Android share one rule; web mirrors it in TS.

`apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt`:
```kotlin
enum class SessionDoneState { DONE, NOT_DONE }

/**
 * Glanceable finished-vs-not state for the session list. Worktree (base-mode) sessions only:
 * DONE when merged into / on par with the base branch and clean; NOT_DONE when there are
 * unmerged commits (ahead) or uncommitted changes (dirty). Returns null when no indicator
 * applies (non-repo session, or remote/plain-repo mode).
 */
fun sessionDoneState(git: GitLiteStatusDto?): SessionDoneState? {
    if (git == null || git.mode != "base") return null
    return if (git.ahead == 0 && git.dirty == 0) SessionDoneState.DONE else SessionDoneState.NOT_DONE
}
```
The existing `gitBadge(git)` stays — it provides the detailed text for the tooltip/header. The list row uses `sessionDoneState` for the icon/color.

Web mirror in `src/web-app/src/lib/gitBadge.ts` (or a small `sessionStatus.ts`): `sessionDoneState(git): "done" | "not-done" | null` with the identical rule.

## Components (session list only)

**Shared:** `sessionDoneState` + `SessionDoneState` (above) + unit tests.

**Web** (`src/web-app/src/components/SessionRow.vue`): replace the inline `⎇ +N −M` badge with a left-edge status icon (lucide `Check` green / `GitBranch` amber) + a faint left rail tint; keep the detailed `gitBadge` text as the element's `title` tooltip. Adjust the unread indicator to a bold-name + dot.

**iOS** (`apps/iosApp/Supermux/Sessions/SessionsListView.swift` + a small `SessionStatusRail` view): the row gains a left-edge SF Symbol (`checkmark` green / `arrow.triangle.branch` amber) + a faint rail tint, driven by `GitBadgeKt.sessionDoneState(git:)`. Remove the inline `GitBadgeView` from the row (it stays available for the header). Long-press/context shows the detailed counts.

**Android** (`apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt`): the row gains a left-edge `Icon` (`ic_check` green / `ic_git_branch` amber) + a faint rail tint, driven by `sessionDoneState(s.git)`. Remove the inline `GitBadgeRow` from the row.

## Visual reference

```
🟡⎇  supermux-8        2m      amber rail+icon → NOT DONE (ahead or dirty)
     fixing the badge…
🟢✓  flight-tracker    5m      green rail+icon → DONE (in dev, clean)
     merged, all done
```
Tooltip / long-press on the indicator → the detailed `+3 −1 ·2 vs dev` text.

## Error handling

- `git == null` / non-worktree → `sessionDoneState` returns null → no indicator (row renders as today, minus the old inline badge).
- Live updates already flow via the `session_git` frame (shipped) → the indicator recomputes on each render from the session's current `git`.

## Testing

- **Shared `sessionDoneState`** unit tests: done (ahead 0 + dirty 0), not-done (ahead>0), not-done (dirty>0, ahead 0), behind-only → done, null/remote → null. `cd apps && ./gradlew :shared:jvmTest`.
- **Web** unit test for the TS mirror; component typecheck via `cd src/web-app && bun run build`.
- **Android** compiles via `:android:compileDebugKotlin`; **iOS** via the remote-Mac simulator build (Watch excluded, per the prior recipe).

## Out of scope

- Chat-header changes (keeps the current detailed mode-aware line — it's the detail view).
- Remote-mode / plain-repo "finished" status (the concept is worktree-vs-dev).
- Squash-merge detection (won't flip to done — acknowledged limitation, not in the workflow).
- A separate "dirty" state (collapsed into NOT_DONE per the two-state decision; dirty detail is in the tooltip).

## Open questions

None — visual (A + icons), two states, and the live-vs-dev comparison (already implemented) are all decided.
