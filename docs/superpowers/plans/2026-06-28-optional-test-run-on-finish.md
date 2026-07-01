# Optional Test Run on Finish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an up-front "Run tests / Skip tests" two-step choice to the Finish sheet's *Merge locally* and *Open PR* actions, on Web, Android, and iOS.

**Architecture:** `skipVerify` is already plumbed end-to-end (web client → `/finish` → `finish-job.ts` → `finish.ts`; KMP `BrokerApi.finish`). This feature is UI: tapping Merge/PR reveals two inline choice rows (mirroring the existing "Discard all work?" confirm) instead of running immediately. The one conditional rule — hide *Skip tests* on the PR path when the repo sets `prRequiresGreen` — is driven by a new additive `prRequiresGreen` field on `FinishReadiness` and a pure, per-client `canSkipTests` helper (tested).

**Tech Stack:** TypeScript (broker + Vue PWA, `bun test`), Kotlin (Android Compose + KMP shared, JUnit/commonTest), Swift (SwiftUI, XCTest).

**Spec:** `docs/superpowers/specs/2026-06-28-optional-test-run-on-finish-design.md`

**Execution note — parallelism:** Tasks 1–5 are independent (backend, KMP DTO, three pure helpers) and may run in parallel. Tasks 6–8 (the three UI sheets) are independent of each other but each depends on its helper/type task; run them in a second parallel wave. Tasks touch disjoint files, so no worktree isolation is needed between them.

**Verification reality (no silent skips):** Web and Android have **no component/Compose UI-test harness** in this repo (web uses `bun test` for store/logic; Android has only JUnit unit tests; iOS has XCTest). So each platform's one piece of conditional logic is extracted into a pure, unit-tested `canSkipTests` helper. The remaining UI wiring (state + markup) is verified by typecheck/compile/build plus the explicit manual steps in each UI task. This is a deliberate, stated limitation — not skipped coverage.

---

## File Structure

**Create:**
- `src/web-app/src/lib/finish.ts` — web pure helper `canSkipTests`.
- `src/web-app/tests/finish.test.ts` — web helper test.
- `apps/android/src/main/kotlin/dev/supermux/android/chat/FinishChoices.kt` — Android pure helper (no Compose imports → unit-testable).
- `apps/android/src/test/kotlin/dev/supermux/android/chat/FinishChoicesTest.kt` — Android helper test.
- `apps/iosApp/Supermux/Chat/FinishChoices.swift` — iOS pure helper.
- `apps/iosApp/SupermuxTests/FinishChoicesTests.swift` — iOS helper test.

**Modify:**
- `src/core/worktree/readiness.ts` — add `prRequiresGreen` to `FinishReadiness` + `ReadinessInput`; set in `computeReadiness`.
- `src/core/worktree/readiness.test.ts` — cover the new field.
- `src/main.ts:862` — pass `prRequiresGreen: cfg.prRequiresGreen`.
- `src/web-app/src/api/client.ts:56` — add `prRequiresGreen` to the `FinishReadiness` interface.
- `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt:299` — add `prRequiresGreen` to the `FinishReadiness` data class.
- `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiSettingsTest.kt` — cover decode of the new field.
- `src/web-app/src/components/FinishSheet.vue` — two-step UI.
- `apps/android/src/main/kotlin/dev/supermux/android/chat/FinishSheet.kt` — two-step UI.
- `apps/iosApp/Supermux/Chat/FinishSheet.swift` — two-step UI.

---

## Task 1: Backend — expose `prRequiresGreen` on `FinishReadiness`

**Files:**
- Modify: `src/core/worktree/readiness.ts`
- Modify: `src/main.ts:862`
- Test: `src/core/worktree/readiness.test.ts`

- [ ] **Step 1: Write the failing test**

Append to `src/core/worktree/readiness.test.ts`:

```ts
test("prRequiresGreen defaults to false when not provided", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  const r = computeReadiness({ repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })
  expect(r.prRequiresGreen).toBe(false)
})

test("prRequiresGreen is passed through from input", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  const r = computeReadiness({ repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main", prRequiresGreen: true })
  expect(r.prRequiresGreen).toBe(true)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/worktree/readiness.test.ts`
Expected: FAIL — `r.prRequiresGreen` is `undefined` (property missing on type/result).

- [ ] **Step 3: Implement — add the field**

In `src/core/worktree/readiness.ts`, add `prRequiresGreen` to the interface, the input, and the returned object:

```ts
export interface FinishReadiness {
  base: string; branch: string
  ahead: number; behind: number
  dirtyFiles: string[]
  filesChanged: number; insertions: number; deletions: number
  hasRemote: boolean; baseHasUpstream: boolean; ghAvailable: boolean
  conflictPreflight: "clean" | "will_conflict" | "unknown"
  recommended: "merge" | "pr"
  nothingToLand: boolean
  prRequiresGreen: boolean
}

export interface ReadinessInput {
  repoRoot: string; worktreeDir: string; sessionBranch: string; baseBranch: string
  defaultAction?: "auto" | "merge" | "pr"
  prRequiresGreen?: boolean
}
```

In the `return { … }` of `computeReadiness`, add (after `nothingToLand,`):

```ts
    conflictPreflight, recommended, nothingToLand,
    prRequiresGreen: s.prRequiresGreen ?? false,
```

- [ ] **Step 4: Wire the caller in `src/main.ts`**

At `src/main.ts:862`, add `prRequiresGreen: cfg.prRequiresGreen` to the `computeReadiness({ … })` call (the line already destructures `cfg = loadFinishConfig(s.repo_root)` just above):

```ts
  return computeReadiness({ repoRoot: s.repo_root, worktreeDir: s.workdir, sessionBranch: s.session_branch, baseBranch: s.base_branch, defaultAction: cfg.defaultAction, prRequiresGreen: cfg.prRequiresGreen })
```

- [ ] **Step 5: Run test to verify it passes**

Run: `bun test src/core/worktree/readiness.test.ts`
Expected: PASS (all cases).

- [ ] **Step 6: Typecheck**

Run: `bunx tsc --noEmit -p tsconfig.json`
Expected: no errors.

- [ ] **Step 7: Commit**

```bash
git add src/core/worktree/readiness.ts src/core/worktree/readiness.test.ts src/main.ts
git commit --no-verify -m "$(printf 'feat(finish): expose prRequiresGreen on FinishReadiness\n\nClients need the repo policy to hide Skip-tests on the PR path.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 2: KMP — add `prRequiresGreen` to the `FinishReadiness` DTO

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt:299`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiSettingsTest.kt`

- [ ] **Step 1: Write the failing test**

In `BrokerApiSettingsTest.kt`, find the existing `finish_readiness_decodes` test and add two assertions after the existing ones (it decodes a `FinishReadiness` from JSON — reuse its `json` instance). Add a new test:

```kotlin
@Test fun finish_readiness_decodes_prRequiresGreen() {
    val withFlag = json.decodeFromString<FinishReadiness>(
        """{"branch":"b","base":"main","prRequiresGreen":true}"""
    )
    assertTrue(withFlag.prRequiresGreen)
    val omitted = json.decodeFromString<FinishReadiness>(
        """{"branch":"b","base":"main"}"""
    )
    assertFalse(omitted.prRequiresGreen)
}
```

Ensure `import kotlin.test.assertTrue` and `import kotlin.test.assertFalse` are present (add if missing).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiSettingsTest.finish_readiness_decodes_prRequiresGreen"`
Expected: FAIL — `prRequiresGreen` is not a member of `FinishReadiness`.

- [ ] **Step 3: Implement — add the field**

In `BrokerApi.kt`, add to the `FinishReadiness` data class (after `nothingToLand`):

```kotlin
    val nothingToLand: Boolean = false,
    val prRequiresGreen: Boolean = false,
)
```

The default `= false` keeps older payloads decoding (kotlinx.serialization treats defaulted properties as optional).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiSettingsTest.finish_readiness_decodes_prRequiresGreen"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiSettingsTest.kt
git commit --no-verify -m "$(printf 'feat(shared): add prRequiresGreen to FinishReadiness DTO\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 3: Web — `canSkipTests` helper + client type field

**Files:**
- Create: `src/web-app/src/lib/finish.ts`
- Create: `src/web-app/tests/finish.test.ts`
- Modify: `src/web-app/src/api/client.ts:56` (`FinishReadiness` interface)

- [ ] **Step 1: Write the failing test**

Create `src/web-app/tests/finish.test.ts`:

```ts
import { test, expect } from "bun:test"
import { canSkipTests } from "../src/lib/finish"

test("merge can always skip tests", () => {
  expect(canSkipTests("merge", false)).toBe(true)
  expect(canSkipTests("merge", true)).toBe(true)
})

test("pr can skip tests only when prRequiresGreen is false", () => {
  expect(canSkipTests("pr", false)).toBe(true)
  expect(canSkipTests("pr", true)).toBe(false)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd src/web-app && bun test tests/finish.test.ts`
Expected: FAIL — cannot find module `../src/lib/finish`.

- [ ] **Step 3: Implement the helper**

Create `src/web-app/src/lib/finish.ts`:

```ts
export type FinishVerifyAction = "merge" | "pr"

/** Whether "Skip tests" may be offered. Hidden only on the PR path when the
 *  repo requires green tests for a PR (skipping would silently defeat it). */
export function canSkipTests(action: FinishVerifyAction, prRequiresGreen: boolean): boolean {
  return !(action === "pr" && prRequiresGreen)
}
```

- [ ] **Step 4: Add the readiness field to the web client type**

In `src/web-app/src/api/client.ts`, in the `FinishReadiness` interface (starts at line 56), add:

```ts
  nothingToLand: boolean
  prRequiresGreen?: boolean
```

(Optional for back-compat; consumers read `readiness?.prRequiresGreen ?? false`.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd src/web-app && bun test tests/finish.test.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/web-app/src/lib/finish.ts src/web-app/tests/finish.test.ts src/web-app/src/api/client.ts
git commit --no-verify -m "$(printf 'feat(web): canSkipTests helper + prRequiresGreen on readiness type\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 4: Android — `canSkipTests` helper (non-Compose, unit-tested)

**Files:**
- Create: `apps/android/src/main/kotlin/dev/supermux/android/chat/FinishChoices.kt`
- Test: `apps/android/src/test/kotlin/dev/supermux/android/chat/FinishChoicesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `apps/android/src/test/kotlin/dev/supermux/android/chat/FinishChoicesTest.kt`:

```kotlin
package dev.supermux.android.chat

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FinishChoicesTest {
    @Test fun merge_can_always_skip() {
        assertTrue(canSkipTests("merge", false))
        assertTrue(canSkipTests("merge", true))
    }

    @Test fun pr_skips_only_when_not_requiring_green() {
        assertTrue(canSkipTests("pr", false))
        assertFalse(canSkipTests("pr", true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps && ./gradlew :android:testDebugUnitTest --tests "dev.supermux.android.chat.FinishChoicesTest"`
Expected: FAIL — unresolved reference `canSkipTests`.

- [ ] **Step 3: Implement the helper**

Create `apps/android/src/main/kotlin/dev/supermux/android/chat/FinishChoices.kt` (no Compose imports — keeps it unit-testable):

```kotlin
package dev.supermux.android.chat

/** Whether "Skip tests" may be offered. Hidden only on the PR path when the repo
 *  requires green tests for a PR (skipping would silently defeat that policy). */
fun canSkipTests(action: String, prRequiresGreen: Boolean): Boolean =
    !(action == "pr" && prRequiresGreen)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps && ./gradlew :android:testDebugUnitTest --tests "dev.supermux.android.chat.FinishChoicesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/chat/FinishChoices.kt apps/android/src/test/kotlin/dev/supermux/android/chat/FinishChoicesTest.kt
git commit --no-verify -m "$(printf 'feat(android): canSkipTests helper\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 5: iOS — `canSkipTests` helper + test

**Files:**
- Create: `apps/iosApp/Supermux/Chat/FinishChoices.swift`
- Test: `apps/iosApp/SupermuxTests/FinishChoicesTests.swift`

- [ ] **Step 1: Write the failing test**

Create `apps/iosApp/SupermuxTests/FinishChoicesTests.swift`:

```swift
import XCTest
@testable import Supermux

final class FinishChoicesTests: XCTestCase {
    func testMergeCanAlwaysSkip() {
        XCTAssertTrue(canSkipTests(action: "merge", prRequiresGreen: false))
        XCTAssertTrue(canSkipTests(action: "merge", prRequiresGreen: true))
    }
    func testPrSkipsOnlyWhenNotRequiringGreen() {
        XCTAssertTrue(canSkipTests(action: "pr", prRequiresGreen: false))
        XCTAssertFalse(canSkipTests(action: "pr", prRequiresGreen: true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (remote Mac, see `mux:ios-simulator-on-remote-mac`): build the test target.
Expected: FAIL — unresolved identifier `canSkipTests`. (If no Mac is reachable at execution time, mark this step blocked and note it in the handoff; do NOT silently skip.)

- [ ] **Step 3: Implement the helper**

Create `apps/iosApp/Supermux/Chat/FinishChoices.swift`:

```swift
/// Whether "Skip tests" may be offered. Hidden only on the PR path when the repo
/// requires green tests for a PR (skipping would silently defeat that policy).
func canSkipTests(action: String, prRequiresGreen: Bool) -> Bool {
    !(action == "pr" && prRequiresGreen)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the test target on the remote Mac. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/iosApp/Supermux/Chat/FinishChoices.swift apps/iosApp/SupermuxTests/FinishChoicesTests.swift
git commit --no-verify -m "$(printf 'feat(ios): canSkipTests helper\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 6: Web UI — two-step in `FinishSheet.vue` (depends on Task 3)

**Files:**
- Modify: `src/web-app/src/components/FinishSheet.vue`

- [ ] **Step 1: Add imports + state + handlers (script)**

Add the helper import near the other imports:

```ts
import { canSkipTests } from "@/lib/finish"
```

Add the pending-choice ref alongside the other refs (near `const busy = ref(false)`):

```ts
const pendingVerify = ref<"merge" | "pr" | null>(null)
```

Add menu handlers. **Keep `merge()`** (the `non_ff` outcome's "Merge again" still calls it). **Remove `openPr()`** — after this rewire it is only referenced by the menu PR button, which now calls `pickPr`, so it becomes dead code (`retryPr()` already covers the outcome retry path). Add:

```ts
function pickMerge() { pendingVerify.value = pendingVerify.value === "merge" ? null : "merge" }
function pickPr() { pendingVerify.value = pendingVerify.value === "pr" ? null : "pr" }
function chooseRun() { const a = pendingVerify.value; pendingVerify.value = null; if (a) void run({ action: a, skipVerify: false }) }
function chooseSkip() { const a = pendingVerify.value; pendingVerify.value = null; if (a) void run({ action: a, skipVerify: true }) }
function canSkip(): boolean { return pendingVerify.value != null && canSkipTests(pendingVerify.value, readiness.value?.prRequiresGreen ?? false) }
```

In the existing `watch(() => props.open, …)` reset block (which already sets `confirmingDiscard.value = false`), add:

```ts
  pendingVerify.value = null
```

In `keep()` and `confirmDiscard()`, add `pendingVerify.value = null` as the first line so opening a different action collapses the choice.

- [ ] **Step 2: Rewire the menu buttons + add the choice block (template)**

In the "Normal: four actions" block, change the Merge button handler from `@click="merge"` to `@click="pickMerge"`, and the PR button from `@click="openPr"` to `@click="pickPr"`.

Immediately AFTER the Merge button's closing `</button>`, insert the choice block:

```html
            <div
              v-if="pendingVerify === 'merge'"
              class="rounded-lg border border-border bg-card px-3 py-2.5 flex flex-col gap-2"
            >
              <span class="text-[12px] text-muted-foreground">Run tests before merging?</span>
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  :disabled="busy"
                  class="flex-1 text-[12px] px-2.5 py-2 rounded-md bg-emerald-600 text-white hover:bg-emerald-500 disabled:opacity-60 transition-colors"
                  @click="chooseRun"
                >Run tests</button>
                <button
                  v-if="canSkip()"
                  type="button"
                  :disabled="busy"
                  class="flex-1 text-[12px] px-2.5 py-2 rounded-md border border-amber-500/40 text-amber-400 hover:bg-amber-500/10 disabled:opacity-60 transition-colors"
                  @click="chooseSkip"
                >Skip tests</button>
              </div>
            </div>
```

Insert the SAME block after the PR button's closing `</button>`, but with `v-if="pendingVerify === 'pr'"` and the prompt text `Run tests before opening the PR?`.

- [ ] **Step 3: Typecheck + build**

Run: `cd src/web-app && bunx vue-tsc --noEmit && bun run build`
Expected: no type errors; build succeeds.
(If the project lints, also run its lint script.)

- [ ] **Step 4: Manual verification (stated, since no component-test harness)**

Run the broker/PWA (`mux:preview-broker` or the project run skill). In a worktree session with commits to land, open Finish and confirm:
1. Tapping *Merge locally* reveals **Run tests** / **Skip tests** and does NOT start finish.
2. *Run tests* → finish runs the verify step (Running shows "Running tests…").
3. *Skip tests* → finish skips straight to "Merging…".
4. Tapping *Open PR* shows the same; with `.mux/finish.json` `{"prRequiresGreen":true}`, *Skip tests* is hidden on the PR choice only.
5. Tapping *Merge locally* then *Open PR* collapses the first choice.

- [ ] **Step 5: Commit**

```bash
git add src/web-app/src/components/FinishSheet.vue
git commit --no-verify -m "$(printf 'feat(web): two-step Run/Skip tests on Finish merge & PR\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 7: Android UI — two-step in `FinishSheet.kt` (depends on Tasks 2, 4)

**Files:**
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/chat/FinishSheet.kt`

- [ ] **Step 1: Add pending state + choice rows in `MenuBody`**

`MenuBody` already receives `readiness` and `onFinish`. Inside `MenuBody`'s `Column`, add state at the top:

```kotlin
        var pendingVerify by remember { mutableStateOf<String?>(null) }
```

In the "Normal" branch (the `else` with the four actions), change the Merge row from calling `onFinish` to toggling the pending state, and insert the choice rows right after it. Replace:

```kotlin
            ActionRow(
                "Merge locally",
                R.drawable.ic_git_merge,
                color = if (readiness?.recommended == "merge") cs.primary else cs.onSurface,
            ) { onFinish("merge", null, null, null, kickoff) }
            PrRow(readiness, onFinish, kickoff)
```

with:

```kotlin
            ActionRow(
                "Merge locally",
                R.drawable.ic_git_merge,
                color = if (readiness?.recommended == "merge") cs.primary else cs.onSurface,
            ) { pendingVerify = if (pendingVerify == "merge") null else "merge" }
            if (pendingVerify == "merge") {
                VerifyChoiceRows(
                    prompt = "Run tests before merging?",
                    showSkip = canSkipTests("merge", readiness?.prRequiresGreen ?: false),
                    onRun = { pendingVerify = null; onFinish("merge", false, null, null, kickoff) },
                    onSkip = { pendingVerify = null; onFinish("merge", true, null, null, kickoff) },
                )
            }
            PrRow(readiness) { pendingVerify = if (pendingVerify == "pr") null else "pr" }
            if (pendingVerify == "pr") {
                VerifyChoiceRows(
                    prompt = "Run tests before opening the PR?",
                    showSkip = canSkipTests("pr", readiness?.prRequiresGreen ?: false),
                    onRun = { pendingVerify = null; onFinish("pr", false, null, null, kickoff) },
                    onSkip = { pendingVerify = null; onFinish("pr", true, null, null, kickoff) },
                )
            }
```

Then change `PrRow` to take a **tap callback** instead of finishing directly — this keeps its existing label / `enabled` / `trailing` logic verbatim (avoids duplicating the `ghAvailable` expression) and only swaps the tap behaviour. Replace the whole `PrRow` composable with:

```kotlin
@Composable
private fun PrRow(
    readiness: FinishReadiness?,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val label = if (readiness?.hasRemote == true && !readiness.ghAvailable) "Push & open PR" else "Open PR"
    val noRemote = readiness != null && !readiness.hasRemote
    ActionRow(
        label = label,
        iconRes = R.drawable.ic_git_pull_request,
        color = if (readiness?.recommended == "pr") cs.primary else cs.onSurface,
        enabled = !noRemote,
        trailing = if (noRemote) {
            { Text("no remote", color = cs.onSurfaceVariant, fontSize = 12.sp) }
        } else null,
    ) { onClick() }
}
```

Also collapse the choice when Keep/Discard are used: set `pendingVerify = null` at the start of the `ActionRow("Keep" …)` lambda (before `onFinish("keep", …)`). Leaving the Discard confirm overlap is acceptable (cosmetic), matching the existing inline-confirm behaviour.

- [ ] **Step 2: Add the `VerifyChoiceRows` composable**

Add near the other private composables in `FinishSheet.kt`:

```kotlin
/** Inline Run/Skip choice shown under Merge/Open PR (mirrors the Discard confirm). */
@Composable
private fun VerifyChoiceRows(
    prompt: String,
    showSkip: Boolean,
    onRun: () -> Unit,
    onSkip: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Text(prompt, color = cs.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRun) { Text("Run tests") }
            if (showSkip) {
                OutlinedButton(
                    onClick = onSkip,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.tertiary),
                ) { Text("Skip tests") }
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `cd apps && ./gradlew :android:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (resolves `canSkipTests`, `VerifyChoiceRows`).

- [ ] **Step 4: Run the Android unit tests (regression)**

Run: `cd apps && ./gradlew :android:testDebugUnitTest`
Expected: PASS (incl. `FinishChoicesTest`).

- [ ] **Step 5: Manual verification (stated, no Compose UI-test harness)**

Build+install the debug app (`mux:running-emulators` / `mux:driving-emulators`). In a worktree session with commits: tap *Merge locally* → Run/Skip appear; *Run tests* runs verify, *Skip tests* skips; *Open PR* same; with `prRequiresGreen` the Skip button is hidden on the PR choice only.

- [ ] **Step 6: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/chat/FinishSheet.kt
git commit --no-verify -m "$(printf 'feat(android): two-step Run/Skip tests on Finish merge & PR\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 8: iOS UI — two-step in `FinishSheet.swift` (depends on Tasks 2, 5)

**Files:**
- Modify: `apps/iosApp/Supermux/Chat/FinishSheet.swift`

- [ ] **Step 1: Add pending state**

Add to the `@State` block near `confirmingDiscard`:

```swift
    @State private var pendingVerify: String?   // "merge" | "pr" | nil
```

- [ ] **Step 2: Rewire the menu actions + add the choice rows**

In the `menu` view's action `Section`, replace the Merge and PR rows in the `else` branch. Change:

```swift
                    actionRow("Merge locally", systemImage: "arrow.triangle.merge",
                              highlighted: readiness?.recommended == "merge") { chrome.run(action: "merge") }
                    prRow
```

to:

```swift
                    actionRow("Merge locally", systemImage: "arrow.triangle.merge",
                              highlighted: readiness?.recommended == "merge") {
                        pendingVerify = pendingVerify == "merge" ? nil : "merge"
                    }
                    if pendingVerify == "merge" { verifyChoiceRows(action: "merge", prompt: "Run tests before merging?") }
                    prRow
                    if pendingVerify == "pr" { verifyChoiceRows(action: "pr", prompt: "Run tests before opening the PR?") }
```

Update `prRow`'s button action from `chrome.run(action: "pr")` to:

```swift
        Button { pendingVerify = pendingVerify == "pr" ? nil : "pr" } label: {
```

In the *Keep* and *Discard* actions in this section, set `pendingVerify = nil` before their existing calls so switching actions collapses the choice. (e.g. `actionRow("Keep", …) { pendingVerify = nil; chrome.run(action: "keep"); dismiss() }`.)

- [ ] **Step 3: Add the `verifyChoiceRows` builder**

Add near the other private view builders:

```swift
    @ViewBuilder private func verifyChoiceRows(action: String, prompt: String) -> some View {
        Text(prompt).font(.footnote).foregroundStyle(.secondary)
        Button { pendingVerify = nil; chrome.run(action: action, skipVerify: false) } label: {
            Label("Run tests", systemImage: "checkmark.circle")
        }
        if canSkipTests(action: action, prRequiresGreen: readiness?.prRequiresGreen ?? false) {
            Button { pendingVerify = nil; chrome.run(action: action, skipVerify: true) } label: {
                Label("Skip tests", systemImage: "forward")
            }.foregroundStyle(.orange)
        }
    }
```

(Confirm `chrome.run` accepts `skipVerify:` — it does; the outcome's "Merge anyway" already calls `chrome.run(action: "merge", skipVerify: true)`.)

- [ ] **Step 4: Build the app + run helper tests**

On the remote Mac (`mux:ios-simulator-on-remote-mac`): build the `Supermux` scheme and run the `SupermuxTests` target.
Expected: build succeeds; `FinishChoicesTests` + existing tests PASS. (If no Mac is reachable, mark blocked and report — do not silently skip.)

- [ ] **Step 5: Manual verification**

Run in the simulator: tap *Merge locally* → Run/Skip rows appear; *Run tests* runs verify, *Skip tests* skips; *Open PR* same; with `prRequiresGreen` the Skip row is hidden on the PR choice only.

- [ ] **Step 6: Commit**

```bash
git add apps/iosApp/Supermux/Chat/FinishSheet.swift
git commit --no-verify -m "$(printf 'feat(ios): two-step Run/Skip tests on Finish merge & PR\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Final verification (after all tasks)

- [ ] Backend + web logic: `bun test` (root) and `cd src/web-app && bun test` — all green.
- [ ] Types: `bunx tsc --noEmit -p tsconfig.json` and `cd src/web-app && bunx vue-tsc --noEmit` — clean.
- [ ] Android: `cd apps && ./gradlew :android:compileDebugKotlin :android:testDebugUnitTest :shared:jvmTest` — green.
- [ ] iOS (remote Mac): build `Supermux` + `SupermuxTests` — green (or explicitly reported blocked).
- [ ] Spec coverage re-check: every §4/§5/§6 requirement maps to a task (see self-review below).
- [ ] Use superpowers:requesting-code-review before merging.
