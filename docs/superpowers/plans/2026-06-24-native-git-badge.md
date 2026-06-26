# Native git-status badge (iOS + Android) + local/remote visual distinction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the per-session git-status indicator natively on iOS + Android at full parity with the web (session-list badge + mode-aware chat header), with local (worktree-vs-base) shown as `⎇ +N −M` and remote (vs origin) as `↑N ↓M`, and correct the web badge to match — all driven by one shared-Kotlin `gitBadge` formatter.

**Architecture:** A pure `gitBadge(GitLiteStatusDto?): GitBadge?` formatter in shared Kotlin (`commonMain`) is the single source of the visual rules; iOS calls it via SKIE (`GitBadgeKt.gitBadge`), Android calls it directly, web keeps an equivalent TS copy. Each platform maps the badge's `kind` to its own branch icon (SF Symbol / `ic_git_branch` drawable / lucide `GitBranch`). The `session_git` delta frame, already a `ServerFrame` variant, gets its reducer body filled in on both native apps so badges update live.

**Tech Stack:** Kotlin Multiplatform (`commonMain` + `commonTest`, kotlin.test), Jetpack Compose (Android), SwiftUI + SKIE (iOS), Vue 3 + TypeScript (web, `bun test`). Gradle root is `apps/` (`:shared`, `:android`).

**Worktree:** Already on branch `mux/supermux-18` at dev tip `07d28c2`, at `/home/ahmet/.mux/worktrees/supermux-3962b5bf/c7b124d4-773a-4b23-bdcc-4681736c99bd`. All paths below are relative to that root.

**iOS build caveat:** iOS Swift **cannot compile on this Linux host**. Tasks 7–8 write Swift verified against the patterns shown; the iOS build is verified in Task 9 on the remote Mac (`ssh mac`).

---

### Task 1: Shared `gitBadge` formatter + tests (the DRY core)

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/proto/GitBadgeTest.kt`

Context: `GitLiteStatusDto` lives in `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt` (lines 44-53): `data class GitLiteStatusDto(val mode: String = "base", val compareRef: String = "", val ahead: Int = 0, val behind: Int = 0, val dirty: Int = 0, val unpublished: Boolean? = null, val computedAt: Double = 0.0)`. We add the formatter in the same package so SKIE exposes it (as `GitBadgeKt.gitBadge`) and Android imports `dev.supermux.proto.gitBadge`.

- [ ] **Step 1: Write the failing test**

Create `apps/shared/src/commonTest/kotlin/dev/supermux/proto/GitBadgeTest.kt`:
```kotlin
package dev.supermux.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitBadgeTest {
    @Test fun null_status_no_badge() {
        assertNull(gitBadge(null))
    }

    @Test fun in_sync_is_muted_check() {
        val b = gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main"))
        assertEquals(GitBadge("✓", GitBadgeKind.INSYNC, GitBadgeTone.MUTED, "main"), b)
    }

    @Test fun base_mode_uses_plus_minus_and_branch_kind() {
        val b = gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2, behind = 1, dirty = 3))
        assertEquals("+2 −1 ·3", b?.text)
        assertEquals(GitBadgeKind.BASE, b?.kind)
        assertEquals(GitBadgeTone.ACTIVE, b?.tone)
        assertEquals("main", b?.compareRef)
    }

    @Test fun remote_mode_uses_arrows_and_remote_kind() {
        val b = gitBadge(GitLiteStatusDto(mode = "remote", compareRef = "origin/x", ahead = 2, behind = 1))
        assertEquals("↑2 ↓1", b?.text)
        assertEquals(GitBadgeKind.REMOTE, b?.kind)
    }

    @Test fun only_nonzero_parts_shown() {
        assertEquals("+2", gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2))?.text)
        assertEquals("↓3", gitBadge(GitLiteStatusDto(mode = "remote", compareRef = "o", behind = 3))?.text)
    }

    @Test fun base_dirty_only_active() {
        val b = gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", dirty = 5))
        assertEquals("·5", b?.text)
        assertEquals(GitBadgeKind.BASE, b?.kind)
        assertEquals(GitBadgeTone.ACTIVE, b?.tone)
    }

    @Test fun unpublished_remote_muted() {
        val b = gitBadge(GitLiteStatusDto(mode = "remote", compareRef = "x", unpublished = true))
        assertEquals(GitBadge("unpublished", GitBadgeKind.UNPUBLISHED, GitBadgeTone.MUTED, "x"), b)
    }

    @Test fun unpublished_ignored_in_base_mode() {
        val b = gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 1, unpublished = true))
        assertEquals("+1", b?.text)
        assertEquals(GitBadgeKind.BASE, b?.kind)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.proto.GitBadgeTest"`
Expected: FAIL — compile error, `gitBadge` / `GitBadge` / `GitBadgeKind` / `GitBadgeTone` unresolved.

- [ ] **Step 3: Write the formatter**

Create `apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt`:
```kotlin
package dev.supermux.proto

/** Visual category of a session's git badge — selects the per-platform icon + tone. */
enum class GitBadgeKind { BASE, REMOTE, UNPUBLISHED, INSYNC }

enum class GitBadgeTone { ACTIVE, MUTED }

/**
 * Rendered git badge for a session. [text] is the glyph string; [kind] selects the
 * platform icon (branch glyph for BASE, none for REMOTE); [tone] selects styling;
 * [compareRef] is the ref the counts are relative to (used for the header label).
 */
data class GitBadge(
    val text: String,
    val kind: GitBadgeKind,
    val tone: GitBadgeTone,
    val compareRef: String,
)

/**
 * Formats a [GitLiteStatusDto] for display. Pure; shared by the iOS (SKIE) and Android
 * UIs so the local-vs-remote visual rules live in one place.
 *
 * - base mode (worktree vs base branch):  `+{ahead} −{behind}`  → BASE (branch icon)
 * - remote mode (branch vs @{upstream}):  `↑{ahead} ↓{behind}`  → REMOTE (no icon)
 * - dirty (both modes):                   append `·{dirty}`
 * - all zero:                             `✓`            → INSYNC (muted)
 * - remote with no upstream:              `unpublished`  → UNPUBLISHED (muted)
 * - null status (non-repo session):       null (no badge)
 *
 * Note: `−` is U+2212 MINUS SIGN (not a hyphen); `·` is U+00B7 MIDDLE DOT.
 */
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
        if (git.behind != 0) parts += "−${git.behind}"
    } else {
        if (git.ahead != 0) parts += "↑${git.ahead}"
        if (git.behind != 0) parts += "↓${git.behind}"
    }
    if (git.dirty != 0) parts += "·${git.dirty}"
    val kind = if (git.mode == "base") GitBadgeKind.BASE else GitBadgeKind.REMOTE
    return GitBadge(parts.joinToString(" "), kind, GitBadgeTone.ACTIVE, ref)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.proto.GitBadgeTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt apps/shared/src/commonTest/kotlin/dev/supermux/proto/GitBadgeTest.kt
git commit -m "feat(shared): gitBadge formatter — local +/− vs remote ↑↓, kind/tone for native UIs"
```

---

### Task 2: Web `gitBadge.ts` correction + tests

**Files:**
- Modify: `src/web-app/src/lib/gitBadge.ts`
- Test: `src/web-app/src/lib/gitBadge.test.ts`

Context: today's `gitBadge.ts` uses `↑/↓` for both modes and has no `kind`. We switch base mode to `+/−`, gate `unpublished` on remote mode (parity with the Kotlin formatter), and add `kind`.

- [ ] **Step 1: Update the test to the new behavior (failing)**

Replace the entire contents of `src/web-app/src/lib/gitBadge.test.ts` with:
```typescript
import { test, expect } from "bun:test"
import { gitBadge } from "./gitBadge"

test("undefined → null (no badge)", () => { expect(gitBadge(undefined)).toBeNull() })

test("clean → muted in-sync", () => {
  const b = gitBadge({ mode: "base", compareRef: "main", ahead: 0, behind: 0, dirty: 0, computedAt: 0 })
  expect(b).toEqual({ text: "✓ in sync", title: "In sync with main", tone: "muted", kind: "insync" })
})

test("base mode → +/− glyphs, branch kind, active tone", () => {
  const b = gitBadge({ mode: "base", compareRef: "main", ahead: 2, behind: 1, dirty: 3, computedAt: 0 })
  expect(b?.text).toBe("+2 −1 ·3")
  expect(b?.kind).toBe("base")
  expect(b?.tone).toBe("active")
  expect(b?.title).toBe("2 ahead / 1 behind main · 3 uncommitted")
})

test("remote mode → ↑/↓ arrows, remote kind, origin label", () => {
  const b = gitBadge({ mode: "remote", compareRef: "origin/x", ahead: 1, behind: 0, dirty: 0, computedAt: 0 })
  expect(b?.text).toBe("↑1")
  expect(b?.kind).toBe("remote")
  expect(b?.title).toBe("1 ahead origin")
})

test("unpublished remote → muted unpublished", () => {
  const b = gitBadge({ mode: "remote", compareRef: "x", ahead: 0, behind: 0, dirty: 0, unpublished: true, computedAt: 0 })
  expect(b).toEqual({ text: "unpublished", title: "Not published", tone: "muted", kind: "unpublished" })
})

test("base dirty-only → ·N, branch kind, active", () => {
  const b = gitBadge({ mode: "base", compareRef: "main", ahead: 0, behind: 0, dirty: 3, computedAt: 0 })
  expect(b).toEqual({ text: "·3", title: "3 uncommitted", tone: "active", kind: "base" })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `bun test src/web-app/src/lib/gitBadge.test.ts`
Expected: FAIL — base test still gets `↑2 ↓1 ·3`, and `kind` is `undefined`.

- [ ] **Step 3: Update the formatter**

Replace the entire contents of `src/web-app/src/lib/gitBadge.ts` with:
```typescript
import type { GitLiteStatus } from "@/stores/gitStatus"

export interface GitBadge {
  text: string
  title: string
  tone: "muted" | "active"
  kind: "base" | "remote" | "unpublished" | "insync"
}

export function gitBadge(git: GitLiteStatus | undefined): GitBadge | null {
  if (!git) return null
  const ref = git.mode === "base" ? (git.compareRef || "base") : (git.compareRef.split("/")[0] || "origin")
  if (git.mode === "remote" && git.unpublished)
    return { text: "unpublished", title: "Not published", tone: "muted", kind: "unpublished" }

  const parts: string[] = []
  if (git.mode === "base") {
    if (git.ahead) parts.push(`+${git.ahead}`)
    if (git.behind) parts.push(`−${git.behind}`)
  } else {
    if (git.ahead) parts.push(`↑${git.ahead}`)
    if (git.behind) parts.push(`↓${git.behind}`)
  }
  if (git.dirty) parts.push(`·${git.dirty}`)
  if (parts.length === 0) return { text: "✓ in sync", title: `In sync with ${ref}`, tone: "muted", kind: "insync" }

  const ab: string[] = []
  if (git.ahead) ab.push(`${git.ahead} ahead`)
  if (git.behind) ab.push(`${git.behind} behind`)
  const titleBits: string[] = []
  if (ab.length) titleBits.push(`${ab.join(" / ")} ${ref}`)
  if (git.dirty) titleBits.push(`${git.dirty} uncommitted`)
  return {
    text: parts.join(" "),
    title: titleBits.join(" · "),
    tone: "active",
    kind: git.mode === "base" ? "base" : "remote",
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `bun test src/web-app/src/lib/gitBadge.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/web-app/src/lib/gitBadge.ts src/web-app/src/lib/gitBadge.test.ts
git commit -m "fix(web): gitBadge local mode uses +/− (not ↑↓) + kind field"
```

---

### Task 3: Web components render the branch icon

**Files:**
- Modify: `src/web-app/src/components/SessionRow.vue`
- Modify: `src/web-app/src/components/BranchSyncStatus.vue`

Context: `SessionRow.vue` already renders `badge.text` via the Task-2 formatter (so it now shows `+/−` for base). We add the lucide `GitBranch` glyph when `badge.kind === "base"`. `BranchSyncStatus.vue` has its OWN `stateLabel` (not using `gitBadge`) that still emits `↑↓` for base — we change it to `+/−` and add the icon. `GitBranch` comes from `lucide-vue-next` (already used in this codebase).

- [ ] **Step 1: SessionRow.vue — import the icon**

In `src/web-app/src/components/SessionRow.vue`, the imports currently include (lines 1-6):
```typescript
import { computed, ref, nextTick } from "vue"
import { useMessages } from "@/stores/messages"
import { useAgentState, isAgentWorking } from "@/stores/agentState"
import { useGitStatus } from "@/stores/gitStatus"
import { gitBadge } from "@/lib/gitBadge"
import SessionAvatar from "@/components/SessionAvatar.vue"
```
Add this import line after the `SessionAvatar` import:
```typescript
import { GitBranch } from "lucide-vue-next"
```

- [ ] **Step 2: SessionRow.vue — render the icon in the badge**

Replace the existing badge span (currently at ~lines 120-125):
```vue
<span
  v-if="badge"
  :title="badge.title"
  class="shrink-0 font-mono text-[10px] tabular-nums"
  :class="badge.tone === 'muted' ? 'text-muted-foreground/45' : 'text-muted-foreground/80'"
>{{ badge.text }}</span>
```
with:
```vue
<span
  v-if="badge"
  :title="badge.title"
  class="inline-flex shrink-0 items-center gap-0.5 font-mono text-[10px] tabular-nums"
  :class="badge.tone === 'muted' ? 'text-muted-foreground/45' : 'text-muted-foreground/80'"
><GitBranch v-if="badge.kind === 'base'" class="size-2.5 shrink-0" />{{ badge.text }}</span>
```

- [ ] **Step 3: BranchSyncStatus.vue — local mode uses +/−**

In `src/web-app/src/components/BranchSyncStatus.vue`, the `stateLabel` computed currently is (lines 35-48):
```typescript
const stateLabel = computed(() => {
  if (base.value) {
    const a = base.value.ahead, b = base.value.behind
    if (a && b) return `↑${a} ↓${b}`
    if (a) return `↑${a}`
    if (b) return `↓${b}`
    return "✓"
  }
  if (!published.value) return "not published"
  if (ahead.value && behind.value) return `↑${ahead.value} ↓${behind.value}`
  if (ahead.value) return `↑${ahead.value}`
  if (behind.value) return `↓${behind.value}`
  return "✓"
})
```
Change ONLY the `base.value` branch to use `+/−` (leave the remote branch as `↑↓`):
```typescript
const stateLabel = computed(() => {
  if (base.value) {
    const a = base.value.ahead, b = base.value.behind
    if (a && b) return `+${a} −${b}`
    if (a) return `+${a}`
    if (b) return `−${b}`
    return "✓"
  }
  if (!published.value) return "not published"
  if (ahead.value && behind.value) return `↑${ahead.value} ↓${behind.value}`
  if (ahead.value) return `↑${ahead.value}`
  if (behind.value) return `↓${behind.value}`
  return "✓"
})
```

- [ ] **Step 4: BranchSyncStatus.vue — import + render the icon**

Add `GitBranch` to the existing `lucide-vue-next` import in `BranchSyncStatus.vue` (the file already imports `Loader2Icon` from there — add `GitBranch` to that import's named list, e.g. `import { Loader2Icon, GitBranch } from "lucide-vue-next"`).

Then in the sync-state button (lines 146-151):
```vue
<button type="button" :class="segBtn" class="shrink-0" aria-label="Branch sync" :title="stateTitle">
  <span class="opacity-80">· {{ stateLabel }}</span>
  <Loader2Icon v-if="busy" class="size-3 shrink-0 animate-spin" />
</button>
```
add the icon before the label span:
```vue
<button type="button" :class="segBtn" class="shrink-0" aria-label="Branch sync" :title="stateTitle">
  <GitBranch v-if="base" class="size-3 shrink-0" />
  <span class="opacity-80">· {{ stateLabel }}</span>
  <Loader2Icon v-if="busy" class="size-3 shrink-0 animate-spin" />
</button>
```

- [ ] **Step 5: Typecheck + build**

Run: `cd src/web-app && bun run build`
Expected: PASS — `vue-tsc --noEmit` reports no errors and `vite build` succeeds. (If `bun run build` is slow, `bunx vue-tsc --noEmit` alone is a sufficient type gate.)

- [ ] **Step 6: Commit**

```bash
git add src/web-app/src/components/SessionRow.vue src/web-app/src/components/BranchSyncStatus.vue
git commit -m "feat(web): branch glyph for local-mode git badge + header"
```

---

### Task 4: Android — `session_git` reducer (live updates)

**Files:**
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt`

Context: the reducer's `when (f)` (lines 158-231) handles `ServerFrame` cases and falls through to `else -> {}`. `ServerFrame.SessionGit` (Frames.kt:166-167) is `data class SessionGit(val session: String = "", val git: GitLiteStatusDto? = null)`. The existing `FinishJobFrame` handler (lines 210-212) shows the map/copy idiom for updating one session in `_sessions`.

- [ ] **Step 1: Add the SessionGit case**

In `apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt`, inside the `when (f)` block, immediately BEFORE the final `else -> {}` line, add:
```kotlin
                is ServerFrame.SessionGit ->
                    _sessions.value = _sessions.value.map { s ->
                        if (s.id == f.session) s.copy(git = f.git) else s
                    }
```
(Match the surrounding indentation. `SessionInfo` is a Kotlin data class, so `.copy(git = …)` is available; no new import needed — `ServerFrame` and `SessionInfo` are already imported in this file.)

- [ ] **Step 2: Compile to verify**

Run: `cd apps && ./gradlew :android:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt
git commit -m "feat(android): apply session_git frame to live-update session badges"
```

---

### Task 5: Android — `GitBadgeRow` composable + render in session row

**Files:**
- Create: `apps/android/src/main/kotlin/dev/supermux/android/session/GitBadge.kt`
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt`

Context: `R.drawable.ic_git_branch` already exists. The session row's content `Column` (SessionListScreen.kt) shows name+time, then a status badge, then a preview line. We add the git badge under the name row. `MonoFontFamily` is imported from `dev.supermux.android.theme.MonoFontFamily`.

- [ ] **Step 1: Create the composable**

Create `apps/android/src/main/kotlin/dev/supermux/android/session/GitBadge.kt`:
```kotlin
package dev.supermux.android.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.GitBadgeTone
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.gitBadge

/**
 * Per-session git badge: branch icon + `+N −M` for local (base), `↑N ↓M` for remote.
 * Renders nothing when [git] is null (non-repo session).
 */
@Composable
fun GitBadgeRow(git: GitLiteStatusDto?, modifier: Modifier = Modifier) {
    val badge = gitBadge(git) ?: return
    val cs = MaterialTheme.colorScheme
    val color: Color =
        if (badge.tone == GitBadgeTone.MUTED) cs.onSurfaceVariant.copy(alpha = 0.6f) else cs.onSurface
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (badge.kind == GitBadgeKind.BASE) {
            Icon(
                painter = painterResource(R.drawable.ic_git_branch),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(11.dp),
            )
        }
        Text(badge.text, color = color, fontFamily = MonoFontFamily, fontSize = 10.sp)
    }
}
```

- [ ] **Step 2: Render it in SessionRow**

In `apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt`, the status-badge block currently reads:
```kotlin
            // Fix 2: status badge — show when status is non-null and not "active"
            val status = s.status
            if (status != null && status != "active") {
                val badgeColor = if (status == "suspended") Color(c.warning)
                                 else cs.onSurfaceVariant.copy(alpha = 0.6f)
                Spacer(Modifier.height(2.dp))
                Text(
                    status,
                    color = badgeColor,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                )
            } else {
                Spacer(Modifier.height(Space.xs))
            }
```
Immediately AFTER that whole `if/else` block (and before the `// Preview:` block), add:
```kotlin
            // Git status badge (worktree-vs-base or branch-vs-remote divergence).
            if (s.git != null) {
                Spacer(Modifier.height(2.dp))
                GitBadgeRow(s.git)
            }
```

- [ ] **Step 3: Compile to verify**

Run: `cd apps && ./gradlew :android:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`s.git` is accessible — `SessionInfo.git` is in the shared module; `GitBadgeRow` is in the same `session` package, no import needed.)

- [ ] **Step 4: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/session/GitBadge.kt apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt
git commit -m "feat(android): git-status badge in the session list row"
```

---

### Task 6: Android — mode-aware git line in the chat header

**Files:**
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt`

Context: the header `Column` (ChatScreen.kt lines ~509-530) shows the session name then a `subLabel` (workdir / agent·model). We add a git line below the subLabel: base → branch icon + `compareRef text` (e.g. `main +3 −1`), remote → `text` (`↑3 ↓1`). Reuse the shared `gitBadge`. `session: SessionInfo` is in scope here.

- [ ] **Step 1: Add imports**

In `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt`, ensure these imports exist (add any missing ones alongside the existing imports):
```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.supermux.android.R
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.gitBadge
```

- [ ] **Step 2: Render the git line under the subLabel**

The header Column currently is:
```kotlin
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            val subLabel = buildString {
                if (session.workdir.isNotEmpty()) {
                    append(session.workdir)
                } else {
                    append(session.agent)
                    session.model?.let { append(" · $it") }
                }
            }
            Text(
                text = subLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
```
Add, immediately AFTER the `subLabel` `Text(...)` and still inside the `Column`:
```kotlin
            val badge = gitBadge(session.git)
            if (badge != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (badge.kind == GitBadgeKind.BASE) {
                        Icon(
                            painter = painterResource(R.drawable.ic_git_branch),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    val label = if (badge.kind == GitBadgeKind.BASE && badge.compareRef.isNotEmpty())
                        "${badge.compareRef} ${badge.text}" else badge.text
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
```
(`Alignment` is already imported in this file — it uses `Alignment.CenterVertically` in the header Row. If not, add `import androidx.compose.ui.Alignment`.)

- [ ] **Step 3: Compile to verify**

Run: `cd apps && ./gradlew :android:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt
git commit -m "feat(android): mode-aware git line in the chat header"
```

---

### Task 7: iOS — `GitBadgeView` + `session_git` handler

**Files:**
- Create: `apps/iosApp/Supermux/Sessions/GitBadgeView.swift`
- Modify: `apps/iosApp/Supermux/Broker/BrokerSession.swift:124`

**iOS does not compile on this Linux host — write carefully against the patterns shown; Task 9 verifies the build on the remote Mac.** The shared `gitBadge` is exposed via SKIE as `GitBadgeKt.gitBadge(git:)` returning `GitBadge?` with `.text`, `.kind` (`GitBadgeKind`, cases `.base`/`.remote`/`.unpublished`/`.insync`), `.tone` (`GitBadgeTone`, `.active`/`.muted`), `.compareRef`. `SessionInfo.git` is a `GitLiteStatusDto?`.

- [ ] **Step 1: Create the badge view**

Create `apps/iosApp/Supermux/Sessions/GitBadgeView.swift`:
```swift
import SwiftUI
import Shared

/// Per-session git badge: branch icon + `+N −M` for local (base), `↑N ↓M` for remote.
/// Renders nothing when `git` is nil (non-repo session).
struct GitBadgeView: View {
    let git: GitLiteStatusDto?

    var body: some View {
        if let badge = GitBadgeKt.gitBadge(git: git) {
            HStack(spacing: 3) {
                if badge.kind == .base {
                    Image(systemName: "arrow.triangle.branch")
                        .font(.system(size: 9, weight: .medium))
                }
                Text(badge.text)
                    .font(.caption2.monospaced())
            }
            .foregroundStyle(badge.tone == .muted ? Color.secondary : Color.primary)
            .lineLimit(1)
        }
    }
}
```

- [ ] **Step 2: Fill in the `.sessionGit` handler**

In `apps/iosApp/Supermux/Broker/BrokerSession.swift`, the reduce switch currently ends with (line 124):
```swift
        case .sessionGit: break
```
Replace that line with:
```swift
        case .sessionGit(let g):
            if let idx = sessions.firstIndex(where: { $0.id == g.session }) {
                sessions[idx] = sessions[idx].doCopy(
                    id: sessions[idx].id, name: sessions[idx].name, workdir: sessions[idx].workdir,
                    agent: sessions[idx].agent, status: sessions[idx].status, mute: sessions[idx].mute,
                    connected: sessions[idx].connected, model: sessions[idx].model,
                    repoRoot: sessions[idx].repoRoot, role: sessions[idx].role,
                    sessionBranch: sessions[idx].sessionBranch, git: g.git,
                    finishJob: sessions[idx].finish_job)
            }
```

> **Implementer note (resolve on the Mac):** `SessionInfo` is a Kotlin `data class`; SKIE may expose its copy as `doCopy(...)` (all params) as shown, or the simpler `copy(git:)` may not exist. If `doCopy` with the full parameter list does not match the SKIE-generated signature, the robust fallback that needs no copy method is to refetch the snapshot field — but prefer the copy. Verify the exact generated signature with `grep -A30 "class SessionInfo" <derived Shared module>` or by the Xcode error, and adjust the parameter labels to match. The semantic requirement is: replace `sessions[idx]` with the same session but `git = g.git`.

- [ ] **Step 3: Commit (build verified later on the Mac)**

```bash
git add apps/iosApp/Supermux/Sessions/GitBadgeView.swift apps/iosApp/Supermux/Broker/BrokerSession.swift
git commit -m "feat(ios): GitBadgeView + apply session_git frame for live badge updates"
```

---

### Task 8: iOS — render the badge in the row + mode-aware `navSubtitle`

**Files:**
- Modify: `apps/iosApp/Supermux/Sessions/SessionsListView.swift` (SessionRow, ~lines 183-198)
- Modify: `apps/iosApp/Supermux/Chat/ChatView.swift` (navSubtitle, lines 216-225)
- Modify: `apps/iosApp/Supermux/Chat/SessionChrome.swift` (navSubtitle, lines 71-80)

**iOS does not compile on Linux — verified on the Mac in Task 9.**

- [ ] **Step 1: Render the badge in the session row**

In `apps/iosApp/Supermux/Sessions/SessionsListView.swift`, the `SessionRow` body's subtitle is currently:
```swift
                Text(preview ?? session.agent)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
```
Replace it with a row holding the preview + the badge at the trailing edge:
```swift
                HStack(spacing: 6) {
                    Text(preview ?? session.agent)
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Spacer(minLength: 0)
                    GitBadgeView(git: session.git)
                }
```

- [ ] **Step 2: Make ChatView.navSubtitle mode-aware**

In `apps/iosApp/Supermux/Chat/ChatView.swift`, `navSubtitle` is currently:
```swift
    private var navSubtitle: String {
        if let g = git, g.isRepo, let b = g.branch {
            if g.upstream == nil { return "\(b) · not published" }
            var s = b
            if g.ahead > 0 { s += " ↑\(g.ahead)" }
            if g.behind > 0 { s += " ↓\(g.behind)" }
            return s
        }
        return formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir))
    }
```
Replace it with (prefer the live base-mode lite status; fall back to today's remote line):
```swift
    private var navSubtitle: String {
        // Prefer the at-a-glance base-mode status (worktree vs base branch), live from the broker.
        let lite = broker.sessions.first { $0.id == session.id }?.git ?? session.git
        if let lite, let badge = GitBadgeKt.gitBadge(git: lite), badge.kind == .base {
            return lite.compareRef.isEmpty ? badge.text : "\(lite.compareRef) \(badge.text)"
        }
        if let g = git, g.isRepo, let b = g.branch {
            if g.upstream == nil { return "\(b) · not published" }
            var s = b
            if g.ahead > 0 { s += " ↑\(g.ahead)" }
            if g.behind > 0 { s += " ↓\(g.behind)" }
            return s
        }
        return formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir))
    }
```
(`broker` is already referenced in this view, e.g. `broker.gitStatus(...)` in `loadSession`. `GitBadgeKt` comes from `import Shared`, already imported.)

- [ ] **Step 3: Make SessionChrome.navSubtitle mode-aware (iPad)**

In `apps/iosApp/Supermux/Chat/SessionChrome.swift`, `navSubtitle` is currently:
```swift
    var navSubtitle: String {
        if let g = git, g.isRepo, let b = g.branch {
            if g.upstream == nil { return "\(b) · not published" }
            var s = b
            if g.ahead > 0 { s += " ↑\(g.ahead)" }
            if g.behind > 0 { s += " ↓\(g.behind)" }
            return s
        }
        return formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir))
    }
```
Replace it with (use the session's lite status for base mode):
```swift
    var navSubtitle: String {
        if let lite = session.git, let badge = GitBadgeKt.gitBadge(git: lite), badge.kind == .base {
            return lite.compareRef.isEmpty ? badge.text : "\(lite.compareRef) \(badge.text)"
        }
        if let g = git, g.isRepo, let b = g.branch {
            if g.upstream == nil { return "\(b) · not published" }
            var s = b
            if g.ahead > 0 { s += " ↑\(g.ahead)" }
            if g.behind > 0 { s += " ↓\(g.behind)" }
            return s
        }
        return formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir))
    }
```
> **Implementer note:** `SessionChrome` reloads its `session` via `load(for:)` on switches, so `session.git` refreshes on navigation. If `SessionChrome` stores a `broker` reference, prefer `broker.sessions.first { $0.id == session.id }?.git ?? session.git` for live updates while the chat is open (parity with ChatView). Add `import Shared` if not already present.

- [ ] **Step 4: Commit (build verified on the Mac in Task 9)**

```bash
git add apps/iosApp/Supermux/Sessions/SessionsListView.swift apps/iosApp/Supermux/Chat/ChatView.swift apps/iosApp/Supermux/Chat/SessionChrome.swift
git commit -m "feat(ios): git badge in session row + mode-aware navSubtitle (vs base for worktrees)"
```

---

### Task 9: Full verification (local: shared/web/Android; remote Mac: iOS)

**Files:** none (verification only).

- [ ] **Step 1: Shared formatter tests**

Run: `cd apps && ./gradlew :shared:jvmTest`
Expected: BUILD SUCCESSFUL — `GitBadgeTest` (8) and all existing shared tests pass.

- [ ] **Step 2: Web tests + build**

Run: `bun test src/web-app/src/lib/gitBadge.test.ts` (expect 6 pass), then `cd src/web-app && bun run build` (expect `vue-tsc` clean + `vite build` success).

- [ ] **Step 3: Android compiles (debug APK)**

Run: `cd apps && ./gradlew :android:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: iOS builds on the remote Mac**

iOS Swift could not be compiled locally. Sync the worktree to the Mac and build for the simulator (ad-hoc signed per the `ios-simulator-on-remote-mac` recipe — do NOT use `CODE_SIGNING_ALLOWED=NO`, the App-Group entitlement requires signing):
```bash
# from this Linux host — copy the tree (tar over ssh; macOS rsync is openrsync)
cd /home/ahmet/.mux/worktrees/supermux-3962b5bf/c7b124d4-773a-4b23-bdcc-4681736c99bd
tar cf - --exclude .git --exclude build --exclude .gradle --exclude node_modules apps src docs \
  | ssh mac 'rm -rf ~/sm-badge && mkdir -p ~/sm-badge && tar xf - -C ~/sm-badge'
# on the Mac: regenerate the Xcode project, pre-warm the KMP framework, then build the app for the simulator
ssh mac 'source ~/ios-build-env.sh 2>/dev/null; export JAVA_HOME=$HOME/devtools/jdk17/Contents/Home; export ANDROID_HOME=$HOME/devtools/android-sdk; export PATH=$JAVA_HOME/bin:$HOME/devtools/xcodegen/bin:$PATH; cd ~/sm-badge/apps/iosApp && rm -f local.properties && xcodegen generate && ( nohup xcodebuild -project Supermux.xcodeproj -scheme Supermux -sdk iphonesimulator -configuration Debug -destination "generic/platform=iOS Simulator" ARCHS=arm64 EXCLUDED_ARCHS=x86_64 CODE_SIGN_IDENTITY="-" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="" PROVISIONING_PROFILE_SPECIFIER="" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES build >~/sm-badge-build.log 2>&1 </dev/null & )'
# poll until done:
ssh mac 'grep -qE "BUILD (SUCCEEDED|FAILED)" ~/sm-badge-build.log && tail -25 ~/sm-badge-build.log'
```
Expected: `** BUILD SUCCEEDED **`. If it fails on the `SessionInfo` copy in Task 7, read the error, fix the `doCopy`/`copy` signature on the Mac, copy the corrected `BrokerSession.swift` back to this host, and re-commit.

- [ ] **Step 5: Commit any iOS fixes from the Mac build**

If Task 7/8 needed adjustment to compile, pull the corrected files back and commit:
```bash
git add apps/iosApp/Supermux/Broker/BrokerSession.swift apps/iosApp/Supermux/Chat/SessionChrome.swift apps/iosApp/Supermux/Chat/ChatView.swift
git commit -m "fix(ios): adjust session_git copy / navSubtitle to compile on device toolchain"
```

---

## Self-Review

**Spec coverage:**
- Shared `gitBadge` formatter + tests → Task 1. ✓
- Local `+/−` vs remote `↑↓`, dirty `·N`, `✓`, `unpublished`, `kind`/`tone` → Task 1 (and mirrored in Task 2 for web). ✓
- Web correction (`gitBadge.ts` + tests + components) → Tasks 2-3. ✓
- iOS: GitBadgeView, session-row badge, mode-aware navSubtitle, `session_git` handler → Tasks 7-8. ✓
- Android: GitBadge composable, session-row badge, mode-aware header, `session_git` reducer → Tasks 4-6. ✓
- Live updates (`session_git` handlers) → Task 4 (Android), Task 7 (iOS). ✓
- Testing (shared jvmTest primary; web bun test; Android gradle; iOS Mac build) → Tasks 1, 2, 9. ✓
- Native header display-only (no sync actions) → honored; Tasks 6/8 only render text/icon. ✓

**Placeholder scan:** No TBD/TODO. The one "resolve on the Mac" note (Task 7 SessionInfo copy signature) is a genuine toolchain-specific detail with the exact semantic requirement stated + a verification command — not a vague placeholder.

**Type consistency:** `gitBadge` / `GitBadge` / `GitBadgeKind{BASE,REMOTE,UNPUBLISHED,INSYNC}` / `GitBadgeTone{ACTIVE,MUTED}` used identically across Task 1 (Kotlin), Task 5/6 (Android `GitBadgeKind.BASE`), Task 7/8 (Swift `.base`/`.muted` via SKIE). Web `kind` union `"base"|"remote"|"unpublished"|"insync"` (Task 2) matches the components' checks (Task 3). `GitBadgeRow` (Android) and `GitBadgeView` (iOS) names are used consistently between their defining and rendering tasks.
