# Files Pane Rebuild (sub-project B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Files pane's tree with a fast, live, modern tree built on the host `FileSystemService` (sub-project A): one lazy list, shared folder data per host, per-pane view state that survives pane moves, live updates, breadcrumbs, reveal-active-file, icons, git badges, keyboard navigation, a context menu with file operations, fuzzy search, and the editor's "changed on disk" banner driven by folder subscriptions.

**Architecture:** Pure tree logic (`TreeViewState`, `flattenTree`, path helpers) in `apps/ui/.../ui/files/`, unit-tested without Compose. A `FileTreeView` composable subscribes (through `FileSystemService.subscribe`) to the root and every expanded folder, flattens their `DirState`s into rows and draws them in one `LazyColumn`. `ExplorerPane` and Android's `EditorPanel` sidebar use it; `ShellActions` hands the pane the workspace host's `FileSystemService`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (material3), kotlinx.coroutines, `runComposeUiTest` (jvmTest).

**Spec:** `docs/superpowers/specs/2026-09-27-host-filesystem-service-design.md` §5. **Depends on:** sub-project A (branch already contains it): `dev.supermux.fs.FileSystemService`, `DirState`, `snapshotOrPrevious`, `DirSnapshot`, `FsOpRequest`, `SearchHit`, `HostStore.fileSystem`, `ui/fs/CollectDir.kt`.

**Conventions for every task**
- Work from the worktree root. Gradle from `apps/`: `./gradlew :ui:jvmTest --tests '<FQN>' --max-workers=2`. On OOM kill, rerun once, then report it.
- `:ui:jvmTest` has ~13 known stale failures (touch-layout tests). Never "fix" them; report whether the count changed.
- GIT: the index is shared with other agents. `git add <files>` then `git commit -m "<msg>" -- <files>` (explicit pathspec). Retry on `index.lock` (1 s, up to 10). Never delete the lock, never stash, never reset. Message ends with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Keep the existing test tags: `editor_explorer_pane`, `editor_tree`, `editor-$workdir` (on the ViewHost modifier), `editor_stale_banner`.
- Paths inside the new tree are ABSOLUTE host paths. Everything that leaves the tree towards the existing editor/document code (`onOpenFile`, `DocumentStore`) is converted to a workdir-relative path with `relativeToWorkdir`.

**Deliberate scope decisions (from the spec's "decided" list, plus two made here)**
- View state is per device, in memory, keyed by view id (not synced).
- Delete goes to the OS trash (broker side) after a confirm dialog.
- Browsing above the workspace root is allowed via breadcrumbs. **Opening a file outside the workspace root is not supported in B** (the document/editor code is workdir-relative); the tree shows a short message instead of opening. Recorded in the final report.
- `fs_changed` / broker `FsWatcher` stay for Android's session-scoped `EditorPanel` stale banner; the workspace shell's stale banner moves to folder subscriptions (Task 11). Removing `fs_changed` entirely is a follow-up.

---

## File map

| File | Responsibility |
|---|---|
| Create `apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/TreePaths.kt` | `parentOf`, `childOf`, `ancestorsWithin`, `relativeToWorkdir`, `isWithin`, `displayName`. |
| Create `.../ui/files/TreeViewState.kt` | `TreeViewState` (root, expanded, selected, list state), `TreeViewStates` holder keyed by view id. |
| Create `.../ui/files/FlattenTree.kt` | `TreeRow`, `RowStatus`, `flattenTree`. |
| Create `.../ui/files/FileIcons.kt` | extension → icon glyph + colour. |
| Create `.../ui/files/FileTreeView.kt` | the composable: subscriptions, lazy list, rows, error rows, reveal. |
| Create `.../ui/files/FileTreeHeader.kt` | breadcrumbs + collapse-all + refresh. |
| Create `.../ui/files/FileTreeMenu.kt` | context menu + dialogs (new file/folder, rename, delete). |
| Create `.../ui/files/FileSearch.kt` | fuzzy search field + results with highlighted hits. |
| Modify `.../ui/shell/ShellActions.kt` | `fileSystemFor(workspaceId)` for both builders. |
| Modify `.../ui/shell/ViewHost.kt` | `ExplorerPaneForWorkspace` uses the new pane. |
| Modify `.../ui/editor/EditorPanes.kt` | `ExplorerPane` body replaced. |
| Modify `.../ui/editor/EditorPanel.kt` + `apps/android/.../chat/ChatScreen.kt`, `SessionChatFallback.kt` | sidebar uses `FileTreeView`. |
| Modify `.../ui/shell/SupermuxApp.kt` | workspace stale banner from folder subscriptions. |
| Delete `.../ui/editor/FileTree.kt`, `.../ui/editor/ExplorerState.kt`, `apps/shared/.../workspace/TreeNode.kt` and their tests once unused (Task 6). |
| Tests under `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/files/`. |

---

## B1 — the tree

### Task 1: Paths and view state (pure logic)

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/TreePaths.kt`, `.../files/TreeViewState.kt`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/files/TreePathsTest.kt`, `TreeViewStateTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// apps/ui/src/jvmTest/kotlin/dev/supermux/ui/files/TreePathsTest.kt
package dev.supermux.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreePathsTest {
    @Test fun parentAndChild() {
        assertEquals("/a/b", parentOf("/a/b/c"))
        assertEquals("/", parentOf("/a"))
        assertNull(parentOf("/"))
        assertEquals("/a/b", childOf("/a", "b"))
        assertEquals("/b", childOf("/", "b"))
    }

    @Test fun within() {
        assertTrue(isWithin("/w", "/w"))
        assertTrue(isWithin("/w", "/w/src/a.kt"))
        assertFalse(isWithin("/w", "/work/a.kt"))
        assertTrue(isWithin("/", "/anything"))
    }

    @Test fun ancestorsWithinRootExcludeThePathItself() {
        assertEquals(listOf("/w", "/w/src", "/w/src/ui"), ancestorsWithin("/w", "/w/src/ui/A.kt"))
        assertEquals(emptyList(), ancestorsWithin("/w", "/other/A.kt"))
    }

    @Test fun relativeToWorkdir() {
        assertEquals("src/a.kt", relativeToWorkdir("/w", "/w/src/a.kt"))
        assertNull(relativeToWorkdir("/w", "/x/a.kt"))
        assertEquals(".", relativeToWorkdir("/w", "/w"))
        assertEquals("a.kt", relativeToWorkdir("/w/", "/w/a.kt"))
    }

    @Test fun displayName() {
        assertEquals("a.kt", displayName("/w/a.kt"))
        assertEquals("/", displayName("/"))
    }
}
```

```kotlin
// apps/ui/src/jvmTest/kotlin/dev/supermux/ui/files/TreeViewStateTest.kt
package dev.supermux.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TreeViewStateTest {
    @Test fun toggleAndPruneDescendants() {
        val v = TreeViewState("/w")
        v.toggle("/w/src"); v.toggle("/w/src/ui"); v.toggle("/w/docs")
        assertEquals(setOf("/w/src", "/w/src/ui", "/w/docs"), v.expanded)
        v.prune("/w/src")
        assertEquals(setOf("/w/docs"), v.expanded)
        v.toggle("/w/docs")
        assertTrue(v.expanded.isEmpty())
    }

    @Test fun revealExpandsAncestorsAndSelects() {
        val v = TreeViewState("/w")
        v.reveal("/w/src/ui/A.kt")
        assertEquals(setOf("/w/src", "/w/src/ui"), v.expanded)
        assertEquals("/w/src/ui/A.kt", v.selected)
    }

    @Test fun holderKeepsStatePerViewAndResetsOnWorkdirChange() {
        val h = TreeViewStates()
        val a = h.forView("v1", workdir = "/w")
        a.toggle("/w/src")
        assertSame(a, h.forView("v1", workdir = "/w"))
        val b = h.forView("v1", workdir = "/other")
        assertNotSame(a, b)
        assertEquals("/other", b.rootPath)
        assertTrue(b.expanded.isEmpty())
        h.forget("v1")
        assertNotSame(b, h.forView("v1", workdir = "/other"))
    }
}
```

Run (from `apps/`): `./gradlew :ui:jvmTest --tests 'dev.supermux.ui.files.*' --max-workers=2`
Expected: compile FAIL (unresolved references).

- [ ] **Step 2: Implement**

```kotlin
// apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/TreePaths.kt
// Absolute-path helpers for the Files tree. The tree speaks host paths ("/home/u/p/src/a.kt");
// everything handed to the workdir-relative editor code goes through [relativeToWorkdir].
package dev.supermux.ui.files

private fun trimEnd(p: String): String = if (p.length > 1) p.trimEnd('/') else p

fun parentOf(path: String): String? {
    val p = trimEnd(path)
    if (p == "/") return null
    val i = p.lastIndexOf('/')
    return if (i <= 0) "/" else p.substring(0, i)
}

fun childOf(dir: String, name: String): String = if (trimEnd(dir) == "/") "/$name" else "${trimEnd(dir)}/$name"

fun isWithin(root: String, path: String): Boolean {
    val r = trimEnd(root)
    val p = trimEnd(path)
    return r == "/" || p == r || p.startsWith("$r/")
}

/** Folders from [root] down to [path]'s parent (inclusive of root), or empty if outside root. */
fun ancestorsWithin(root: String, path: String): List<String> {
    val r = trimEnd(root)
    if (!isWithin(r, path) || trimEnd(path) == r) return emptyList()
    val out = ArrayList<String>()
    var cur = parentOf(path)
    while (cur != null && isWithin(r, cur)) {
        out.add(0, cur)
        if (cur == r) break
        cur = parentOf(cur)
    }
    return out
}

/** Workdir-relative form of [path] ("." for the workdir itself), or null when outside it. */
fun relativeToWorkdir(workdir: String, path: String): String? {
    val w = trimEnd(workdir)
    val p = trimEnd(path)
    return when {
        p == w -> "."
        w == "/" -> p.removePrefix("/")
        p.startsWith("$w/") -> p.substring(w.length + 1)
        else -> null
    }
}

fun displayName(path: String): String = trimEnd(path).substringAfterLast('/').ifEmpty { "/" }
```

```kotlin
// apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/TreeViewState.kt
// What ONE Files pane is looking at. Folder CONTENTS live in the host's FileSystemService and are
// shared; this is only the view: where the tree starts, which folders are open, what's selected,
// and the scroll position. Held per view id in [TreeViewStates], not in `remember {}`, so dragging,
// splitting or re-tabbing the pane keeps it.
package dev.supermux.ui.files

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class TreeViewState(rootPath: String) {
    /** The workspace workdir this state was created for; [rootPath] may move above it. */
    val workdir: String = rootPath
    var rootPath by mutableStateOf(rootPath)
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set
    var selected by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    val list = LazyListState()

    fun isExpanded(path: String) = path in expanded
    fun expand(path: String) { expanded = expanded + path }
    fun toggle(path: String) {
        expanded = if (path in expanded) expanded.filterNot { it == path || isWithin(path, it) }.toSet() else expanded + path
    }
    /** Drop [path] and everything under it (folder gone, or collapsed). */
    fun prune(path: String) { expanded = expanded.filterNot { it == path || isWithin(path, it) }.toSet() }
    fun collapseAll() { expanded = emptySet() }

    /** Expand every ancestor of [path] under [rootPath] and select it. */
    fun reveal(path: String) {
        val anc = ancestorsWithin(rootPath, path).filter { it != rootPath }
        if (anc.isNotEmpty()) expanded = expanded + anc
        selected = path
    }
}

/** Per-host, in-memory holder: one [TreeViewState] per Files view id. */
class TreeViewStates {
    private val byView = HashMap<String, TreeViewState>()

    fun forView(viewId: String, workdir: String): TreeViewState {
        val cur = byView[viewId]
        if (cur != null && cur.workdir == workdir) return cur
        return TreeViewState(workdir).also { byView[viewId] = it }
    }

    fun forget(viewId: String) { byView.remove(viewId) }
}
```

Note: `toggle` collapsing a folder also collapses its descendants (matches the test).

- [ ] **Step 3: Run tests** — same command. Expected: PASS.
- [ ] **Step 4: Commit** — `feat(ui): absolute-path helpers and per-view tree state for the new Files tree`

---

### Task 2: Flatten

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/FlattenTree.kt`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/files/FlattenTreeTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package dev.supermux.ui.files

import dev.supermux.fs.DirSnapshot
import dev.supermux.fs.DirState
import dev.supermux.net.FsEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class FlattenTreeTest {
    private fun ready(path: String, vararg e: FsEntry) = DirState.Ready(DirSnapshot(path = path, version = "v", entries = e.toList()))
    private fun dir(n: String) = FsEntry(name = n, type = "dir")
    private fun file(n: String) = FsEntry(name = n, type = "file")

    @Test fun depthFirstWithDepthsAndStatuses() {
        val states = mapOf(
            "/w" to ready("/w", dir("src"), dir("docs"), file("README.md")),
            "/w/src" to ready("/w/src", dir("ui"), file("a.kt")),
            "/w/src/ui" to DirState.Loading(null),
            "/w/docs" to DirState.Failed("EACCES", "denied", null),
        )
        val v = TreeViewState("/w").apply { expand("/w/src"); expand("/w/src/ui"); expand("/w/docs") }
        val rows = flattenTree(v.rootPath, v.expanded) { states[it] ?: DirState.Unloaded }
        assertEquals(
            listOf("src:0:OPEN", "ui:1:LOADING", "a.kt:1:FILE", "docs:0:ERROR", "README.md:0:FILE"),
            rows.map { "${it.entry.name}:${it.depth}:${it.status}" },
        )
        assertEquals("denied", rows.first { it.entry.name == "docs" }.error)
    }

    @Test fun collapsedFoldersHideChildren_symlinkLoopsStop() {
        val states = mapOf(
            "/w" to ready("/w", FsEntry(name = "loop", type = "symlink", target = "dir")),
            "/w/loop" to DirState.Ready(DirSnapshot(path = "/w/loop", real = "/w", version = "v", entries = listOf(FsEntry(name = "loop", type = "symlink", target = "dir")))),
        )
        val rows = flattenTree("/w", setOf("/w/loop", "/w/loop/loop")) { states[it] ?: DirState.Unloaded }
        // /w/loop resolves to /w (an ancestor) → shown but not descended into.
        assertEquals(listOf("loop:0:LOOP"), rows.map { "${it.entry.name}:${it.depth}:${it.status}" })
    }

    @Test fun previousSnapshotShownWhileRefreshing() {
        val prev = DirSnapshot(path = "/w", version = "v1", entries = listOf(file("x")))
        val rows = flattenTree("/w", emptySet()) { DirState.Loading(prev) }
        assertEquals(listOf("x"), rows.map { it.entry.name })
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/FlattenTree.kt
package dev.supermux.ui.files

import dev.supermux.fs.DirState
import dev.supermux.fs.snapshotOrPrevious
import dev.supermux.net.FsEntry

enum class RowStatus { FILE, CLOSED, OPEN, LOADING, ERROR, LOOP }

data class TreeRow(val path: String, val depth: Int, val entry: FsEntry, val status: RowStatus, val error: String? = null)

val FsEntry.isDirLike: Boolean get() = type == "dir" || (type == "symlink" && target == "dir")

/**
 * Depth-first rows for [root] and every expanded folder, using each folder's current or previous
 * snapshot. A folder whose REAL path is already one of its ancestors' real paths (symlink loop) is
 * shown with [RowStatus.LOOP] and never descended into.
 */
fun flattenTree(root: String, expanded: Set<String>, dirOf: (String) -> DirState): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun realOf(path: String): String? = dirOf(path).snapshotOrPrevious?.real
    fun walk(dir: String, depth: Int, seenReals: Set<String>) {
        val entries = dirOf(dir).snapshotOrPrevious?.entries ?: return
        for (e in entries) {
            val p = childOf(dir, e.name)
            if (!e.isDirLike) { out += TreeRow(p, depth, e, RowStatus.FILE); continue }
            if (p !in expanded) { out += TreeRow(p, depth, e, RowStatus.CLOSED); continue }
            val st = dirOf(p)
            val real = realOf(p)
            if (real != null && real in seenReals) { out += TreeRow(p, depth, e, RowStatus.LOOP); continue }
            when (st) {
                is DirState.Failed -> out += TreeRow(p, depth, e, RowStatus.ERROR, st.message.ifBlank { st.code })
                is DirState.Loading -> if (st.previous == null) out += TreeRow(p, depth, e, RowStatus.LOADING) else {
                    out += TreeRow(p, depth, e, RowStatus.OPEN); walk(p, depth + 1, seenReals + (real ?: p))
                }
                is DirState.Ready -> { out += TreeRow(p, depth, e, RowStatus.OPEN); walk(p, depth + 1, seenReals + (real ?: p)) }
                DirState.Unloaded -> out += TreeRow(p, depth, e, RowStatus.LOADING)
                DirState.Gone -> out += TreeRow(p, depth, e, RowStatus.CLOSED)
            }
        }
    }
    walk(root, 0, setOfNotNull(realOf(root) ?: root))
    return out
}
```

- [ ] **Step 3: Run** `./gradlew :ui:jvmTest --tests 'dev.supermux.ui.files.FlattenTreeTest' --max-workers=2` → PASS.
- [ ] **Step 4: Commit** — `feat(ui): flatten the open folders of a tree into rows`

---

### Task 3: `fileSystemFor` in ShellActions

**Files:** Modify `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ShellActions.kt`.

- [ ] **Step 1:** Add to the `ShellActions` data (next to the workspace fs members):
```kotlin
    /** The host file-system service owning [workspaceId]'s host (spec 2026-09-27), or null offline. */
    val fileSystemFor: (workspaceId: String) -> dev.supermux.fs.FileSystemService? = { null },
```
In the HostStore builder: `fileSystemFor = { app.fileSystem },`. In the FleetStore builder: `fileSystemFor = { wsId -> fleet.appForWorkspace(wsId)?.fileSystem },`.
Also add `sessionFileSystem: (sessionId: String) -> FileSystemService?` the same way (`app.fileSystem` / `fleet.appFor(sessionId)?.fileSystem`) for Android's session-scoped EditorPanel. Check `FleetStore.appFor` exists (it does for per-session routing); use its real name.
- [ ] **Step 2:** `./gradlew :ui:compileKotlinJvm --max-workers=2` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `feat(ui): shell actions expose the host FileSystemService`

---

### Task 4: `FileTreeView` composable

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/FileTreeView.kt`, `.../files/FileIcons.kt`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/files/FileTreeViewTest.kt`

Behaviour:
- Subscribes to `view.rootPath` and every expanded folder whose parent chain is expanded (simply: every path in `expanded` that `isWithin(rootPath, it)`), each via `fileSystem.subscribe(path)` held in a `DisposableEffect` keyed on the SET of paths (diff the set: subscribe added, close removed; close all on dispose).
- Collects the `StateFlow<DirState>` of each subscribed path (a `mutableStateMapOf<String, DirState>` updated by one `LaunchedEffect` per path, or `combine` of flows) and computes `rows = remember { derivedStateOf { flattenTree(rootPath, expanded, lookup) } }`.
- `LazyColumn(state = view.list)` with `items(rows, key = { it.path })`.
- Row: indent (`depth * 14.dp + 8.dp`) with a 1-px vertical guide line per depth level (draw with `Modifier.drawBehind`), chevron for folders (`ChevronRight` / `KeyboardArrowDown`), a `CircularProgressIndicator(12.dp)` instead of the chevron for `LOADING` rows but only after 150 ms (a `LaunchedEffect(row.path, row.status) { delay(150); showSpinner = true }`), a file-type badge from `FileIcons.kt`, the name (monospace 13 sp, ellipsis), ignored entries at alpha 0.45, the selected row with `colorScheme.secondaryContainer` background, the active file (param `activePath`) in bold.
- Error rows: under a `ERROR` folder, one extra row (key `"${path}#error"`) with the message in `colorScheme.error`; clicking the folder retries (collapse + expand → re-subscribe; also `fileSystem.subscribe` for a Failed path re-sends `fs_sub` since the broker holds nothing).
- `DirState.Gone` for an expanded path → `view.prune(path)`.
- Click: folder → `view.toggle(path)`, `view.selected = path`; file → `view.selected = path; onOpenFile(path)`. Haptic `Tick` on click (existing `rememberHaptics()`), `pointerHoverIcon(PointerIcon.Hand)`.
- Root row: none — the root folder's entries are depth 0 (breadcrumbs show the root).
- Empty root (`Ready` with no entries): a centred "Empty folder" hint. Root `Failed`: the error text with a Retry button. Root `Loading(null)`: a small spinner.
- `activePath` param: when it changes and `revealActive` (param, default true) is on, `view.reveal(activePath)` if it is under `rootPath`, then `view.list.animateScrollToItem(index)` once the row exists (a `LaunchedEffect(activePath, rows.size)` that finds the index).
- Test tag `editor_tree` on the `LazyColumn`; each row `testTag("tree_row:${entry.name}")`.

`FileIcons.kt`: a `data class FileBadge(val label: String, val color: Color)` and `fun fileBadge(name: String, isDir: Boolean): FileBadge` mapping extensions: kt/kts `K` #7F52FF; ts/tsx `TS` #3178C6; js/mjs/cjs `JS` #E8C44A; json `{}` #E2A03F; md `MD` #5B6B7F; swift `S` #F05138; py `PY` #3776AB; rs `RS` #CE422B; go `GO` #00ADD8; java `J` #B07219; html `<>` #E34C26; css/scss `#` #563D7C; yml/yaml/toml `⚙` #6D8086; sh/zsh/bash `$` #4EAA25; gradle `G` #02303A; png/jpg/jpeg/gif/svg/webp `▣` #A074C4; lock `🔒`-free: `L` #8B8B85; dotfiles (name starts with ".") `•` #8B8B85; default: first letter of the extension uppercased or `·`, #8B8B85. Folders: `▸` #8B8B85 (drawn as a folder icon instead: use `Icons.Filled.Folder` / `FolderOpen` tinted `onSurfaceVariant`). Render the badge as a 16×16 rounded box with the label in 8–9 sp bold white.

- [ ] **Step 1: Failing UI test** — build a `FileSystemService` with a MockEngine `BrokerApi` whose sent frames are captured, and drive it by calling `fs.onFrame(ServerFrame.FsDir(...))`:

```kotlin
package dev.supermux.ui.files

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import dev.supermux.fs.FileSystemService
import dev.supermux.net.BrokerApi
import dev.supermux.net.FsEntry
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FileTreeViewTest {
    private fun service(sent: MutableList<ClientFrame>) = FileSystemService(
        BrokerApi("http://h", "t", HttpClient(MockEngine { respond("{}") })),
        send = { synchronized(sent) { sent += it } },
        scope = CoroutineScope(Dispatchers.Unconfined),
        graceMs = 0,
    )

    @Test fun rendersRootExpandsFoldersAndOpensFiles() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w")
        val opened = mutableListOf<String>()
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { FileTreeView(fs, view, onOpenFile = { opened += it }) } }
        waitForIdle()
        assertTrue(sent.contains(ClientFrame.FsSub("/w")))
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "src", type = "dir"), FsEntry(name = "a.kt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:src").assertIsDisplayed()
        onNodeWithTag("tree_row:src").performClick()
        waitForIdle()
        assertTrue(sent.contains(ClientFrame.FsSub("/w/src")))
        fs.onFrame(ServerFrame.FsDir(path = "/w/src", version = "1", entries = listOf(FsEntry(name = "b.kt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:b.kt").performClick()
        assertEquals(listOf("/w/src/b.kt"), opened)
    }

    @Test fun liveUpdatesAndGonePrunes() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w").apply { expand("/w/src") }
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { FileTreeView(fs, view, onOpenFile = {}) } }
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "src", type = "dir"))))
        fs.onFrame(ServerFrame.FsDir(path = "/w/src", version = "1", entries = listOf(FsEntry(name = "old.kt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:old.kt").assertIsDisplayed()
        fs.onFrame(ServerFrame.FsDir(path = "/w/src", version = "2", entries = listOf(FsEntry(name = "new.kt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:new.kt").assertIsDisplayed()
        fs.onFrame(ServerFrame.FsGone("/w/src"))
        waitForIdle()
        assertTrue("/w/src" !in view.expanded)
    }

    @Test fun onlyVisibleRowsAreComposed() = runComposeUiTest {
        val fs = service(mutableListOf())
        val view = TreeViewState("/w")
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { FileTreeView(fs, view, onOpenFile = {}) } }
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = (0 until 5000).map { FsEntry(name = "f%04d".format(it), type = "file") }))
        waitForIdle()
        onNodeWithTag("tree_row:f0000").assertIsDisplayed()
        onNodeWithTag("tree_row:f4999").assertDoesNotExist()
    }
}
```
Add `CompositionLocalProvider` for `LocalUiPrefs`/`LocalPlatform` if the theme or haptics require them (copy the `host {}` helper from `EditorPanesTest`).

- [ ] **Step 2: Implement** `FileIcons.kt` and `FileTreeView.kt` per the behaviour list. Signature:
```kotlin
@Composable
fun FileTreeView(
    fileSystem: FileSystemService,
    view: TreeViewState,
    onOpenFile: (absolutePath: String) -> Unit,
    modifier: Modifier = Modifier,
    activePath: String? = null,
    revealActive: Boolean = true,
    onRowContextMenu: ((TreeRow) -> Unit)? = null,   // wired in Task 9
)
```
- [ ] **Step 3: Run** `./gradlew :ui:jvmTest --tests 'dev.supermux.ui.files.*' --max-workers=2` → PASS.
- [ ] **Step 4: Commit** — `feat(ui): FileTreeView, a lazy live tree over the host FileSystemService`

---

### Task 5: Header (breadcrumbs, collapse all, refresh) and the Explorer pane on the new tree

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/FileTreeHeader.kt`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/EditorPanes.kt` (`ExplorerPane`), `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ViewHost.kt` (`ExplorerPaneForWorkspace`, and pass the active file path)
- Test: update `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/editor/EditorPanesTest.kt::the_explorer_pane_draws_its_tree`, `ViewHostTest.modeTreeDrawsTheExplorerPane` (keep their assertions on the tags)

- [ ] **Header:** a 32-dp row: breadcrumb segments of `view.rootPath` (show `~` for the user's home if the path starts with it — skip if the home path isn't known in common code; show the last 3 segments with a leading `…` when longer), each tappable → `view.rootPath = thatPath; view.collapseAll()`; when `rootPath != view.workdir` show a small "Workspace" chip that sets `rootPath = view.workdir`; trailing icon buttons "Collapse all" (`Icons.Filled.UnfoldLess`) and "Refresh" (`Icons.Filled.Refresh`, which calls a new `FileSystemService.refresh(path)` — add it in A's Kotlin service if missing: re-send `fs_sub` without `since` for a subscribed path; add a unit test for it in `FileSystemServiceTest`). Test tags `tree_breadcrumb:<name>`, `tree_collapse_all`, `tree_refresh`.
- [ ] **New `ExplorerPane` signature:**
```kotlin
@Composable
fun ExplorerPane(
    fileSystem: FileSystemService?,
    view: TreeViewState,
    workdir: String,
    onOpenFile: (relativePath: String) -> Unit,
    modifier: Modifier = Modifier,
    activeRelativePath: String? = null,
    onOutsideWorkdir: (absolutePath: String) -> Unit = {},
)
```
Body: `Column(Modifier.fillMaxSize().background(surfaceContainerHigh).testTag("editor_explorer_pane"))` with the search field (Task 10 replaces it; for now keep the old `EditorSearchField` + overlay wired to `fileSystem.search(workdir, q)` mapping `SearchHit.path` → relative), the header, a divider, then `FileTreeView` in `Box(Modifier.weight(1f).testTag("editor_tree"))`. `fileSystem == null` → a centred "Host offline" hint. `onOpenFile` from the tree: `relativeToWorkdir(workdir, abs)?.let(onOpenFile) ?: onOutsideWorkdir(abs)`. `activeRelativePath` → absolute via `childOf(workdir, rel)` (handle "a/b" by joining) → `FileTreeView(activePath = …)`.
- [ ] **ViewHost:** `ExplorerPaneForWorkspace` gets the host's `TreeViewStates` (add one per `ShellActions` owner: `val treeStates = remember { TreeViewStates() }` at the ViewHost/shell level that outlives panes — find the composable that owns `DocumentStore`s per workspace (`rememberWorkspaceDocuments` / `ws.documents`) and hold `TreeViewStates` next to it, keyed by workspace id), `view = treeStates.forView(viewId, workdir)`, `fileSystem = actions.fileSystemFor(workspaceId)`, `activeRelativePath` = the path of the workspace's active `file` view (the `ViewDto` with `isFileView()` that is active in its group — pass it down from where the layout is known; if that's not reachable without large plumbing, pass `null` and note it — Task 7 wires it), `onOutsideWorkdir` = show a snackbar/toast "Opening files outside the workspace isn't supported yet" through the existing snackbar mechanism (search for how ViewHost reports errors; if none, a transient `Text` banner in the pane for 3 s).
- [ ] Run `./gradlew :ui:jvmTest --tests 'dev.supermux.ui.editor.EditorPanesTest' --tests 'dev.supermux.ui.shell.ViewHostTest' --tests 'dev.supermux.ui.files.*' --max-workers=2` → PASS (update the ExplorerPane test to the new signature with a fake service as in Task 4).
- [ ] **Commit** — `feat(ui): the Files pane runs on the new tree with breadcrumbs`

---

### Task 6: Android EditorPanel sidebar on the new tree; delete the old tree

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/EditorPanel.kt`, `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt`, `SessionChatFallback.kt`
- Delete: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/FileTree.kt`, `ExplorerState.kt`, `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/TreeNode.kt`, and tests `FileTreeTest.kt`, `FileTreeWorkdirTest.kt` (their behaviour is covered by Tasks 2/4 tests), plus `EditorState`'s delegation to `ExplorerState` (`treeVisible`/`searchQuery` move onto `EditorState` itself as plain `mutableStateOf`s — keep their names so call sites don't change).
- [ ] `EditorPanel` gains `fileSystem: FileSystemService?` and `workdir` (it already has the session's workdir; check) and draws `FileTreeView` (with a `TreeViewState` remembered per session id in the panel — `remember(sessionId, workdir) { TreeViewState(workdir) }` is acceptable here because this legacy panel has no view ids) instead of the old `FileTree`. Android passes `vm.fleet.appFor(sessionId)?.fileSystem` (use the real accessor; `ShellActions.sessionFileSystem` from Task 3 if ChatScreen gets actions).
- [ ] `grep -rn "FileTree(\|ExplorerState\|TreeNode\|loadAndExpand" apps --include=*.kt` → only the new `files/` code remains.
- [ ] Compile: `./gradlew :ui:compileKotlinJvm :android:compileDebugKotlin :desktop:compileKotlinJvm --max-workers=2` (use the real desktop task name: `./gradlew :desktop:tasks --all | grep -i compileKotlin`). Web: `./gradlew :web:compileKotlinWasmJs --max-workers=2` (or the real wasm compile task).
- [ ] Tests: `./gradlew :ui:jvmTest --max-workers=2` — no new failures vs the known ~13.
- [ ] **Commit** — `refactor(ui): the old recursive file tree is gone; Android's editor sidebar uses FileTreeView`

---

## B2 — polish and consumers

### Task 7: Reveal the active file

- [ ] Wire `activeRelativePath` from the workspace layout into `ExplorerPaneForWorkspace` if Task 5 left it null: the active file = the active view of the group that most recently had focus among `file` views; if the shell tracks the focused group/view (search `activeViewId`, `focusedGroupId`), use that; else the first `file` view that is active in its group. Unit-test the chooser as a pure function in `apps/shared/.../workspace/` or `apps/ui/.../files/` (`fun activeFilePath(layout: LayoutNode, views: Map<String, ViewDto>, focusedViewId: String?): String?`).
- [ ] A pane-menu toggle "Reveal active file" (default on) stored in `LocalUiPrefs` if there is a simple boolean pref API (look at `UiPrefs`), else in `TreeViewState` (`var revealActive by mutableStateOf(true)`).
- [ ] Test: FileTreeView with `activePath = "/w/src/ui/A.kt"` expands `/w/src` and `/w/src/ui` (asserts `FsSub` frames sent and `view.selected`).
- [ ] **Commit** — `feat(ui): the Files tree follows the active editor file`

### Task 8: Keyboard navigation (desktop) and selection

- [ ] In `FileTreeView`, make the list focusable (`Modifier.focusable().onPreviewKeyEvent { … }`) and handle: ↑/↓ move `view.selected` to the previous/next row (scroll into view), → on a closed folder expands, on an open folder moves to its first child; ← on an open folder collapses, otherwise moves to the parent row; Enter opens a file / toggles a folder; Home/End first/last row; typing letters (within 700 ms) jumps to the next row whose name starts with the typed prefix (case-insensitive). F2 and Delete call `onRowContextMenu`-driven actions from Task 9 (`onRename(row)`, `onDelete(row)` params; no-ops until Task 9).
- [ ] Put the key logic in a pure function `fun treeKeyAction(key: TreeKey, rows: List<TreeRow>, selected: String?, expanded: Set<String>): TreeKeyResult` in `files/TreeKeys.kt` and unit-test it thoroughly (each key, edges: empty rows, selection not in rows, loop rows).
- [ ] **Commit** — `feat(ui): keyboard navigation in the Files tree`

### Task 9: Context menu and file operations

- [ ] `FileTreeMenu.kt`: a `DropdownMenu` anchored at the row (right-click via `Modifier.onPointerEvent(PointerEventType.Press)` with the secondary button on desktop; long-press via `combinedClickable(onLongClick = …)` on touch), items: New file…, New folder… (on folders: inside it; on files: in its parent), Rename…, Delete…, Copy path, Copy relative path (only when inside the workdir), and on phones the same list in a `ModalBottomSheet` if the codebase uses one for menus elsewhere (follow the existing pattern; `mux:android-development` guidance says bottom sheets for touch).
- [ ] Dialogs: name input with validation (non-empty, no `/`, not `.`/`..`, not an existing sibling name — check the parent's snapshot); delete confirm ("Move “name” to the trash?"). Operations call `fileSystem.op(FsOpRequest(...))` with absolute paths; on failure show the error message inline in the dialog (`EEXIST` → "A file with that name already exists"). New file → after success, open it (if inside workdir) and select it; new folder → expand the parent.
- [ ] Clipboard via `LocalClipboardManager.current.setText(AnnotatedString(path))`.
- [ ] Tests: dialog validation as a pure function (`validateNewName(name, siblings)`), and one UI test that the menu opens on long-press and "New folder…" sends a POST `/fs/ops` with `{"op":"mkdir","path":"/w/src/x"}` (MockEngine captures the request body).
- [ ] **Commit** — `feat(ui): Files tree context menu with new/rename/delete/copy path`

### Task 10: Fuzzy search UI

- [ ] `FileSearch.kt`: replace the Explorer pane's search field + overlay with a field (placeholder "Go to file…", ⌘P / Ctrl+P focuses it on desktop via the pane's key handler) and a results list: each result shows the file name with matched characters in bold (use `SearchHit.hits`, which index into the absolute path; convert to indexes in the displayed relative path by subtracting `workdir.length + 1`, clamp/drop out-of-range), the relative folder path dimmed underneath. 120 ms debounce; cancel the previous search (`LaunchedEffect(query)` naturally cancels). Enter opens the first/selected result; ↑/↓ move the selection; Esc clears. Empty query → the pane's recently opened files (keep an in-memory `ArrayDeque` of the last 10 opened relative paths in `TreeViewState`).
- [ ] Pure function `fun highlightRuns(display: String, hitIndexes: List<Int>): List<Pair<IntRange, Boolean>>` unit-tested.
- [ ] Remove the old `EditorSearchOverlay` usage from `ExplorerPane` (keep the composable if `EditorPanel` still uses it).
- [ ] **Commit** — `feat(ui): fuzzy Go-to-file in the Files pane`

### Task 11: Workspace stale banner from folder subscriptions

- [ ] In `SupermuxApp.kt` (around the `lspSession` `DisposableEffect`/`fsChanges` collector, ~line 1617): replace the workspace path's reliance on `fsChanges` with a `FileStaleWatcher`: for every open document in `ws.documents` (it exposes the open paths — find the property), subscribe to the file's parent folder (absolute = `childOf(workdir, rel)`'s parent) through `actions.fileSystemFor(workspaceId)`; remember the entry's `(mtime, size)` from the first snapshot seen after the document was opened; when a later snapshot shows a different `(mtime, size)` for that name — or the entry disappears — call `ws.documents.markChanged(listOf(rel))`. Put the comparison in a pure class `FileChangeTracker` (`fun onSnapshot(dir: String, snap: DirSnapshot): List<String /*changed abs paths*/>`, `track(absPath)`, `untrack(absPath)`) with unit tests (first sighting records, same values no-op, changed mtime reports once, deleted file reports).
- [ ] Keep the `editorOpen`/`editorClose` calls only if something else still needs `fs_changed` for this workspace (LSP does not); if not, remove them from this code path. Android's `EditorPanel` keeps its own `fsChanges` wiring.
- [ ] **Commit** — `feat(ui): the workspace "changed on disk" banner comes from folder subscriptions`

### Task 12: Chat file-path taps check the file exists

- [ ] Where a tapped path from chat is resolved (`ui.externalOpen` → `workspaceOpenPath(req.second, current.workdir)` in `SupermuxApp.kt`, and the equivalent for other entry points you find), call `fileSystem.stat(absolute)` first; on failure show the existing "not found" affordance (or a short snackbar "File not found: <path>") instead of opening an empty editor. Unit-test any pure helper you add; keep the change minimal.
- [ ] **Commit** — `feat(ui): tapping a missing file path in chat says so instead of opening an empty editor`

### Task 13: Full verification

- [ ] `bun test src/core/fs src/channels/web` (broker side unchanged by B, sanity).
- [ ] From `apps/`: `./gradlew :shared:jvmTest --max-workers=2`, `./gradlew :ui:jvmTest --max-workers=2` (known ~13 stale failures only), `./gradlew --stop`, then `./gradlew :android:compileDebugKotlin :desktop:<compile task> :web:<wasm compile task> --max-workers=2`.
- [ ] Manual (if a display/emulator is available; otherwise say so): desktop hot run or the web client against a preview broker (`mux:preview-broker`), open a big repo, expand `node_modules`, scroll, create a file from a terminal and watch it appear, rename via the menu, search with ⌘P.
- [ ] **Commit** any test/doc fixes — `test(ui): Files pane verification fixes`
