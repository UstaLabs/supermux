# Glanceable session "finished vs not" status — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the session-list's inline git counts with a glanceable two-state status — 🟢 ✓ done (in `dev`, clean) vs 🟡 ⎇ not-done (unmerged or uncommitted) — shown as a leading colored rail + icon, so you can tell finished-vs-not at a glance.

**Architecture:** A pure shared helper `sessionDoneState(GitLiteStatusDto?)` derives the two-state status from the *existing* `ahead`/`dirty` fields (the comparison is already live-vs-`dev` — no backend change). Each platform's session row renders a leading colored rail + status icon from it and drops the inline counts (which move to a tooltip/header). One shared Kotlin helper for iOS (SKIE) + Android; web mirrors it in TS.

**Tech Stack:** Kotlin Multiplatform (`commonMain`/`commonTest`, kotlin.test), Vue 3 + TS (`bun test`), Jetpack Compose, SwiftUI + SKIE. Gradle root `apps/` (`:shared`, `:android`).

**Worktree:** branch `mux/supermux-18` at `/home/ahmet/.mux/worktrees/supermux-3962b5bf/c7b124d4-773a-4b23-bdcc-4681736c99bd` (already synced to dev tip; spec committed `3d6685a`). All paths relative to that root.

**Colors (all platforms):** done = green, not-done = amber. **Icons:** done = check (web lucide `Check` / iOS SF `checkmark` / Android `R.drawable.ic_check`), not-done = branch (web `GitBranch` / iOS `arrow.triangle.branch` / Android `R.drawable.ic_git_branch`) — both already available.

**iOS caveat:** iOS Swift can't compile on this Linux host. Task 4 writes Swift; Task 5 builds it on the remote Mac (Watch target excluded, per the prior recipe).

---

### Task 1: Shared `sessionDoneState` helper + tests

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt` (append)
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/proto/GitBadgeTest.kt` (append)

- [ ] **Step 1: Add failing tests** — append inside the existing `class GitBadgeTest { … }` in `GitBadgeTest.kt` (before its closing brace):
```kotlin
    @Test fun done_when_in_dev_and_clean() {
        assertEquals(SessionDoneState.DONE, sessionDoneState(GitLiteStatusDto(mode = "base", compareRef = "dev")))
    }

    @Test fun not_done_when_ahead() {
        assertEquals(SessionDoneState.NOT_DONE, sessionDoneState(GitLiteStatusDto(mode = "base", compareRef = "dev", ahead = 2)))
    }

    @Test fun not_done_when_dirty() {
        assertEquals(SessionDoneState.NOT_DONE, sessionDoneState(GitLiteStatusDto(mode = "base", compareRef = "dev", dirty = 1)))
    }

    @Test fun behind_only_is_still_done() {
        assertEquals(SessionDoneState.DONE, sessionDoneState(GitLiteStatusDto(mode = "base", compareRef = "dev", behind = 3)))
    }

    @Test fun no_indicator_for_null_or_remote() {
        assertNull(sessionDoneState(null))
        assertNull(sessionDoneState(GitLiteStatusDto(mode = "remote", compareRef = "origin/x", ahead = 1)))
    }
```

- [ ] **Step 2: Run, verify it FAILS to compile** (`sessionDoneState`/`SessionDoneState` unresolved):
`cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.proto.GitBadgeTest"`

- [ ] **Step 3: Implement** — append to the END of `GitBadge.kt`:
```kotlin

/** Glanceable finished-vs-not state for the session list. */
enum class SessionDoneState { DONE, NOT_DONE }

/**
 * Two-state "is this session finished?" for the list. Worktree (base-mode) sessions only:
 * DONE when its commits are in the base branch (ahead == 0) and the tree is clean (dirty == 0);
 * NOT_DONE when there are unmerged commits (ahead > 0) or uncommitted changes (dirty > 0).
 * `behind` alone does NOT make it not-done. Returns null when no indicator applies
 * (non-repo session, or remote/plain-repo mode).
 */
fun sessionDoneState(git: GitLiteStatusDto?): SessionDoneState? {
    if (git == null || git.mode != "base") return null
    return if (git.ahead == 0 && git.dirty == 0) SessionDoneState.DONE else SessionDoneState.NOT_DONE
}
```

- [ ] **Step 4: Run, verify PASS:** `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.proto.GitBadgeTest"` (13 tests total: 8 existing + 5 new).

- [ ] **Step 5: Commit:**
```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt apps/shared/src/commonTest/kotlin/dev/supermux/proto/GitBadgeTest.kt
git commit -m "feat(shared): sessionDoneState — two-state done/not-done for the session list"
```

---

### Task 2: Web — TS mirror + session-row status (rail + icon)

**Files:**
- Modify: `src/web-app/src/lib/gitBadge.ts` (append the mirror)
- Test: `src/web-app/src/lib/gitBadge.test.ts` (append)
- Modify: `src/web-app/src/components/SessionRow.vue`

- [ ] **Step 1: Add a failing test** — append to `src/web-app/src/lib/gitBadge.test.ts`:
```typescript
import { sessionDoneState } from "./gitBadge"

test("sessionDoneState: done when in dev + clean", () => {
  expect(sessionDoneState({ mode: "base", compareRef: "dev", ahead: 0, behind: 0, dirty: 0, computedAt: 0 })).toBe("done")
})
test("sessionDoneState: not-done when ahead or dirty", () => {
  expect(sessionDoneState({ mode: "base", compareRef: "dev", ahead: 2, behind: 0, dirty: 0, computedAt: 0 })).toBe("not-done")
  expect(sessionDoneState({ mode: "base", compareRef: "dev", ahead: 0, behind: 0, dirty: 1, computedAt: 0 })).toBe("not-done")
})
test("sessionDoneState: behind-only still done", () => {
  expect(sessionDoneState({ mode: "base", compareRef: "dev", ahead: 0, behind: 3, dirty: 0, computedAt: 0 })).toBe("done")
})
test("sessionDoneState: null for undefined or remote", () => {
  expect(sessionDoneState(undefined)).toBeNull()
  expect(sessionDoneState({ mode: "remote", compareRef: "origin/x", ahead: 1, behind: 0, dirty: 0, computedAt: 0 })).toBeNull()
})
```

- [ ] **Step 2: Run, verify FAIL:** `bun test src/web-app/src/lib/gitBadge.test.ts` (sessionDoneState undefined).

- [ ] **Step 3: Implement the mirror** — append to `src/web-app/src/lib/gitBadge.ts`:
```typescript

export type SessionDoneState = "done" | "not-done"

/** Two-state session-list status; mirrors the shared Kotlin `sessionDoneState`. */
export function sessionDoneState(git: GitLiteStatus | undefined): SessionDoneState | null {
  if (!git || git.mode !== "base") return null
  return git.ahead === 0 && git.dirty === 0 ? "done" : "not-done"
}
```

- [ ] **Step 4: Run, verify PASS:** `bun test src/web-app/src/lib/gitBadge.test.ts` (10 total).

- [ ] **Step 5: Update SessionRow.vue** — three edits.

(a) Imports (line 6-8 area) — add `Check` to the lucide import and the helper. Replace:
```typescript
import { gitBadge } from "@/lib/gitBadge"
import SessionAvatar from "@/components/SessionAvatar.vue"
import { GitBranch } from "lucide-vue-next"
```
with:
```typescript
import { gitBadge, sessionDoneState } from "@/lib/gitBadge"
import SessionAvatar from "@/components/SessionAvatar.vue"
import { GitBranch, Check } from "lucide-vue-next"
```

(b) Add a `done` computed next to the existing `badge` computed (after line 43):
```typescript
const done = computed(() => sessionDoneState(gitStatus.get(props.id)))
```

(c) Add a left-rail accent on the row and replace the inline counts with the status icon.

In the `<a>` `:class` array (lines 87-93), append a status border accent as a new array entry after the `props.reserveMenuSpace …` line:
```vue
      props.reserveMenuSpace ? 'pl-3 pr-9 py-2.5' : 'px-3 py-2.5',
      done === 'done' ? 'border-l-2 border-l-green-500' : done === 'not-done' ? 'border-l-2 border-l-amber-500' : '',
```

Then replace the inline counts badge (lines 121-126):
```vue
          <span
            v-if="badge"
            :title="badge.title"
            class="inline-flex shrink-0 items-center gap-0.5 font-mono text-[10px] tabular-nums"
            :class="badge.tone === 'muted' ? 'text-muted-foreground/45' : 'text-muted-foreground/80'"
          ><GitBranch v-if="badge.kind === 'base'" class="size-2.5 shrink-0" />{{ badge.text }}</span>
```
with the status icon (counts move to its tooltip):
```vue
          <component
            v-if="done"
            :is="done === 'done' ? Check : GitBranch"
            :title="badge?.title"
            class="size-3.5 shrink-0"
            :class="done === 'done' ? 'text-green-600' : 'text-amber-500'"
          />
```

- [ ] **Step 6: Typecheck + build:** `cd src/web-app && bun run build` (expect `vue-tsc` clean + `vite build` success). If `border-l-green-500`/`text-green-600` are purged by the theme, substitute the project's nearest green/amber utility and note it.

- [ ] **Step 7: Commit:**
```bash
git add src/web-app/src/lib/gitBadge.ts src/web-app/src/lib/gitBadge.test.ts src/web-app/src/components/SessionRow.vue
git commit -m "feat(web): glanceable done/not-done status (rail + icon) on the session row"
```

---

### Task 3: Android — status rail + icon on the session row

**Files:**
- Create: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionStatusRail.kt`
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt`

- [ ] **Step 1: Create the status-rail composable** — `SessionStatusRail.kt`:
```kotlin
package dev.supermux.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.supermux.android.R
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionDoneState
import dev.supermux.proto.sessionDoneState

private val DoneGreen = Color(0xFF16A34A)
private val NotDoneAmber = Color(0xFFF59E0B)

/**
 * Leading session status: a colored rail + check/branch icon. Renders an empty
 * fixed-width spacer (for alignment) when there's no status (non-worktree session).
 */
@Composable
fun SessionStatusRail(git: GitLiteStatusDto?, unread: Boolean, modifier: Modifier = Modifier) {
    val state = sessionDoneState(git)
    if (state == null) {
        // Keep avatar alignment consistent; still show the unread cue as a thin bar.
        Box(
            modifier
                .width(4.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (unread) Color(0xFF14B8A6).copy(alpha = 0.7f) else Color.Transparent),
        )
        return
    }
    val color = if (state == SessionDoneState.DONE) DoneGreen else NotDoneAmber
    val icon = if (state == SessionDoneState.DONE) R.drawable.ic_check else R.drawable.ic_git_branch
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
    }
}
```

- [ ] **Step 2: Wire it into SessionRow + drop the inline badge + move unread to bold name.**

(a) Replace the leading unread block (SessionListScreen.kt lines 201-215):
```kotlin
        // Subtle teal left-edge unread indicator
        if (hasUnread) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(cs.primary.copy(alpha = 0.7f))
                    .align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(Space.sm))
        } else {
            // Reserve same horizontal space so avatar aligns consistently
            Spacer(Modifier.width(4.dp + Space.sm))
        }
```
with the status rail:
```kotlin
        SessionStatusRail(git = s.git, unread = hasUnread, modifier = Modifier.align(Alignment.CenterVertically))
        Spacer(Modifier.width(Space.sm))
```

(b) Make the name bold when unread — replace the name `Text(...)` (lines 230-238):
```kotlin
                Text(
                    s.name,
                    color = cs.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
```
with:
```kotlin
                Text(
                    s.name,
                    color = cs.onSurface,
                    fontSize = 15.sp,
                    fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
```

(c) Remove the inline git badge block (lines 267-271):
```kotlin
            // Git status badge (worktree-vs-base or branch-vs-remote divergence).
            if (s.git != null) {
                Spacer(Modifier.height(2.dp))
                GitBadgeRow(s.git)
            }
```
Delete those 5 lines entirely (the status rail replaces it).

- [ ] **Step 3: Compile:** `cd apps && ./gradlew :android:compileDebugKotlin` → BUILD SUCCESSFUL. (`GitBadgeRow` in `GitBadge.kt` is now unused by the row but stays — it's still useful and harmless; leave it.)

- [ ] **Step 4: Commit:**
```bash
git add apps/android/src/main/kotlin/dev/supermux/android/session/SessionStatusRail.kt apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt
git commit -m "feat(android): glanceable done/not-done status rail on the session row"
```

---

### Task 4: iOS — status rail + icon on the session row (write; Mac-verified in Task 5)

**Files:**
- Create: `apps/iosApp/Supermux/Sessions/SessionStatusRail.swift`
- Modify: `apps/iosApp/Supermux/Sessions/SessionsListView.swift` (SessionRow, lines 183-202)

**iOS does not compile on Linux — write per the patterns; Task 5 verifies on the Mac.** The shared helper is exposed via SKIE as `GitBadgeKt.sessionDoneState(git:)` returning `SessionDoneState?` (cases `.done` / `.notDone`).

- [ ] **Step 1: Create the rail view** — `SessionStatusRail.swift`:
```swift
import SwiftUI
import Shared

/// Leading session status: a colored rail + check/branch icon (green = done, amber = not-done).
/// Renders nothing for sessions without a worktree status.
struct SessionStatusRail: View {
    let git: GitLiteStatusDto?

    var body: some View {
        if let state = GitBadgeKt.sessionDoneState(git: git) {
            let color: Color = state == .done ? .green : .orange
            HStack(spacing: 4) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(color)
                    .frame(width: 3, height: 20)
                Image(systemName: state == .done ? "checkmark" : "arrow.triangle.branch")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(color)
            }
        }
    }
}
```

- [ ] **Step 2: Wire into SessionRow + drop the inline badge.** Replace the `SessionRow` body (SessionsListView.swift lines 183-202):
```swift
    var body: some View {
        HStack(spacing: 11) {
            AgentLogo(agent: session.agent)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(session.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if working { ProgressView().controlSize(.mini) }
                    if muted { Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(.tertiary) }
                    Spacer(minLength: 0)
                }
                HStack(spacing: 6) {
                    Text(preview ?? session.agent)
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Spacer(minLength: 0)
                    GitBadgeView(git: session.git)
                }
            }
        }
        .padding(.vertical, 3)
    }
```
with (leading rail before the logo; subtitle back to plain preview):
```swift
    var body: some View {
        HStack(spacing: 8) {
            SessionStatusRail(git: session.git)
            AgentLogo(agent: session.agent)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(session.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if working { ProgressView().controlSize(.mini) }
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
(`GitBadgeView` stays in the file — still used by nothing in the row now, but it's harmless and was also intended for reuse; leave the type defined.)

- [ ] **Step 3: Commit (build verified in Task 5):**
```bash
git add apps/iosApp/Supermux/Sessions/SessionStatusRail.swift apps/iosApp/Supermux/Sessions/SessionsListView.swift
git commit -m "feat(ios): glanceable done/not-done status rail on the session row"
```

---

### Task 5: Full verification (local: shared/web/Android; remote Mac: iOS)

**Files:** none.

- [ ] **Step 1: Shared tests:** `cd apps && ./gradlew :shared:jvmTest` → BUILD SUCCESSFUL (GitBadgeTest 13).
- [ ] **Step 2: Web:** `bun test src/web-app/src/lib/gitBadge.test.ts` (10 pass), then `cd src/web-app && bun run build` (vue-tsc clean + vite build).
- [ ] **Step 3: Android:** `cd apps && ./gradlew :android:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 4: iOS on the remote Mac** — sync + build for the simulator with the Watch target excluded (ad-hoc signed; the App-Group entitlement requires signing, so do NOT use `CODE_SIGNING_ALLOWED=NO`):
```bash
cd /home/ahmet/.mux/worktrees/supermux-3962b5bf/c7b124d4-773a-4b23-bdcc-4681736c99bd
tar cf - --exclude .git --exclude build --exclude .gradle --exclude node_modules apps \
  | ssh mac 'rm -rf ~/sm-status && mkdir -p ~/sm-status && tar xf - -C ~/sm-status'
ssh mac 'export JAVA_HOME=$HOME/devtools/jdk17/Contents/Home ANDROID_HOME=$HOME/devtools/android-sdk; export PATH=$JAVA_HOME/bin:$HOME/devtools/xcodegen/bin:$PATH
  P=~/sm-status/apps/iosApp/project.yml
  perl -0pi -e "s/      - target: SupermuxWatch\n        embed: true\n//" "$P"
  cd ~/sm-status/apps && rm -f local.properties
  cd ~/sm-status/apps/iosApp && xcodegen generate >/dev/null
  rm -f ~/sm-status-build.log
  ( nohup xcodebuild -project Supermux.xcodeproj -scheme Supermux -sdk iphonesimulator -configuration Debug -destination "generic/platform=iOS Simulator" ARCHS=arm64 EXCLUDED_ARCHS=x86_64 CODE_SIGN_IDENTITY="-" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="" PROVISIONING_PROFILE_SPECIFIER="" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES build >~/sm-status-build.log 2>&1 </dev/null & )
  echo LAUNCHED'
# poll until done:
ssh mac 'grep -qE "BUILD (SUCCEEDED|FAILED)" ~/sm-status-build.log && tail -20 ~/sm-status-build.log'
```
Expect `** BUILD SUCCEEDED **`. If a SKIE signature errors, read it, fix on the Mac, copy the file back, re-commit.

---

## Self-Review

**Spec coverage:** two-state model → Task 1 (shared) + Task 2 (web mirror). Leading rail + icon, counts→tooltip → Tasks 2/3/4. Worktree-only / null-for-remote → `sessionDoneState`. Live-vs-dev → no backend change (uses existing fields). Unread deconflict → Task 3 (Android: rail takes the slot, unread→bold name; web/iOS unaffected). Testing → Tasks 1,2,5. ✓

**Placeholder scan:** none. The Tailwind-color fallback note (Task 2 Step 6) is a concrete contingency, not a placeholder.

**Type consistency:** `sessionDoneState` / `SessionDoneState{DONE,NOT_DONE}` (Kotlin) ↔ `"done"|"not-done"` (TS) ↔ SKIE `.done`/`.notDone` (Swift) used consistently. `SessionStatusRail` is the component name in both iOS (View) and Android (composable). Icons/colors consistent (check+green / branch+amber) across all three. The inline `GitBadgeView`/`GitBadgeRow` are left defined (harmless) but removed from the rows.
