# Desktop Workspace Keep-Alive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve transient Compose desktop workspace state across workspace switches while bounding retained workspace compositions to the ten most recently viewed.

**Architecture:** Add a pure workspace-ID LRU and a Compose host that gives each retained workspace a stable keyed slot inside the existing heavyweight-safe `KeepAlivePanel`. `AppShell` will render the active and retained workspace layers together, gate active-only effects, and draw launcher/empty states above the hidden layers.

**Tech Stack:** Kotlin/JVM, Compose Multiplatform Desktop, Compose UI tests, Kotlin test, Gradle, Xvfb software rendering.

---

## File Structure

- Create `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAlive.kt`: the bounded LRU model and reusable Compose layer host.
- Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAliveTest.kt`: pure ordering tests plus Compose lifecycle, bounds, scroll-retention, and eviction tests.
- Modify `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/AppShell.kt`: resolve the active workspace once, render retained layers, gate active-only effects, and overlay launcher/empty states.
- Modify `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/AppShellTest.kt`: prove the real shell retains the first workspace layer after selecting a second workspace.

### Task 1: Implement the bounded workspace LRU

**Files:**

- Create: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAliveTest.kt`
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAlive.kt`

- [ ] **Step 1: Write the failing pure-model tests**

Create `WorkspaceKeepAliveTest.kt` with the model cases first:

```kotlin
package dev.supermux.desktop.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceKeepAliveTest {
    @Test
    fun selectedWorkspaceIsMostRecentAndNeverDuplicated() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)

        assertEquals(listOf("w1"), cache.update("w1", setOf("w1", "w2")))
        assertEquals(listOf("w1", "w2"), cache.update("w2", setOf("w1", "w2")))
        assertEquals(listOf("w2", "w1"), cache.update("w1", setOf("w1", "w2")))
    }

    @Test
    fun eleventhWorkspaceEvictsTheLeastRecentlyViewed() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        val live = (1..11).map { "w$it" }.toSet()

        (1..11).forEach { cache.update("w$it", live) }

        assertEquals((2..11).map { "w$it" }, cache.update("w11", live))
    }

    @Test
    fun removedWorkspacesArePrunedImmediately() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        cache.update("w1", setOf("w1", "w2"))
        cache.update("w2", setOf("w1", "w2"))

        assertEquals(listOf("w2"), cache.update("w2", setOf("w2")))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `apps/`:

```bash
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test \
  --tests 'dev.supermux.desktop.shell.WorkspaceKeepAliveTest' --console=plain
```

Expected: compilation fails because `WorkspaceKeepAliveCache` does not exist.

- [ ] **Step 3: Add the minimal LRU implementation**

Create `WorkspaceKeepAlive.kt` with:

```kotlin
package dev.supermux.desktop.shell

internal const val MAX_RETAINED_WORKSPACES = 10

internal class WorkspaceKeepAliveCache(
    private val maxSize: Int = MAX_RETAINED_WORKSPACES,
) {
    private val retained = linkedSetOf<String>()

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    fun update(activeWorkspaceId: String?, liveWorkspaceIds: Set<String>): List<String> {
        retained.retainAll(liveWorkspaceIds)
        if (activeWorkspaceId != null && activeWorkspaceId in liveWorkspaceIds) {
            retained.remove(activeWorkspaceId)
            retained.add(activeWorkspaceId)
        }
        while (retained.size > maxSize) retained.remove(retained.first())
        return retained.toList()
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2 again. Expected: all three model tests pass.

- [ ] **Step 5: Commit the model**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAlive.kt \
  apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAliveTest.kt
git commit -m "feat(desktop): add bounded workspace retention"
```

### Task 2: Keep retained Compose layers mounted and preserve scroll

**Files:**

- Modify: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAliveTest.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAlive.kt`

- [ ] **Step 1: Add failing Compose lifecycle tests**

Extend `WorkspaceKeepAliveTest.kt` with a probe and three UI tests. Add the required Compose imports, then add:

```kotlin
private class WorkspaceProbe {
    var mounts = 0
    var disposals = 0
}

@Composable
private fun ProbeWorkspace(id: String, probe: WorkspaceProbe) {
    LaunchedEffect(Unit) { probe.mounts += 1 }
    DisposableEffect(Unit) { onDispose { probe.disposals += 1 } }
    Box(Modifier.fillMaxSize().testTag("content-$id"))
}

@Test
fun switchingAwayAndBackDoesNotDisposeOrRemountAWorkspace() = runComposeUiTest {
    val probes = mutableMapOf("w1" to WorkspaceProbe(), "w2" to WorkspaceProbe())
    var active by mutableStateOf("w1")
    setContent {
        WorkspaceKeepAliveHost(active, setOf("w1", "w2")) { id, _ ->
            ProbeWorkspace(id, probes.getValue(id))
        }
    }

    waitForIdle()
    active = "w2"
    waitForIdle()
    assertEquals(0.dp, onNodeWithTag("content-w1").getBoundsInRoot().width)
    active = "w1"
    waitForIdle()

    assertEquals(1, probes.getValue("w1").mounts)
    assertEquals(0, probes.getValue("w1").disposals)
    onNodeWithTag("content-w1").assertIsDisplayed()
}

@Test
fun retainedWorkspaceKeepsItsLazyListPosition() = runComposeUiTest {
    var active by mutableStateOf("w1")
    setContent {
        WorkspaceKeepAliveHost(active, setOf("w1", "w2")) { id, _ ->
            val state = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().testTag("list-$id"), state = state) {
                items(100) { index -> Text("$id-item-$index") }
            }
        }
    }

    onNodeWithTag("list-w1").performScrollToIndex(60)
    active = "w2"
    waitForIdle()
    active = "w1"
    waitForIdle()

    onNodeWithText("w1-item-60").assertIsDisplayed()
}

@Test
fun eleventhWorkspaceDisposesTheLeastRecentLayer() = runComposeUiTest {
    val probes = (1..11).associate { "w$it" to WorkspaceProbe() }
    val live = probes.keys
    var active by mutableStateOf("w1")
    setContent {
        WorkspaceKeepAliveHost(active, live) { id, _ ->
            ProbeWorkspace(id, probes.getValue(id))
        }
    }

    (2..11).forEach {
        active = "w$it"
        waitForIdle()
    }

    assertEquals(1, probes.getValue("w1").disposals)
    onNodeWithTag("content-w1").assertDoesNotExist()
    onNodeWithTag("content-w11").assertIsDisplayed()
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run the Task 1 focused command. Expected: compilation fails because `WorkspaceKeepAliveHost` does not exist.

- [ ] **Step 3: Implement the heavyweight-safe Compose host**

Append to `WorkspaceKeepAlive.kt`:

```kotlin
@Composable
internal fun WorkspaceKeepAliveHost(
    activeWorkspaceId: String?,
    liveWorkspaceIds: Set<String>,
    showActive: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable (workspaceId: String, active: Boolean) -> Unit,
) {
    val cache = remember { WorkspaceKeepAliveCache() }
    val retained = remember(activeWorkspaceId, liveWorkspaceIds) {
        cache.update(activeWorkspaceId, liveWorkspaceIds)
    }

    Box(modifier.fillMaxSize()) {
        retained.forEach { workspaceId ->
            val active = showActive && workspaceId == activeWorkspaceId
            key(workspaceId) {
                KeepAlivePanel(
                    visible = active,
                    modifier = Modifier.testTag("workspace-layer-$workspaceId"),
                ) {
                    content(workspaceId, active)
                }
            }
        }
    }
}
```

Add imports for `Box`, `Composable`, `key`, `remember`, `Modifier`, `testTag`, `KeepAlivePanel`, and `fillMaxSize`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the focused command again. Expected: all model and Compose retention tests pass, including the scroll-position assertion.

- [ ] **Step 5: Commit the Compose host**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAlive.kt \
  apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAliveTest.kt
git commit -m "feat(desktop): keep recent workspace layers composed"
```

### Task 3: Wire the cache into the real desktop shell

**Files:**

- Modify: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/AppShellTest.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/AppShell.kt`

- [ ] **Step 1: Add the failing AppShell switch test**

Add an `appWithTwoWorkspaces()` fixture beside the existing `appFor()` fixture. Feed `DesktopAppState.reduce` a snapshot with sessions `s1`/`s2`, workspaces `w1`/`w2`, and one chat view in each. Add this test:

```kotlin
@Test
fun switchingWorkspacesKeepsThePreviousWorkspaceLayerMountedAtZeroSize() = runComposeUiTest {
    val app = appWithTwoWorkspaces()
    val ui = ShellUiState().apply { selectedId = "s1" }
    setContent {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            AppShell(app, ui, ShellStateStore(tempPath("state")), LauncherStore(tempPath("launcher")))
        }
    }

    waitForIdle()
    onNodeWithTag("workspace-layer-w1").assertIsDisplayed()

    ui.selectedId = "s2"
    waitForIdle()

    assertEquals(0.dp, onNodeWithTag("workspace-layer-w1").getBoundsInRoot().width)
    onNodeWithTag("workspace-layer-w2").assertIsDisplayed()

    ui.selectedId = "s1"
    waitForIdle()
    onNodeWithTag("workspace-layer-w1").assertIsDisplayed()
}
```

Import `getBoundsInRoot` and `dp`. The fixture must use `singleViewLayout("g-$workspaceId", "v-$workspaceId").toDto()` and chat state `{ "sessionId": sessionId }`, matching the existing single-workspace fixture exactly.

- [ ] **Step 2: Run the AppShell test and verify RED**

Run from `apps/`:

```bash
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test \
  --tests 'dev.supermux.desktop.shell.AppShellTest.switchingWorkspacesKeepsThePreviousWorkspaceLayerMountedAtZeroSize' \
  --console=plain
```

Expected: the `workspace-layer-w1` node does not exist because `AppShell` still disposes the prior workspace.

- [ ] **Step 3: Resolve active workspace independently of the rendered layer**

In `AppShell.kt`, immediately after deriving `session`, derive:

```kotlin
val activeWorkspace = session?.let { selected ->
    workspaces.firstOrNull { workspace -> workspace.chatSessionIds().contains(selected.id) }
}
val liveWorkspaceIds = remember(workspaces) { workspaces.mapTo(linkedSetOf()) { it.id } }
```

Keep the existing no-workspace behavior: a selected session with no matching workspace still shows `workspace_welcome`.

- [ ] **Step 4: Replace the single-workspace conditional with retained layers and overlays**

Wrap the detail region in a `Box(Modifier.fillMaxSize())`. Render `WorkspaceKeepAliveHost` before the launcher/empty overlays:

```kotlin
WorkspaceKeepAliveHost(
    activeWorkspaceId = activeWorkspace?.id,
    liveWorkspaceIds = liveWorkspaceIds,
    showActive = !ui.launcherOpen && session != null && activeWorkspace != null,
) { workspaceId, isActive ->
    val current = workspaces.first { it.id == workspaceId }
    val workspaceSession = if (isActive) {
        session
    } else {
        current.primarySessionId?.let { id -> sessions.firstOrNull { it.id == id } }
            ?: current.chatSessionIds().firstNotNullOfOrNull { id -> sessions.firstOrNull { it.id == id } }
    }
}
```

Move the contiguous workspace body beginning with `val provisionalViews = remember(current.id)` and ending after the `closeCandidate` confirmation block into the callback, directly after `workspaceSession`. Preserve every statement in that range and make these exact substitutions inside it:

- `session?.id` fallback in `wsApp` becomes `workspaceSession?.id`.
- `session?.id` fallback passed through `appFor(...)` becomes `workspaceSession?.id`.
- The per-workspace `LaunchedEffect(current.id) { tabDragState.forgetAllBounds() }` is removed.

After the host, draw exactly one foreground branch with `Modifier.zIndex(2f)`:

- `ui.launcherOpen`: the existing launcher box and Escape handling.
- `session == null`: the existing “select a session” surface.
- `activeWorkspace == null`: the existing `workspace_welcome` surface.
- Otherwise: no foreground content, leaving the active cached layer visible.

This placement keeps cached layers composed while the launcher is open and keeps launcher/empty surfaces above all workspace layers.

- [ ] **Step 5: Gate effects that represent the visible workspace**

Inside the retained workspace callback:

```kotlin
LaunchedEffect(ui.externalOpen, current.id, isActive) {
    if (!isActive) return@LaunchedEffect
    val req = ui.externalOpen ?: return@LaunchedEffect
    val rel = workspaceOpenPath(req.second, current.workdir)
    if (rel == null) {
        println("[AppShell] externalOpen: '${req.second.path}' is outside workspace workdir '${current.workdir}' — dropped")
    } else {
        fileOpener.open(rel, req.second.line, req.second.endLine, sourceViewId = null)
    }
    ui.externalOpen = null
}

LaunchedEffect(ui.forceWorkspaceView, current.id, isActive) {
    if (!isActive) return@LaunchedEffect
    val req = ui.forceWorkspaceView ?: return@LaunchedEffect
    val gid = firstGroupId(localLayout)
    if (gid == null) {
        println("[AppShell] forceWorkspaceView: workspace layout has no group to open '${req.first}' into")
    } else {
        runCatching {
            wsApp.api.addView(
                current.id,
                AddViewBody(kind = req.first, state = req.second, groupId = gid),
            )
        }.onFailure { println("[AppShell] forceWorkspaceView failed: $it") }
    }
    ui.forceWorkspaceView = null
}

LaunchedEffect(localLayout, viewsById, isActive) {
    if (!isActive) return@LaunchedEffect
    workspaceViewingLayout = localLayout
    workspaceViewingViews = viewsById
}
```

Replace the three current effects with the complete versions above. Wrap the current `closeCandidate?.let` block in `if (isActive)` so a hidden layer cannot draw or consume the shared close dialog.

Outside the retained callback, add:

```kotlin
LaunchedEffect(activeWorkspace?.id) {
    tabDragState.forgetAllBounds()
    if (activeWorkspace == null) {
        workspaceViewingLayout = null
        workspaceViewingViews = emptyMap()
    }
}
```

This clears stale drag geometry at every actual switch and clears viewing presence when no workspace is selected. Workspace-scoped document stores, editor watchers, terminals, and JCEF instances remain alive until LRU eviction.

- [ ] **Step 6: Run the focused shell and keep-alive tests**

```bash
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test \
  --tests 'dev.supermux.desktop.shell.WorkspaceKeepAliveTest' \
  --tests 'dev.supermux.desktop.shell.AppShellTest' --console=plain
```

Expected: both test classes pass.

- [ ] **Step 7: Commit the shell integration**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/AppShell.kt \
  apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/AppShellTest.kt
git commit -m "fix(desktop): preserve recent workspace view state"
```

### Task 4: Verify the complete desktop change

**Files:**

- Verify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAlive.kt`
- Verify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/AppShell.kt`
- Verify: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceKeepAliveTest.kt`
- Verify: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/AppShellTest.kt`

- [ ] **Step 1: Run formatting/diff checks**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only the intended implementation files are changed or committed.

- [ ] **Step 2: Run the full desktop test suite from a clean test execution**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL` with zero failing desktop tests.

- [ ] **Step 3: Compile the production desktop source explicitly**

```bash
cd apps
./gradlew :desktop:compileKotlin --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Review the final diff against the design**

Confirm all of the following from the diff and tests:

- Cache keys are workspace IDs.
- Ten retained entries include the active workspace.
- The eleventh distinct workspace evicts the least recent.
- Removed workspaces are pruned.
- Hidden layers use `KeepAlivePanel`, not alpha-only hiding.
- Hidden layers cannot consume viewing presence, one-shot actions, drag bounds, or close dialogs.
- No broker protocol or other client changed.

- [ ] **Step 5: Record durable project knowledge**

Append a dated note to `/home/ahmet/.mux/domains/claudemux.md` documenting that Compose desktop retains an LRU of ten workspace compositions, that the active workspace counts toward ten, and that heavyweight workspace layers must be hidden through `KeepAlivePanel` at `0×0`.
