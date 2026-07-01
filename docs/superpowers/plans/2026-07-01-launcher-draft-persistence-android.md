# New Session Launcher Draft Persistence — Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist `SessionLauncherScreen`'s project pick, worktree settings, agent/model choice, and typed message text via Jetpack DataStore, so they survive leaving New Session and coming back — or a full process death/relaunch — and the draft (not the agent/model prefs) clears automatically once a session is actually created. Attachments are explicitly out of scope (ephemeral upload blobs — see the spec's Decisions §2).

**Architecture:** Two new `@Serializable` data classes (`LauncherPrefs`, `LauncherDraft`) plus load/save methods on `AppViewModel`, mirroring its existing per-session chat-draft DataStore pattern exactly (`AppViewModel.kt:74-76,323-334`) but in a separate `launcher_state` DataStore file. `SessionLauncherScreen` — which has no ViewModel of its own today, just `remember{}` — gains new callback params (matching its existing all-callbacks style) wired from `MainActivity.kt`. A `launcherRestoring` gate (mirroring `ChatScreen.kt`'s `draftLoaded` gate, generalized to also protect two OTHER effects from clobbering restored state) blocks the agent-list-fetch and repo-info effects until the one-time restore has fully landed; a `lastSeenAgent`/`lastSeenWorkdir` comparison (not a one-shot "armed" boolean) then decides whether a change is the restore settling or a genuine later switch — see the **2026-07-01 update** below for why.

> **Update (2026-07-01, after this plan was first written):** the iOS version of this exact mechanism (a shared `launcherRestoring`-style flag plus one-shot `modelResetArmed`/`baseBranchResetArmed` booleans) passed two static code reviews, then was found via **real on-device testing** to fail 100% of the time — restored `model`/`baseBranch` were silently clobbered back to their reset values on every fresh mount, because SwiftUI's `.task(id:)` turned out to spin up a second task instance for the *same, already-settled* id shortly after the first, and a one-shot boolean can't distinguish "second invocation for an unchanged id" from "id genuinely changed." iOS's fix replaced the one-shot booleans with `lastSeenAgent`/`lastSeenWorkdir` — recording the actual last-observed value and only resetting when the *live* value differs from it, which is correct regardless of how many duplicate invocations occur for the same key. **This plan (below) already reflects that lesson** — it specifies `lastSeenAgent`/`lastSeenWorkdir`, not one-shot booleans, even though it's unconfirmed whether Compose's `LaunchedEffect(key1, key2)` actually shares SwiftUI's duplicate-invocation behavior. The `lastSeen` approach costs nothing extra either way and is strictly safer, so use it regardless of whether the underlying Compose behavior turns out to match SwiftUI's. **Given a materially similar mechanism already fooled two static reviews once, Task 3's manual/emulator verification for this specific mechanism is not optional — treat it as the real acceptance gate, the same way it was for iOS.**

**Tech Stack:** Jetpack Compose, Kotlin coroutines (`LaunchedEffect`, `delay`), Jetpack DataStore (Preferences), `kotlinx.serialization`.

**Spec:** `docs/superpowers/specs/2026-07-01-launcher-draft-persistence-design.md`

---

### Task 1: `LauncherState` data classes + `AppViewModel` persistence

**Files:**
- Create: `apps/android/src/main/kotlin/dev/supermux/android/session/LauncherState.kt`
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt`

There's no existing unit-test coverage for `AppViewModel`'s DataStore-backed methods (`loadDraft`/`saveDraft` have none either — `apps/android/src/test/kotlin` only has 3 files, none touching `AppViewModel`, which needs a real `Application`/`Context` that isn't wired up with Robolectric here). Introducing that test infrastructure is out of scope for this feature. This task is implementation + the compile check + Task 3's manual verification, consistent with existing practice.

- [ ] **Step 1: Write the data classes**

```kotlin
// apps/android/src/main/kotlin/dev/supermux/android/session/LauncherState.kt
package dev.supermux.android.session

import kotlinx.serialization.Serializable

/** Sticky New Session launcher preferences — the agent + its last-used model, keyed per agent.
 *  Mirrors the web launcher's `cmux:launcher-prefs` localStorage shape (SessionLauncherView.vue). */
@Serializable
data class LauncherPrefs(
    val agent: String = "claude",
    val models: Map<String, String> = emptyMap(),
)

/** In-progress New Session launcher draft — cleared once a session is actually created.
 *  `workdir` is null when nothing was explicitly restored (so the screen's own
 *  most-recent-session fallback still applies). Mirrors the web launcher's `cmux:launcher-draft`. */
@Serializable
data class LauncherDraft(
    val workdir: String? = null,
    val useWorktree: Boolean = true,
    val baseBranch: String = "",
    val text: String = "",
)
```

- [ ] **Step 2: Add persistence methods to `AppViewModel`**

Modify `apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt:74-76` (currently):

```kotlin
/** App-scoped DataStore backing per-session composer drafts (process-death-durable; mirrors
 *  iOS UserDefaults "cmux:draft:<id>"). One store for the whole app, keyed per session. */
private val Context.draftDataStore by preferencesDataStore(name = "chat_drafts")
```

Add immediately after it:

```kotlin
/** App-scoped DataStore backing the New Session launcher's persisted state — separate from
 *  chat_drafts (a different concept/lifecycle: pre-session, not per-session). */
private val Context.launcherDataStore by preferencesDataStore(name = "launcher_state")
```

Modify `apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt:323-334` (currently):

```kotlin
    // ── Per-session composer draft persistence (DataStore) ─────────────────────────
    // Survives session-switch AND process death (iOS UserDefaults "cmux:draft:<id>" parity).
    private fun draftKey(sessionId: String) = stringPreferencesKey("draft:$sessionId")

    suspend fun loadDraft(sessionId: String): String =
        runCatching { appContext.draftDataStore.data.first()[draftKey(sessionId)] }.getOrNull() ?: ""

    fun saveDraft(sessionId: String, text: String) {
        viewModelScope.launch {
            runCatching { appContext.draftDataStore.edit { it[draftKey(sessionId)] = text } }
        }
    }
```

Add immediately after it:

```kotlin
    // ── New Session launcher state persistence (DataStore) ─────────────────────────
    // Two lifecycles: prefs persist forever; draft persists until a session is created.
    private val launcherJson = Json { ignoreUnknownKeys = true }
    private val launcherPrefsKey = stringPreferencesKey("launcher_prefs")
    private val launcherDraftKey = stringPreferencesKey("launcher_draft")

    suspend fun loadLauncherPrefs(): LauncherPrefs =
        runCatching {
            appContext.launcherDataStore.data.first()[launcherPrefsKey]
                ?.let { launcherJson.decodeFromString<LauncherPrefs>(it) }
        }.getOrNull() ?: LauncherPrefs()

    fun saveLauncherPrefs(prefs: LauncherPrefs) {
        viewModelScope.launch {
            runCatching {
                appContext.launcherDataStore.edit { it[launcherPrefsKey] = launcherJson.encodeToString(prefs) }
            }
        }
    }

    suspend fun loadLauncherDraft(): LauncherDraft =
        runCatching {
            appContext.launcherDataStore.data.first()[launcherDraftKey]
                ?.let { launcherJson.decodeFromString<LauncherDraft>(it) }
        }.getOrNull() ?: LauncherDraft()

    fun saveLauncherDraft(draft: LauncherDraft) {
        viewModelScope.launch {
            runCatching {
                appContext.launcherDataStore.edit { it[launcherDraftKey] = launcherJson.encodeToString(draft) }
            }
        }
    }
```

Add the new imports near the top of `AppViewModel.kt`, alongside the existing `dev.supermux.proto`/`dev.supermux.net` imports:

```kotlin
import dev.supermux.android.session.LauncherDraft
import dev.supermux.android.session.LauncherPrefs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
```

- [ ] **Step 3: Compile check**

Run: `cd apps && ./gradlew :android:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/session/LauncherState.kt apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt
git commit -m "feat(android): add LauncherState + AppViewModel persistence for New Session"
```

---

### Task 2: Wire persistence into `SessionLauncherScreen`

**Files:**
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionLauncherScreen.kt`
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/MainActivity.kt`

Two existing effects unconditionally reset state that this task now restores from persistence, so both need a guard — otherwise the restored value gets immediately overwritten:
- `LaunchedEffect(agent) { model = null; models = loadModels(agent) }` (line 87-90) — fires once on first composition too, for the plain `"claude"` default.
- `LaunchedEffect(workdir) { ...; baseBranch = info?.currentBranch ?: "" }` (line 97-101) — same issue for `baseBranch`.

**A plain "have I run once" flag per effect is not enough — and neither is a one-shot "armed" boolean.** `loadLauncherPrefs()`/`loadLauncherDraft()` are real DataStore reads (suspend, not instant) — so there's a window where `LaunchedEffect(agent)` runs for the untouched `"claude"` default, completes its "first run" bookkeeping, *and only then* does the restore effect change `agent` to the persisted value. Compose then cancels and relaunches `LaunchedEffect(agent)` for the new key. The fix needs two layers:

1. A shared `launcherRestoring` flag (starts `true`) that blocks *both* effects' bodies outright until the restore has applied every field. The restore effect (Step 3) has two suspend points of its own (`loadLauncherPrefs()`, then `loadLauncherDraft()`), so its assignments land across more than one recomposition — that's fine, because the only property that matters is that `launcherRestoring = false` is its *last* assignment, after every other field. Whichever recomposition pass finally flips it, both guarded effects have already seen every restored field by then, so their first *unblocked* run is guaranteed to be the fully-settled one, never a partial one.
2. Once unblocked, each effect must still distinguish "this is the first run after the restore settled" from "the user genuinely changed agent/workdir." **Do not use a one-shot boolean for this** (e.g. `if (modelResetArmed) model = null else modelResetArmed = true`) — this is the exact mechanism that shipped on iOS, passed two independent static code reviews, and then was found via real on-device testing to fail 100% of the time: SwiftUI's `.task(id:)` turned out to spin up a *second* task instance for the same, already-settled id shortly after the first, and a one-shot boolean can't tell that apart from a genuine id change (the first instance arms it, the second — for the identical id — sees it already armed and wrongly resets). It's unconfirmed whether Compose's `LaunchedEffect(key1, key2)` has the same duplicate-invocation behavior, but there's no reason to risk it: use `lastSeenAgent: String?`/`lastSeenWorkdir: String?` (nullable, start `null`) instead, and reset only when the *live* value differs from what was last recorded (`null` means "never recorded yet" and must never itself count as a difference). This is correct regardless of how many duplicate invocations occur for the same key, not just the first one — see the Step 2/3 code below.

Agent and model persist from their explicit pick sites (the agent `SegmentedButton` and the model `PickerSheet`), **not** a generic effect keyed on `agent`/`model`. Reason: `LaunchedEffect(agent)`'s `model = null` reset (above) is a real state change too, and persisting on *every* model change can't tell "the user picked a model" apart from "the code just reset it because the agent changed" — that would silently erase the previous agent's remembered model the instant you switch away from it. `workdir`/`useWorktree`/`baseBranch`/`message` don't have this problem — for those, "whatever's currently on screen" is exactly what the draft should remember, so persisting on every change (including a programmatic default) is correct, not a bug; they get one shared debounced save effect, mirroring `ChatScreen.kt:297-308`'s 400ms-`delay` pattern.

- [ ] **Step 1: Add new params, imports, and local state**

Modify `apps/android/src/main/kotlin/dev/supermux/android/session/SessionLauncherScreen.kt:63-69` (currently):

```kotlin
    // Voice dictation — no session yet, so these hit the broker's id-less /transcribe (the
    // session only enriches cleanup context). Same wiring as chat, minus the session id.
    loadGlossary: suspend () -> List<String> = { emptyList() },
    transcribeDraft: suspend (draft: String) -> String? = { null },
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String? = { _, _ -> null },
    onSubmit: suspend (workdir: String, agent: String, model: String?, message: String, worktree: Boolean, baseBranch: String?) -> String,
    onOpenSession: (String) -> Unit,
) {
```

Replace with:

```kotlin
    // Voice dictation — no session yet, so these hit the broker's id-less /transcribe (the
    // session only enriches cleanup context). Same wiring as chat, minus the session id.
    loadGlossary: suspend () -> List<String> = { emptyList() },
    transcribeDraft: suspend (draft: String) -> String? = { null },
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String? = { _, _ -> null },
    // Launcher state persistence — sticky agent/model prefs, and an in-progress draft cleared
    // once a session is actually created (see onSubmit's success path below).
    loadLauncherPrefs: suspend () -> LauncherPrefs = { LauncherPrefs() },
    onLauncherPrefsChange: (LauncherPrefs) -> Unit = {},
    loadLauncherDraft: suspend () -> LauncherDraft = { LauncherDraft() },
    onLauncherDraftChange: (LauncherDraft) -> Unit = {},
    onSubmit: suspend (workdir: String, agent: String, model: String?, message: String, worktree: Boolean, baseBranch: String?) -> String,
    onOpenSession: (String) -> Unit,
) {
```

Add the import near the top of the file, alongside the other `dev.supermux.*` imports (`SessionLauncherScreen.kt:34-39`):

```kotlin
import kotlinx.coroutines.delay
```

(`LauncherPrefs`/`LauncherDraft` need no import — they're already in this file's own package, `dev.supermux.android.session`.)

Modify `apps/android/src/main/kotlin/dev/supermux/android/session/SessionLauncherScreen.kt:75-78` (currently):

```kotlin
    var message by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf(emptyList<String>()) }
    var showProjectSheet by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
```

Replace with:

```kotlin
    var message by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf(emptyList<String>()) }
    var showProjectSheet by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    // Launcher state persistence — see this task's header note for why launcherRestoring gates
    // the agent/workdir effects below, and why lastSeenAgent/lastSeenWorkdir (not one-shot
    // "armed" booleans) are the safe way to distinguish restore-settling from a genuine later
    // change. launcherModels mirrors LauncherPrefs.models (the per-agent memory) so a pick can
    // reconstruct the whole prefs blob to persist.
    var launcherRestoring by remember { mutableStateOf(true) }
    var lastSeenAgent by remember { mutableStateOf<String?>(null) }
    var lastSeenWorkdir by remember { mutableStateOf<String?>(null) }
    var launcherModels by remember { mutableStateOf(emptyMap<String, String>()) }
```

- [ ] **Step 2: Guard the agent-triggered model reset**

Modify `apps/android/src/main/kotlin/dev/supermux/android/session/SessionLauncherScreen.kt:87-90` (currently):

```kotlin
    LaunchedEffect(agent) {
        model = null
        models = loadModels(agent)
    }
```

Replace with:

```kotlin
    LaunchedEffect(agent, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        models = loadModels(agent)
        // Reset only when the live agent genuinely differs from what this effect last recorded
        // — safe against any number of duplicate invocations for the same agent, unlike a
        // one-shot "armed" boolean (see this task's header note for why that broke on iOS).
        // lastSeenAgent == null means "never recorded yet" and must never count as a difference.
        if (lastSeenAgent != null && lastSeenAgent != agent) {
            model = null
        }
        lastSeenAgent = agent
    }
```

- [ ] **Step 3: Guard the workdir-triggered base-branch reset, and add the restore effect**

Modify `apps/android/src/main/kotlin/dev/supermux/android/session/SessionLauncherScreen.kt:97-101` (currently):

```kotlin
    LaunchedEffect(workdir) {
        val info = if (workdir.isBlank()) null else loadRepoInfo(workdir)
        repoInfo = info
        baseBranch = info?.currentBranch ?: ""
    }
```

Replace with:

```kotlin
    LaunchedEffect(workdir, launcherRestoring) {
        if (launcherRestoring) { repoInfo = null; return@LaunchedEffect }
        val info = if (workdir.isBlank()) null else loadRepoInfo(workdir)
        repoInfo = info
        if (lastSeenWorkdir != null && lastSeenWorkdir != workdir) {
            baseBranch = info?.currentBranch ?: ""
        } else if (baseBranch.isBlank()) {
            baseBranch = info?.currentBranch ?: ""
        }
        lastSeenWorkdir = workdir
    }

    // Restore persisted launcher state once. Runs after useWorktree/baseBranch are declared
    // (Kotlin needs them in scope), but correctness doesn't depend on textual position relative
    // to the two guarded effects above — launcherRestoring blocks their bodies regardless of
    // exactly when this finishes; flipping it false is this effect's LAST assignment, so both
    // guarded effects only ever see the fully-restored values on their first real run.
    LaunchedEffect(Unit) {
        val prefs = loadLauncherPrefs()
        // Validate against the known agent list — web's loadPrefs() does the same
        // (SessionLauncherView.vue:126) — so a future agent type added after this prefs blob
        // was written can't leave `agent` holding a value the SegmentedButtonRow has no
        // matching button for.
        agent = if (agents.contains(prefs.agent)) prefs.agent else "claude"
        launcherModels = prefs.models
        model = prefs.models[agent]
        val draft = loadLauncherDraft()
        if (draft.workdir != null) {
            workdir = draft.workdir
            workdirTouched = true
        }
        useWorktree = draft.useWorktree
        baseBranch = draft.baseBranch
        message = draft.text
        launcherRestoring = false
    }
```

- [ ] **Step 4: Debounced save for the draft fields**

Add immediately after the restore effect from Step 3:

```kotlin
    // Persist the in-progress draft, debounced (~400ms) — mirrors ChatScreen.kt's per-session
    // draft save. launcherRestoring gates it so the restore's own assignments (above) don't
    // immediately re-save right back over themselves before they've even settled.
    LaunchedEffect(workdir, workdirTouched, useWorktree, baseBranch, message, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        delay(400)
        onLauncherDraftChange(
            LauncherDraft(
                workdir = if (workdirTouched) workdir else null,
                useWorktree = useWorktree,
                baseBranch = baseBranch,
                text = message,
            )
        )
    }
```

- [ ] **Step 5: Persist agent at its pick site**

Modify `apps/android/src/main/kotlin/dev/supermux/android/session/SessionLauncherScreen.kt:204-215` (currently):

```kotlin
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                agents.forEachIndexed { i, a ->
                    SegmentedButton(
                        selected = agent == a,
                        onClick = { agent = a },
                        shape = SegmentedButtonDefaults.itemShape(i, agents.size),
                        modifier = Modifier.testTag("agent_$a"),
                    ) {
                        Text(a)
                    }
                }
            }
```

Replace with:

```kotlin
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                agents.forEachIndexed { i, a ->
                    SegmentedButton(
                        selected = agent == a,
                        onClick = {
                            agent = a
                            onLauncherPrefsChange(LauncherPrefs(agent = a, models = launcherModels))
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, agents.size),
                        modifier = Modifier.testTag("agent_$a"),
                    ) {
                        Text(a)
                    }
                }
            }
```

- [ ] **Step 6: Persist model at its pick site**

Modify `apps/android/src/main/kotlin/dev/supermux/android/session/SessionLauncherScreen.kt:315-324` (currently):

```kotlin
    if (showModelSheet) {
        val opts = listOf(DEFAULT_MODEL_ID to "Default") + models.map { it.id to it.displayName }
        PickerSheet(
            title = "Select Model",
            options = opts,
            current = model ?: DEFAULT_MODEL_ID,
            onPick = { picked -> model = if (picked == DEFAULT_MODEL_ID) null else picked },
            onDismiss = { showModelSheet = false },
        )
    }
```

Replace with:

```kotlin
    if (showModelSheet) {
        val opts = listOf(DEFAULT_MODEL_ID to "Default") + models.map { it.id to it.displayName }
        PickerSheet(
            title = "Select Model",
            options = opts,
            current = model ?: DEFAULT_MODEL_ID,
            onPick = { picked ->
                val newModel = if (picked == DEFAULT_MODEL_ID) null else picked
                model = newModel
                launcherModels = if (newModel != null) launcherModels + (agent to newModel) else launcherModels - agent
                onLauncherPrefsChange(LauncherPrefs(agent = agent, models = launcherModels))
            },
            onDismiss = { showModelSheet = false },
        )
    }
```

- [ ] **Step 7: Clear the draft when a session is created**

Modify `apps/android/src/main/kotlin/dev/supermux/android/session/SessionLauncherScreen.kt` — find the submit button's `onClick` (around line 273-289, currently):

```kotlin
                    scope.launch {
                        try {
                            val sessionId = onSubmit(
                                workdir.trim(),
                                agent,
                                model,
                                text,
                                wantsWorktree,
                                base,
                            )
                            onOpenSession(sessionId)
                        } catch (e: Exception) {
                            error = e.message ?: "Failed to create session"
                        } finally {
                            submitting = false
                        }
                    }
```

Replace with:

```kotlin
                    scope.launch {
                        try {
                            val sessionId = onSubmit(
                                workdir.trim(),
                                agent,
                                model,
                                text,
                                wantsWorktree,
                                base,
                            )
                            onLauncherDraftChange(LauncherDraft())
                            onOpenSession(sessionId)
                        } catch (e: Exception) {
                            error = e.message ?: "Failed to create session"
                        } finally {
                            submitting = false
                        }
                    }
```

- [ ] **Step 8: Wire the new params in `MainActivity.kt`**

Modify `apps/android/src/main/kotlin/dev/supermux/android/MainActivity.kt` at **both** `SessionLauncherScreen(...)` call sites (the expanded/tablet layout around line 290, and the phone layout around line 314) — in each, add these four lines after `transcribeAudio = { bytes, name -> vm.transcribeAudio(null, bytes, name) },` and before `onSubmit = { ... }`:

```kotlin
                                        loadLauncherPrefs = { vm.loadLauncherPrefs() },
                                        onLauncherPrefsChange = { vm.saveLauncherPrefs(it) },
                                        loadLauncherDraft = { vm.loadLauncherDraft() },
                                        onLauncherDraftChange = { vm.saveLauncherDraft(it) },
```

(Match the indentation already used by the surrounding arguments at each of the two call sites — the tablet-layout call site is indented one level deeper than the phone-layout one.)

- [ ] **Step 9: Compile check**

Run: `cd apps && ./gradlew :android:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/session/SessionLauncherScreen.kt apps/android/src/main/kotlin/dev/supermux/android/MainActivity.kt
git commit -m "feat(android): restore + persist New Session draft (project, worktree, agent/model, text)"
```

---

### Task 3: Manual verification

No existing automated coverage exercises the chat draft's full screen wiring either — `ChatScreen.kt`'s `draftLoaded` pattern is verified manually, not unit-tested. This is a manual pass, via the `mux:running-emulators` / `mux:driving-emulators` skills.

- [ ] **Step 1: Smoke-test restore across in-app navigation**

1. Open New Session. Pick a specific project that's a git repo, open the worktree sheet and pick a base branch that is **not** the repo's current branch, pick a specific agent + model (not the defaults), and type a message like `testing draft persistence`.
2. Navigate back to the session list and open New Session again.
3. Confirm: same project, same base branch (not silently reverted to the repo's actual current branch), same agent + model, and the typed text is still there.

- [ ] **Step 2: Smoke-test restore across a process death / relaunch**

1. With the same in-progress draft from Step 1, force-stop the app (e.g. from Android's App Info screen, or `adb shell am force-stop dev.supermux.android`) and relaunch it.
2. Confirm the draft is still restored.

- [ ] **Step 3: Smoke-test agent switching doesn't erase the other agent's remembered model, across mounts — and stress-test the restore-guard mechanism**

Agent/model prefs are only read from persisted storage once per screen instance (at restore time), matching the design doc's "pre-fill every future launch" wording and the web/iOS versions of this same feature — so this must be tested across a remount, not as one continuous live switch. **This is also the mechanism (`lastSeenAgent`/`lastSeenWorkdir`, see Task 2's header) that replaced a design proven buggy on iOS via exactly this kind of on-device test — treat this step as mandatory, not optional, before considering Android done.**

1. On New Session, pick agent Claude and a specific (non-default) model.
2. Switch to agent Codex (still same visit) — confirm the model resets to "Default" (expected, unchanged pre-existing behavior).
3. **Navigate away (back to session list) and open New Session again** — do NOT just switch agents within the same continuous screen instance.
4. Pick agent Claude again — confirm your earlier model choice for Claude is restored (not "Default"). This is the scenario Task 2 Steps 5-6 exist for; persisting on every model change instead of only at the pick site would fail this check by erasing Claude's saved model the moment you switched away from it.
5. As a stress test of the restore-guard mechanism itself: rapidly switch agents back and forth several times (Claude → Codex → Cursor → Opencode → Claude, quickly) *within one visit*, then navigate away and back once more, and confirm the final restored state (agent + model) is coherent and matches whatever you last explicitly picked — not garbled, not showing a stale/wrong model for the current agent. Also repeat Step 1's base-branch check (pick a non-current branch, navigate away and back) a few times in a row to stress the `lastSeenWorkdir` path the same way.

- [ ] **Step 4: Smoke-test clearing on submit**

1. From a restored draft, submit to actually create a session.
2. Open New Session again.
3. Confirm: project reverts to the most-recent-session default, worktree/base-branch/text are cleared — but agent/model still show your last pick (unaffected by the clear).
