# Unified per-session state indicator — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make each session row's leading element one "session state" indicator — working spinner (top priority) > worktree ✓/⎇/neutral (vs `dev`, with a pristine→neutral state) > remote ☁️ + push/pull counts (vs origin) > neutral — and remove the agent avatar from the list row.

**Architecture:** The broker adds a `touched` flag to `GitLiteStatus` (computed from the already-recorded `base_commits`), so a never-committed worktree (neutral) is distinguishable from a finished-and-merged one (✓). A shared Kotlin `sessionStatus(git) → {kind, level}` helper (mirrored in web TS) drives the icon/color; the working state comes from each platform's agent-phase, composed `working ? spinner : status`. The avatar leaves the list row (stays in the chat header).

**Tech Stack:** Bun/TS broker, KMP shared (kotlin.test), Vue 3 + TS (`bun test`), Jetpack Compose, SwiftUI + SKIE. Gradle root `apps/`.

**Worktree:** `mux/supermux-18` @ `66a97ed` at `/home/ahmet/.mux/worktrees/supermux-3962b5bf/c7b124d4-773a-4b23-bdcc-4681736c99bd`. Spec: `docs/superpowers/specs/2026-06-25-unified-session-state-indicator-design.md`.

**iOS caveat:** iOS won't compile on this Linux host; Task 7 builds it on the remote Mac (Watch excluded).

---

### Task 1: Broker — `touched` flag on GitLiteStatus

**Files:**
- Modify: `src/core/worktree/lite-status.ts`
- Modify: `src/core/worktree/git-status-service.ts`
- Modify: `src/main.ts`
- Test: `src/core/worktree/lite-status.test.ts`

- [ ] **Step 1: Write a failing test.** Append to `src/core/worktree/lite-status.test.ts` (create it if absent, copying the import/setup style of a sibling test — it shells out to a temp git repo). Add a test that builds a worktree off a base, makes a commit, merges it into the base, and asserts `touched === true` while `ahead === 0`; and a pristine worktree asserts `touched === false`:
```typescript
import { test, expect } from "bun:test"
import { execFileSync } from "node:child_process"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { computeLiteStatus } from "./lite-status"

function git(cwd: string, ...args: string[]) { return execFileSync("git", args, { cwd, encoding: "utf-8" }).trim() }

test("touched: true after a commit even once merged into base; false when pristine", () => {
  const root = mkdtempSync(join(tmpdir(), "sm-lite-"))
  try {
    git(root, "init", "-q", "-b", "dev")
    git(root, "config", "user.email", "t@t"); git(root, "config", "user.name", "t")
    execFileSync("git", ["commit", "-q", "--allow-empty", "-m", "base"], { cwd: root })
    const baseSha = git(root, "rev-parse", "HEAD")
    // pristine worktree off dev
    const wtP = join(root, "wt-pristine")
    git(root, "worktree", "add", "-q", "-b", "s-pristine", wtP, "dev")
    // worktree that commits then merges into dev
    const wtW = join(root, "wt-work")
    git(root, "worktree", "add", "-q", "-b", "s-work", wtW, "dev")
    execFileSync("git", ["commit", "-q", "--allow-empty", "-m", "work"], { cwd: wtW })
    git(root, "checkout", "-q", "dev"); git(root, "merge", "-q", "--no-ff", "-m", "merge", "s-work"); git(root, "checkout", "-q", "-")
    return Promise.all([
      computeLiteStatus({ workdir: wtP, repo_root: root, base_branch: "dev", session_branch: "s-pristine", base_commit: baseSha }),
      computeLiteStatus({ workdir: wtW, repo_root: root, base_branch: "dev", session_branch: "s-work", base_commit: baseSha }),
    ]).then(([p, w]) => {
      expect(p?.touched).toBe(false)
      expect(w?.touched).toBe(true)
      expect(w?.ahead).toBe(0) // merged → not ahead of dev, but touched
    })
  } finally { rmSync(root, { recursive: true, force: true }) }
})
```

- [ ] **Step 2: Run it, see it fail.** `bun test src/core/worktree/lite-status.test.ts` → FAIL (`touched` undefined; `base_commit` not on the input type).

- [ ] **Step 3: Add `touched` to the type + input + computation.** In `src/core/worktree/lite-status.ts`:
  - Add to `GitLiteStatus` (after `unpublished?`):
```typescript
  touched?: boolean        // base mode: worktree has commits since creation (rev-list base_commit..HEAD > 0)
```
  - Add to `LiteStatusInput` (after `session_branch?`):
```typescript
  base_commit?: string | null   // HEAD SHA at worktree creation (from session base_commits); for `touched`
```
  - In `computeLiteStatus`, replace the base-mode `return` block:
```typescript
  if (worktreeBacked) {
    const base = s.base_branch as string
    const ab = await runGit(cwd, ["rev-list", "--count", "--left-right", `${base}...HEAD`])
    if (!ab.ok) return null
    const [b, a] = ab.out.split(/\s+/)
    const ahead = Number(a) || 0
    const dirty = await dirtyCount(cwd)
    let touched: boolean
    if (s.base_commit) {
      const t = await runGit(cwd, ["rev-list", "--count", `${s.base_commit}..HEAD`])
      touched = t.ok && (Number(t.out) || 0) > 0
    } else {
      touched = ahead > 0   // fallback for sessions created before base_commit was recorded
    }
    return { mode: "base", compareRef: base, ahead, behind: Number(b) || 0, dirty, touched, computedAt: now }
  }
```

- [ ] **Step 4: Run the test, see it pass.** `bun test src/core/worktree/lite-status.test.ts` → PASS.

- [ ] **Step 5: Plumb `base_commit` through the service + main.** 
  - `src/core/worktree/git-status-service.ts`: add to `ServiceSession` (after `session_branch?`): `  base_commit?: string | null`. And add `touched` to `sameStatus` so a touched-change broadcasts — change the final return to:
```typescript
  return a.mode === b.mode && a.compareRef === b.compareRef && a.ahead === b.ahead
    && a.behind === b.behind && a.dirty === b.dirty && !!a.unpublished === !!b.unpublished
    && !!a.touched === !!b.touched
```
  - `src/main.ts` `gitServiceSessions()`: pass the session's creation SHA. Replace the `.map(...)` body:
```typescript
function gitServiceSessions(): ServiceSession[] {
  return registry.listVisible().map((s) => ({
    id: s.id, workdir: s.workdir,
    repo_root: s.repo_root, base_branch: s.base_branch, session_branch: s.session_branch,
    // base_commits is a { repoRelPath -> HEAD-at-creation } map; a worktree session is single-repo,
    // so its base commit is the sole value. Used for the `touched` (pristine-vs-did-work) flag.
    base_commit: s.base_commits ? Object.values(s.base_commits)[0] ?? null : null,
  }))
}
```

- [ ] **Step 6: Typecheck + commit.** `bun run typecheck` (or `tsc --noEmit`) — expect clean.
```bash
git add src/core/worktree/lite-status.ts src/core/worktree/lite-status.test.ts src/core/worktree/git-status-service.ts src/main.ts
git commit -m "feat(broker): touched flag on GitLiteStatus (pristine vs did-work, from base_commits)"
```

---

### Task 2: Shared — `sessionStatus` helper + `GitLiteStatusDto.touched`

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/proto/GitBadgeTest.kt`

- [ ] **Step 1: Add `touched` to the DTO.** In `Frames.kt`, the `GitLiteStatusDto` — add a field before `computedAt`:
```kotlin
    val touched: Boolean = false,
```
(Final class: `mode, compareRef, ahead, behind, dirty, unpublished, touched, computedAt`.)

- [ ] **Step 2: Write failing tests.** Append inside `class GitBadgeTest` in `GitBadgeTest.kt`:
```kotlin
    @Test fun status_worktree_pristine() {
        val s = sessionStatus(GitLiteStatusDto(mode = "base", compareRef = "dev", touched = false))
        assertEquals(SessionStatusKind.WORKTREE, s?.kind); assertEquals(SessionStatusLevel.PRISTINE, s?.level)
    }
    @Test fun status_worktree_done_when_touched_and_clean() {
        val s = sessionStatus(GitLiteStatusDto(mode = "base", compareRef = "dev", touched = true))
        assertEquals(SessionStatusLevel.DONE, s?.level)
    }
    @Test fun status_worktree_not_done_when_ahead_or_dirty() {
        assertEquals(SessionStatusLevel.NOT_DONE, sessionStatus(GitLiteStatusDto(mode = "base", ahead = 1, touched = true))?.level)
        assertEquals(SessionStatusLevel.NOT_DONE, sessionStatus(GitLiteStatusDto(mode = "base", dirty = 1))?.level)
    }
    @Test fun status_remote_synced_vs_not() {
        assertEquals(SessionStatus(SessionStatusKind.REMOTE, SessionStatusLevel.DONE),
            sessionStatus(GitLiteStatusDto(mode = "remote", compareRef = "origin/x")))
        assertEquals(SessionStatusLevel.NOT_DONE, sessionStatus(GitLiteStatusDto(mode = "remote", ahead = 1))?.level)
        assertEquals(SessionStatusLevel.NOT_DONE, sessionStatus(GitLiteStatusDto(mode = "remote", behind = 1))?.level)
        assertEquals(SessionStatusLevel.NOT_DONE, sessionStatus(GitLiteStatusDto(mode = "remote", unpublished = true))?.level)
    }
    @Test fun status_null_for_null_git() { assertNull(sessionStatus(null)) }
```

- [ ] **Step 3: Run, see it fail.** `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.proto.GitBadgeTest"` → FAIL (unresolved `sessionStatus`/`SessionStatusKind`/`SessionStatusLevel`).

- [ ] **Step 4: Implement.** Append to `GitBadge.kt`:
```kotlin

/** Which axis a session's status is measured on — picks the platform icon family. */
enum class SessionStatusKind { WORKTREE, REMOTE }

/** DONE = done (worktree, merged+clean) / synced (remote); NOT_DONE = not-done / not-synced;
 *  PRISTINE = worktree that has never committed (clean) — neutral, no ✓. */
enum class SessionStatusLevel { PRISTINE, DONE, NOT_DONE }

data class SessionStatus(val kind: SessionStatusKind, val level: SessionStatusLevel)

/**
 * Unified per-session status for the list indicator. null when no indicator applies (git == null).
 * Worktree (base): NOT_DONE if ahead or dirty; else DONE if [touched]; else PRISTINE (never committed).
 * Remote: DONE when synced both ways + clean + published; else NOT_DONE.
 */
fun sessionStatus(git: GitLiteStatusDto?): SessionStatus? {
    if (git == null) return null
    return if (git.mode == "base") {
        val level = when {
            git.ahead > 0 || git.dirty > 0 -> SessionStatusLevel.NOT_DONE
            git.touched -> SessionStatusLevel.DONE
            else -> SessionStatusLevel.PRISTINE
        }
        SessionStatus(SessionStatusKind.WORKTREE, level)
    } else {
        val synced = git.ahead == 0 && git.behind == 0 && git.dirty == 0 && git.unpublished != true
        SessionStatus(SessionStatusKind.REMOTE, if (synced) SessionStatusLevel.DONE else SessionStatusLevel.NOT_DONE)
    }
}
```
(Leave `sessionDoneState` in place — superseded by `sessionStatus`, harmless; the rows migrate to `sessionStatus` below.)

- [ ] **Step 5: Run, see it pass.** `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.proto.GitBadgeTest"` → PASS.

- [ ] **Step 6: Commit.**
```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt apps/shared/src/commonTest/kotlin/dev/supermux/proto/GitBadgeTest.kt
git commit -m "feat(shared): sessionStatus(kind,level) + GitLiteStatusDto.touched"
```

---

### Task 3: Web — `sessionStatus` mirror + unified row (remove avatar, working, cloud)

**Files:**
- Modify: `src/web-app/src/stores/gitStatus.ts`
- Modify: `src/web-app/src/lib/gitBadge.ts`
- Modify: `src/web-app/src/lib/gitBadge.test.ts`
- Modify: `src/web-app/src/components/SessionRow.vue`

- [ ] **Step 1: Add `touched` to the store type.** In `gitStatus.ts`, add to the `GitLiteStatus` interface (after `unpublished?`): `  touched?: boolean`.

- [ ] **Step 2: Add failing tests.** Append to `gitBadge.test.ts`:
```typescript
import { sessionStatus } from "./gitBadge"

test("sessionStatus worktree pristine/done/not-done", () => {
  expect(sessionStatus({ mode: "base", compareRef: "dev", ahead: 0, behind: 0, dirty: 0, touched: false, computedAt: 0 })).toEqual({ kind: "worktree", level: "pristine" })
  expect(sessionStatus({ mode: "base", compareRef: "dev", ahead: 0, behind: 0, dirty: 0, touched: true, computedAt: 0 })).toEqual({ kind: "worktree", level: "done" })
  expect(sessionStatus({ mode: "base", compareRef: "dev", ahead: 1, behind: 0, dirty: 0, touched: true, computedAt: 0 })).toEqual({ kind: "worktree", level: "not-done" })
})
test("sessionStatus remote synced/not", () => {
  expect(sessionStatus({ mode: "remote", compareRef: "origin/x", ahead: 0, behind: 0, dirty: 0, computedAt: 0 })).toEqual({ kind: "remote", level: "done" })
  expect(sessionStatus({ mode: "remote", compareRef: "origin/x", ahead: 0, behind: 1, dirty: 0, computedAt: 0 })).toEqual({ kind: "remote", level: "not-done" })
})
test("sessionStatus null", () => { expect(sessionStatus(undefined)).toBeNull() })
```

- [ ] **Step 3: Run, fail.** `bun test src/web-app/src/lib/gitBadge.test.ts` → FAIL.

- [ ] **Step 4: Implement the mirror.** Append to `gitBadge.ts`:
```typescript

export type SessionStatusKind = "worktree" | "remote"
export type SessionStatusLevel = "pristine" | "done" | "not-done"
export interface SessionStatus { kind: SessionStatusKind; level: SessionStatusLevel }

/** Mirrors the shared Kotlin `sessionStatus`. */
export function sessionStatus(git: GitLiteStatus | undefined): SessionStatus | null {
  if (!git) return null
  if (git.mode === "base") {
    const level: SessionStatusLevel =
      git.ahead > 0 || git.dirty > 0 ? "not-done" : git.touched ? "done" : "pristine"
    return { kind: "worktree", level }
  }
  const synced = git.ahead === 0 && git.behind === 0 && git.dirty === 0 && !git.unpublished
  return { kind: "remote", level: synced ? "done" : "not-done" }
}
```

- [ ] **Step 5: Run, pass.** `bun test src/web-app/src/lib/gitBadge.test.ts` → PASS.

- [ ] **Step 6: Rebuild the row.** In `SessionRow.vue`:
  - Imports: replace the lucide + gitBadge import lines with:
```typescript
import { gitBadge, sessionStatus } from "@/lib/gitBadge"
import { GitBranch, Check, Cloud, CloudCheck, Loader2Icon } from "lucide-vue-next"
```
  (Remove the `import SessionAvatar ...` line.)
  - Replace `const done = computed(...)` with:
```typescript
const status = computed(() => sessionStatus(gitStatus.get(props.id)))
```
  - In the `<a>` `:class` array, replace the `done === 'done' ? ...` accent line with one driven by `status`:
```vue
      status?.kind === 'worktree' && status.level === 'done' ? 'border-l-2 border-l-emerald-500'
        : status?.level === 'not-done' ? 'border-l-2 border-l-amber-500' : '',
```
  - Replace the avatar element (`<SessionAvatar ... />`) with the unified leading indicator (working spinner > status icon):
```vue
      <div class="flex w-5 shrink-0 items-center justify-center self-stretch pt-0.5">
        <Loader2Icon v-if="working" class="size-4 animate-spin text-primary" aria-label="working" />
        <Check v-else-if="status?.kind === 'worktree' && status.level === 'done'" class="size-4 text-emerald-400" />
        <GitBranch v-else-if="status?.kind === 'worktree' && status.level === 'not-done'" class="size-4 text-amber-500" />
        <CloudCheck v-else-if="status?.kind === 'remote' && status.level === 'done'" class="size-4 text-emerald-400" />
        <Cloud v-else-if="status?.kind === 'remote'" class="size-4 text-amber-500" />
        <span v-else class="size-1.5 rounded-full bg-muted-foreground/30" aria-hidden="true" />
      </div>
```
  - Replace the inline `<component v-if="done" .../>` status icon (in the subtitle row) — for **remote not-synced**, show the counts there; otherwise nothing (the leading indicator now carries the state):
```vue
          <span
            v-if="status?.kind === 'remote' && status.level === 'not-done' && badge"
            :title="badge.title"
            class="shrink-0 font-mono text-[10px] tabular-nums text-amber-500"
          >{{ badge.text }}</span>
```
  (Keep the unread `<span v-if="props.unread" ...>` pill as-is.)

- [ ] **Step 7: Build.** `cd src/web-app && bun run build` → vue-tsc clean + vite build. (If `CloudCheck` isn't exported by the installed lucide version, use `Cloud` for the synced state too, colored emerald — note the substitution.)

- [ ] **Step 8: Commit.**
```bash
git add src/web-app/src/stores/gitStatus.ts src/web-app/src/lib/gitBadge.ts src/web-app/src/lib/gitBadge.test.ts src/web-app/src/components/SessionRow.vue
git commit -m "feat(web): unified session-state indicator (working/cloud/pristine), drop list-row avatar"
```

---

### Task 4: Android — cloud drawables

**Files:**
- Create: `apps/android/src/main/res/drawable/ic_cloud_done.xml`
- Create: `apps/android/src/main/res/drawable/ic_cloud_off.xml`

- [ ] **Step 1: Create `ic_cloud_done.xml`** (Material "cloud_done", tintable):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF000000"
        android:pathData="M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM10,17l-3.5,-3.5 1.41,-1.41L10,14.17l4.59,-4.58L16,11l-6,6z" />
</vector>
```

- [ ] **Step 2: Create `ic_cloud_off.xml`** (Material "cloud_off", tintable):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF000000"
        android:pathData="M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3 0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96zM3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27zM7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z" />
</vector>
```

- [ ] **Step 3: Verify they compile** (resource link): `cd apps && ./gradlew :android:compileDebugKotlin` → BUILD SUCCESSFUL (drawables are validated during resource processing).

- [ ] **Step 4: Commit.**
```bash
git add apps/android/src/main/res/drawable/ic_cloud_done.xml apps/android/src/main/res/drawable/ic_cloud_off.xml
git commit -m "feat(android): cloud-done/cloud-off drawables for remote session status"
```

---

### Task 5: Android — unified rail (working + cloud + pristine), thread agentState, drop avatar

**Files:**
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionStatusRail.kt`
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt`
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionKeepAlive.kt` (pass `agentState` into `SessionListScreen`)

- [ ] **Step 1: Rewrite `SessionStatusRail.kt`** to the unified indicator:
```kotlin
package dev.supermux.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionStatusKind
import dev.supermux.proto.SessionStatusLevel
import dev.supermux.proto.gitBadge
import dev.supermux.proto.sessionStatus

private val DoneGreen = Color(0xFF16A34A)
private val NotDoneAmber = Color(0xFFF59E0B)

/**
 * Leading per-session state: working spinner (top priority), else the git/cloud status icon.
 * Worktree: ✓ done / ⎇ not-done / neutral pristine. Remote: cloud-done / cloud-off + ↑N ↓N counts.
 */
@Composable
fun SessionStatusRail(git: GitLiteStatusDto?, working: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            return@Row
        }
        val st = sessionStatus(git)
        when {
            st == null || (st.kind == SessionStatusKind.WORKTREE && st.level == SessionStatusLevel.PRISTINE) ->
                NeutralDot()
            st.kind == SessionStatusKind.WORKTREE && st.level == SessionStatusLevel.DONE ->
                StatusIcon(R.drawable.ic_check, DoneGreen)
            st.kind == SessionStatusKind.WORKTREE ->
                StatusIcon(R.drawable.ic_git_branch, NotDoneAmber)
            st.kind == SessionStatusKind.REMOTE && st.level == SessionStatusLevel.DONE ->
                StatusIcon(R.drawable.ic_cloud_done, DoneGreen)
            else -> {
                StatusIcon(R.drawable.ic_cloud_off, NotDoneAmber)
                val text = gitBadge(git)?.text
                if (!text.isNullOrEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(text, color = NotDoneAmber, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable private fun StatusIcon(res: Int, color: Color) {
    Icon(painterResource(res), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
}

@Composable private fun NeutralDot() {
    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
}
```

- [ ] **Step 2: Update `SessionRow` + `SessionListScreen`.** In `SessionListScreen.kt`:
  - Add an `isWorking` helper near the top of the file (after imports):
```kotlin
private fun isWorking(phase: String?): Boolean = phase == "thinking" || phase == "running"
```
  - Add an `agentState` parameter to `SessionListScreen` (the screen composable's signature) defaulting to empty:
```kotlin
    agentState: Map<String, dev.supermux.proto.AgentStatus?> = emptyMap(),
```
  - Pass it into each `SessionRow(...)` at the `items(...)` call site:
```kotlin
                items(g.sessions, key = { it.id.ifEmpty { it.name } }) { s ->
                    SessionRow(
                        s,
                        active = s.id == activeId,
                        preview = lastBySession[s.id],
                        working = isWorking(agentState[s.id]?.phase),
                        onClick = { onOpen(s.id) },
                        sharedScope = sharedScope,
                        animScope = animScope,
                    )
                }
```
  - Add `working: Boolean = false` to the `SessionRow` composable's parameters (after `preview`).
  - In `SessionRow`, replace the leading-rail + avatar block:
```kotlin
        SessionStatusRail(git = s.git, unread = hasUnread, modifier = Modifier.align(Alignment.CenterVertically))
        Spacer(Modifier.width(Space.sm))

        SessionAvatar(
            name = s.name,
            agent = s.agent,
            sessionId = s.id,
            sharedScope = sharedScope,
            animScope = animScope,
        )

        Spacer(Modifier.width(12.dp))
```
  with (rail now takes `working`, avatar removed):
```kotlin
        SessionStatusRail(git = s.git, working = working, modifier = Modifier.align(Alignment.CenterVertically))
        Spacer(Modifier.width(12.dp))
```
  (Remove the now-unused `SessionAvatar` import if the file no longer references it; `unread` is still used for the bold name — that stays.)

- [ ] **Step 3: Pass `agentState` from the host.** In `SessionKeepAlive.kt`, the `SessionKeepAlivePhoneHost` already receives `agentState: Map<String, AgentStatus?>`. At its call to `SessionListScreen(...)`, add the argument `agentState = agentState`. (TabletHost similarly if it renders `SessionListScreen`.)

- [ ] **Step 4: Compile.** `cd apps && ./gradlew :android:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**
```bash
git add apps/android/src/main/kotlin/dev/supermux/android/session/SessionStatusRail.kt apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt apps/android/src/main/kotlin/dev/supermux/android/session/SessionKeepAlive.kt
git commit -m "feat(android): unified session-state rail (working/cloud/pristine), thread agentState, drop list avatar"
```

---

### Task 6: iOS — unified rail (working + cloud + pristine), drop AgentLogo (Mac-verified in Task 7)

**Files:**
- Modify: `apps/iosApp/Supermux/Sessions/SessionStatusRail.swift`
- Modify: `apps/iosApp/Supermux/Sessions/SessionsListView.swift`

**iOS won't compile on Linux — write carefully; Task 7 builds it on the Mac.** SKIE exposes `GitBadgeKt.sessionStatus(git:)` → `SessionStatus?` with `.kind` (`SessionStatusKind`, `.worktree`/`.remote`) and `.level` (`SessionStatusLevel`, `.pristine`/`.done`/`.notDone`), and `GitBadgeKt.gitBadge(git:)` for the count text.

- [ ] **Step 1: Rewrite `SessionStatusRail.swift`:**
```swift
import SwiftUI
import Shared

/// Leading per-session state: working spinner (top priority), else the git/cloud status.
/// Worktree: ✓ done / ⎇ not-done / neutral pristine. Remote: cloud-done / cloud-off + ↑N ↓N counts.
struct SessionStatusRail: View {
    let git: GitLiteStatusDto?
    var working: Bool = false

    var body: some View {
        if working {
            ProgressView().controlSize(.mini)
        } else if let st = GitBadgeKt.sessionStatus(git: git) {
            switch (st.kind, st.level) {
            case (.worktree, .done):    icon("checkmark", .green)
            case (.worktree, .notDone): icon("arrow.triangle.branch", .orange)
            case (.worktree, .pristine): neutralDot
            case (.remote, .done):      icon("checkmark.icloud", .green)
            case (.remote, _):
                HStack(spacing: 4) {
                    Image(systemName: "icloud").font(.system(size: 11, weight: .semibold)).foregroundStyle(.orange)
                    if let text = GitBadgeKt.gitBadge(git: git)?.text, !text.isEmpty {
                        Text(text).font(.caption2.monospaced()).foregroundStyle(.orange)
                    }
                }
            default: neutralDot
            }
        } else {
            neutralDot
        }
    }

    private func icon(_ name: String, _ color: Color) -> some View {
        Image(systemName: name).font(.system(size: 11, weight: .semibold)).foregroundStyle(color)
    }
    private var neutralDot: some View {
        Circle().fill(Color.secondary.opacity(0.3)).frame(width: 6, height: 6)
    }
}
```
> Implementer note (Mac): verify the SKIE enum case spellings — `SessionStatusKind.worktree`/`.remote` and `SessionStatusLevel.pristine`/`.done`/`.notDone` (SKIE camelCases `NOT_DONE` → `.notDone`). Adjust from the compiler error if needed.

- [ ] **Step 2: Update `SessionRow` in `SessionsListView.swift`.** Replace the body's leading `HStack` content — pass `working` into the rail and remove `AgentLogo`:
```swift
    var body: some View {
        HStack(spacing: 8) {
            SessionStatusRail(git: session.git, working: working)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(session.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if muted { Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(.tertiary) }
                    Spacer(minLength: 0)
                }
                Text(preview ?? session.agent)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.vertical, 3)
    }
```
(The `working` computed property + the `ProgressView` that was in the name row are no longer needed there — the rail shows working now. Keep the `working` computed; it now feeds the rail.)

- [ ] **Step 3: Commit (built on the Mac in Task 7).**
```bash
git add apps/iosApp/Supermux/Sessions/SessionStatusRail.swift apps/iosApp/Supermux/Sessions/SessionsListView.swift
git commit -m "feat(ios): unified session-state rail (working/cloud/pristine), drop list-row AgentLogo"
```

---

### Task 7: Full verification

- [ ] **Step 1: Broker + shared + web (local).** `bun test src/core/worktree/lite-status.test.ts` (touched), `cd apps && ./gradlew :shared:jvmTest`, `bun test src/web-app/src/lib/gitBadge.test.ts`, `cd src/web-app && bun run build`. All green.
- [ ] **Step 2: Android.** `cd apps && ./gradlew :android:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 3: iOS on the remote Mac** (Watch excluded, ad-hoc signed):
```bash
cd /home/ahmet/.mux/worktrees/supermux-3962b5bf/c7b124d4-773a-4b23-bdcc-4681736c99bd
tar cf - --exclude .git --exclude build --exclude .gradle --exclude node_modules apps \
  | ssh mac 'rm -rf ~/sm-uni && mkdir -p ~/sm-uni && tar xf - -C ~/sm-uni'
ssh mac 'export JAVA_HOME=$HOME/devtools/jdk17/Contents/Home ANDROID_HOME=$HOME/devtools/android-sdk; export PATH=$JAVA_HOME/bin:$HOME/devtools/xcodegen/bin:$PATH
  perl -0pi -e "s/      - target: SupermuxWatch\n        embed: true\n//" ~/sm-uni/apps/iosApp/project.yml
  cd ~/sm-uni/apps && rm -f local.properties
  cd ~/sm-uni/apps/iosApp && xcodegen generate >/dev/null
  rm -f ~/sm-uni-build.log
  ( nohup xcodebuild -project Supermux.xcodeproj -scheme Supermux -sdk iphonesimulator -configuration Debug -destination "generic/platform=iOS Simulator" ARCHS=arm64 EXCLUDED_ARCHS=x86_64 CODE_SIGN_IDENTITY="-" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="" PROVISIONING_PROFILE_SPECIFIER="" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES build >~/sm-uni-build.log 2>&1 </dev/null & )
  echo LAUNCHED'
ssh mac 'grep -qE "BUILD (SUCCEEDED|FAILED)" ~/sm-uni-build.log && tail -20 ~/sm-uni-build.log'
```
Expect `** BUILD SUCCEEDED **`. If a SKIE enum case errors, fix from the message, copy the file back, re-commit.

---

## Self-Review

**Spec coverage:** working spinner (priority 1) → Tasks 3/5/6 (each rail). Worktree ✓/⎇/pristine → Task 2 (`sessionStatus`) + the `touched` flag (Task 1) + rendering (3/5/6). Remote cloud + counts → rails (3/5/6) using `sessionStatus` + `gitBadge` text. Avatar removed from list row → 3 (web), 5 (Android), 6 (iOS); kept in header/SidebarRail (untouched). `touched` from `base_commits` → Task 1. Tests → 1, 2, 3, 7. ✓

**Placeholder scan:** none. The `CloudCheck` fallback (Task 3) and SKIE-enum verify note (Task 6) are concrete contingencies.

**Type consistency:** `sessionStatus` / `SessionStatusKind{WORKTREE,REMOTE}` / `SessionStatusLevel{PRISTINE,DONE,NOT_DONE}` (Kotlin) ↔ `"worktree"|"remote"` / `"pristine"|"done"|"not-done"` (TS) ↔ SKIE `.worktree`/`.remote`, `.pristine`/`.done`/`.notDone` (Swift) used consistently. `touched` added to `GitLiteStatus` (TS broker), `GitLiteStatus` (TS web store), `GitLiteStatusDto` (Kotlin). `SessionStatusRail(git, working)` signature matches its callers in Tasks 5/6. `isWorking`/`working` phase check consistent (thinking/running).
