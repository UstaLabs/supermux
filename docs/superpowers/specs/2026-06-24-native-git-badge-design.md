# Native git-status badge (iOS + Android) + local/remote visual distinction — Design (2026-06-24)

## Goal

Render the per-session git-status indicator natively on **iOS and Android** at full parity with the web PWA — a glyph badge in the session list **and** a mode-aware chat-header line — and fix a UX confusion: the `↑/↓` arrows read as "sync with the cloud/remote", so the **local** worktree-vs-base comparison must look different from the **remote** branch-vs-origin one. Apply the same visual distinction to the **web** so all three platforms match.

## Context (current state, dev `07d28c2`)

The base-branch-diff feature already shipped the broker + web PWA (spec `2026-06-24-session-base-branch-diff-status-design.md`): the broker computes a per-session `GitLiteStatus`, broadcasts it in the snapshot + a `session_git` delta frame, and the shared Kotlin `SessionInfo` already carries `val git: GitLiteStatusDto? = null` (`{mode:"base"|"remote", compareRef, ahead, behind, dirty, unpublished?, computedAt}`). Native **rendering** was explicitly deferred.

What exists today:
- **iOS** consumes `SessionInfo` via SKIE (`session.git?.ahead` etc. already accessible). `BrokerSession.reduce` has a **no-op `case .sessionGit: break`** stub (frame acknowledged, not applied). The session list is `SessionsListView.SessionRow`; the chat header shows a branch line via `navSubtitle` (ChatView + iPad `SessionChrome`) using the **remote** axis (`GitRemoteStatus`, async) — which is the wrong axis for worktree sessions per the product rule.
- **Android** consumes `SessionInfo` in `SessionListScreen.SessionRow`; `AppViewModel`'s reducer does **not** handle `ServerFrame.SessionGit`; the chat header (`ChatScreen`) has no branch indicator.
- **Web** is fully done (store + `session_git` dispatch + `SessionRow.vue` badge + mode-aware `BranchSyncStatus.vue`), but its `gitBadge.ts` uses `↑/↓` for **both** modes — the confusion this spec fixes.
- No shared `gitBadge` formatter exists yet (logic lives only in web TS).

## Decisions (and why)

- **One shared `gitBadge` formatter in shared Kotlin (`commonMain`)** — used by both iOS (via SKIE) and Android, unit-tested once. Web keeps its own TS copy (different language) but is updated to the identical visual rules. This avoids a 3-way logic drift; the only per-platform piece is the icon + styling.
- **Local vs remote visual split (the UX fix):**
  - **Remote (mode `remote`, branch vs `@{upstream}`):** `↑{ahead} ↓{behind}` — the universal push/pull-to-cloud arrows. No icon (the arrows are self-evident).
  - **Local (mode `base`, worktree vs base branch):** **`+{ahead} −{behind}`** (plus / U+2212 minus) prefixed with a **branch/fork icon** — reads as "this branch diverged from its base", unmistakably *not* cloud sync.
  - **Dirty** (both modes): append `·{dirty}` (uncommitted). **In sync** (all zero): `✓`. **Unpublished** (remote, no upstream): `unpublished`.
- **Full-parity surfaces (both platforms):** session-list badge **and** mode-aware chat header. On iOS the header (`navSubtitle`) becomes mode-aware — base-mode sessions read `⎇ main +3 −1` from `SessionInfo.git`; remote/non-worktree keep today's origin line. Android gains a header line.
- **Live updates:** finish wiring the `session_git` delta frame on native (iOS: fill the `break` stub; Android: add the reducer case) so badges update live, not just on full snapshot/reconnect.
- **Native header is display-only.** The web header has a sync dropdown (publish/push/pull); native keeps actions in the existing Finish flow — the header just shows the badge.

## The shared badge model + formatter

`apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt` (new):
```kotlin
enum class GitBadgeKind { BASE, REMOTE, UNPUBLISHED, INSYNC }
enum class GitBadgeTone { ACTIVE, MUTED }
data class GitBadge(val text: String, val kind: GitBadgeKind, val tone: GitBadgeTone, val compareRef: String)

fun gitBadge(git: GitLiteStatusDto?): GitBadge? {
    if (git == null) return null
    val ref = git.compareRef
    if (git.mode == "remote" && git.unpublished == true)
        return GitBadge("unpublished", GitBadgeKind.UNPUBLISHED, GitBadgeTone.MUTED, ref)
    if (git.ahead == 0 && git.behind == 0 && git.dirty == 0)
        return GitBadge("✓", GitBadgeKind.INSYNC, GitBadgeTone.MUTED, ref)
    val parts = mutableListOf<String>()
    if (git.mode == "base") {
        if (git.ahead != 0) parts += "+${git.ahead}"
        if (git.behind != 0) parts += "−${git.behind}"   // − (true minus)
    } else {
        if (git.ahead != 0) parts += "↑${git.ahead}"     // ↑
        if (git.behind != 0) parts += "↓${git.behind}"   // ↓
    }
    if (git.dirty != 0) parts += "·${git.dirty}"          // ·
    val kind = if (git.mode == "base") GitBadgeKind.BASE else GitBadgeKind.REMOTE
    return GitBadge(parts.joinToString(" "), kind, GitBadgeTone.ACTIVE, ref)
}
```
- **List badge** renders: icon(kind) + `text`. **Header** renders: icon(kind) + (base → `"${compareRef} ${text}"`, e.g. `main +3 −1`; remote → `text`).
- `kind` → icon mapping is per-platform (no shared icon asset).

## Components

**Shared (Kotlin, `commonMain`):** `GitBadge.kt` (above) + `apps/shared/src/commonTest/.../GitBadgeTest.kt`.

**Web (correction, `src/web-app/src/`):**
- `lib/gitBadge.ts` — switch base mode to `+N −M`, keep remote `↑N ↓M`, add a `kind` field to the returned `GitBadge` (mirrors the Kotlin enum as a string union). Update `lib/gitBadge.test.ts`.
- `components/SessionRow.vue` + `components/BranchSyncStatus.vue` — render a branch glyph for `kind === "base"` (inline SVG or `⎇`), no icon for remote.

**iOS (`apps/iosApp/Supermux/`):**
- `Sessions/GitBadgeView.swift` (new) — `kind`→SF Symbol (`arrow.triangle.branch` for base) + `text`, Theme tones (muted = `.secondary`, active = `.primary`), monospace caption.
- `Sessions/SessionsListView.swift` — render `GitBadgeView(gitBadge(session.git))` in `SessionRow`'s subtitle row.
- `Chat/SessionChrome.swift` + `Chat/ChatView.swift` — `navSubtitle` prefers `SessionInfo.git`: base → `⎇ {compareRef} {text}`; else fall back to the existing `GitRemoteStatus` origin line.
- `Broker/BrokerSession.swift` — replace `case .sessionGit: break` with: find the session by id, update its `git`, reassign `sessions` (so SwiftUI re-renders).

**Android (`apps/android/src/main/kotlin/dev/supermux/android/`):**
- `session/GitBadge.kt` (new composable) — `kind`→Material icon (a merge/branch icon for base) + `text`, `MonoFontFamily`, M3 tones (muted = `onSurfaceVariant.copy(alpha=.6f)`, active = `onSurface`).
- `session/SessionListScreen.kt` — render `GitBadge(gitBadge(s.git))` in `SessionRow`.
- `chat/ChatScreen.kt` — add a mode-aware header line (base → `⎇ main +3 −1`, remote → `↑↓`).
- `AppViewModel.kt` — add `is ServerFrame.SessionGit ->` to the reducer: update the matching session's `git` in `_sessions`.

## Data flow / live updates

`SessionInfo.git` already arrives via `snapshot` + `session_added`. Adding the `session_git` handlers (iOS reduce, Android reducer) makes a recompute (turn-end / fs-watch on the broker) update the badge live without a reconnect. `gitBadge(...)` is pure; the UI recomputes the badge from the session's current `git` on each render.

## Error handling

- `git == null` (non-repo session) → `gitBadge` returns null → no badge, no header git line (header falls back to workdir/remote as today).
- Missing/zero fields → handled by the formatter (in-sync `✓`); never throws.
- `session_git` for an unknown/removed session id → handler no-ops (guard on lookup).

## Testing

- **Shared `GitBadgeTest.kt` (the primary tested unit):** base (`+/−`, only-nonzero, dirty appended), remote (`↑/↓`), in-sync `✓`, unpublished, dirty-only, null→null, and `kind`/`tone`/`compareRef` correctness. Run: `cd apps && ./gradlew :shared:jvmTest`.
- **Web `gitBadge.test.ts`:** updated for the new base `+/−` glyphs + `kind`; `bun test src/web-app/src/lib/gitBadge.test.ts`.
- **UI rendering:** thin over the shared/tested formatter. Android compiles via `./gradlew`; iOS compiles on the remote Mac (`xcodebuild`/`:shared:link…`) — cannot compile on this Linux host. SwiftUI/Compose previews optional.

## Out of scope

- Remote sync **actions** on the native header (publish/push/pull) — stays in the Finish flow; the header is display-only.
- A dedicated remote-mode icon (cloud) — the `↑↓` arrows suffice; can add later.
- Re-theming the existing iOS remote `navSubtitle` beyond making it mode-aware.

## Open questions

None outstanding — surfaces (full parity), the local `⎇ +N −M` vs remote `↑N ↓M` distinction, and the web correction are all decided.
