# Workspaces Phase 3 — Desktop Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Compose Desktop client speak workspaces — a workspace sidebar, then a real split/tab layout that replaces the fixed four-pane shell.

**Architecture:** `DesktopAppState` gains a workspace `StateFlow` fed by the eight new frames. A new shared `WorkspaceGrouping.kt` groups workspaces by project exactly as `SessionGrouping.kt` groups sessions today. The sidebar ships first and alone, because a one-view workspace row looks almost identical to today's session row — it proves the whole model end to end at near-zero visual risk. The split/tab layout follows, rendering the `LayoutNode` tree from Phase 2 with each leaf group drawn as a tab strip over a view host.

**Tech Stack:** Compose Multiplatform Desktop (`apps/desktop`), shared KMP (`apps/shared`), Compose UI tests via `runComposeUiTest` under Xvfb.

**Spec:** `docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md`, sections 8.3, 9.3, 13.3, and 13.6.

**Depends on:**
- `…-phase0-shell-rename.md` **Task 1 only** (the desktop rename). Tasks 2–4 of that plan cover Android, iOS, and web and are not needed here.
- `…-phase1-broker-data-model.md` (all)
- `…-phase1b-routes-and-frames.md` (all)
- `…-phase2-shared-layout-tree.md` (all)

⚠ **File paths in this plan assume the Phase 0 desktop rename has landed**: the package is `dev.supermux.desktop.shell`, the root composable is `AppShell`, and the local pane state is `ShellLayout` / `ShellUiState` / `ShellStateStore`. If you are reading this before that rename, substitute the old names.

---

## Order of work, and why

1. **State first** (Tasks 1–2). No pixels move. The client holds workspaces and can group them.
2. **A mock, then approval** (Task 3). ⚠ The memory digest records a full Android session-list redesign that was **built and then reverted** — "existing one was better" — because big per-row agent avatars made a heavy, repetitive wall. The durable rules from that: **list rows stay lean, no large per-row avatars**, and **mock a hero surface for approval BEFORE building it**. The session list is the most-looked-at surface in the app. Do not skip this gate.
3. **The sidebar** (Task 4). Ships alone and is releasable on its own.
4. **The layout tree** (Tasks 5–7). The part that actually changes how the app looks.
5. **Live verification** (Task 8).

Tasks 1–4 can merge without Tasks 5–8 existing.

---

## File structure

| File | Responsibility |
|---|---|
| `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceGrouping.kt` | **Create.** Group workspaces by project, derive a workspace's agent state and git label. Shared so the later Android/iOS plans reuse it. |
| `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/WorkspaceGroupingTest.kt` | **Create.** |
| `apps/desktop/.../state/DesktopAppState.kt` | **Modify.** A `workspaces` StateFlow plus the eight frame cases. |
| `apps/desktop/src/test/kotlin/.../state/WorkspaceReducerTest.kt` | **Create.** |
| `apps/desktop/.../shell/WorkspaceListPanel.kt` | **Create.** The sidebar rows of spec §13.6. |
| `apps/desktop/src/test/kotlin/.../shell/WorkspaceListPanelTest.kt` | **Create.** |
| `apps/desktop/.../shell/LayoutHost.kt` | **Create.** Renders a `LayoutNode` as nested resizable splits and tab groups. |
| `apps/desktop/src/test/kotlin/.../shell/LayoutHostTest.kt` | **Create.** |
| `apps/desktop/.../shell/ViewHost.kt` | **Create.** Maps one `ViewDto` to the chat / editor / terminal / display composable. |
| `apps/desktop/.../shell/CloseViewDialog.kt` | **Create.** The confirmation of spec §9.3. |
| `apps/desktop/.../shell/AppShell.kt` | **Modify.** Swap the fixed four-pane body for `LayoutHost`, and the session sidebar for `WorkspaceListPanel`. |

---

## Task 1: Group workspaces (shared KMP)

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceGrouping.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/WorkspaceGroupingTest.kt`

- [ ] **Step 1: Read the model this mirrors**

Open `apps/shared/src/commonMain/kotlin/dev/supermux/session/SessionGrouping.kt`. The new file mirrors it:

- `groupSessions()` groups by `repo_root ?: workdir` → `groupWorkspaces()` does the same on workspaces.
- `formatWorkdir()` and `inferHomeDir()` are reused as-is. **Do not reimplement them** — import them from `dev.supermux.session`.
- The Personal Assistants group stays pinned at the top and keeps the `PA_GROUP_KEY` sentinel.

⚠ `SessionGrouping.kt` carries a long comment about `lastTs` and decorate-sort-undecorate: a `Comparator` re-evaluates its selector on every comparison, which on Apple crosses the Kotlin/Native → Swift bridge thousands of times per render. The same rule applies here. Sort by a key computed once per row.

- [ ] **Step 2: Write the failing tests**

Create `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/WorkspaceGroupingTest.kt`:

```kotlin
package dev.supermux.workspace

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private fun chatView(id: String, sessionId: String, workspaceId: String = "w") = ViewDto(
    id = id, workspaceId = workspaceId, kind = "chat",
    state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
)

private fun ws(
    id: String, name: String, workdir: String,
    repoRoot: String? = null, sortOrder: Int = 0, views: List<ViewDto> = emptyList(),
) = WorkspaceDto(id = id, name = name, workdir = workdir, repoRoot = repoRoot, sortOrder = sortOrder, views = views)

class WorkspaceGroupingTest {

    @Test
    fun groupsByRepoRootFallingBackToWorkdir() {
        val a = ws("w1", "a", "/home/u/.mux/worktrees/x", repoRoot = "/home/u/projects/app")
        val b = ws("w2", "b", "/home/u/.mux/worktrees/y", repoRoot = "/home/u/projects/app")
        val c = ws("w3", "c", "/home/u/projects/other")

        val groups = groupWorkspaces(listOf(a, b, c), home = "/home/u")

        assertEquals(2, groups.size)
        assertEquals(listOf("w1", "w2"), groups.first { it.key == "/home/u/projects/app" }.workspaces.map { it.id })
        assertEquals(listOf("w3"), groups.first { it.key == "/home/u/projects/other" }.workspaces.map { it.id })
    }

    @Test
    fun groupsAreOrderedByLabelAndRowsBySortOrder() {
        val z = ws("w1", "z", "/home/u/projects/zeta", sortOrder = 5)
        val a1 = ws("w2", "a1", "/home/u/projects/alpha", sortOrder = 2)
        val a2 = ws("w3", "a2", "/home/u/projects/alpha", sortOrder = 1)

        val groups = groupWorkspaces(listOf(z, a1, a2), home = "/home/u")

        assertEquals(listOf("~/projects/alpha", "~/projects/zeta").map { it }, groups.map { it.label })
        assertEquals(listOf("w3", "w2"), groups[0].workspaces.map { it.id })
    }

    @Test
    fun archivedWorkspacesAreExcluded() {
        val live = ws("w1", "a", "/p")
        val dead = ws("w2", "b", "/p").copy(status = "archived")
        assertEquals(listOf("w1"), groupWorkspaces(listOf(live, dead), home = "/home/u").flatMap { it.workspaces.map { w -> w.id } })
    }

    @Test
    fun agentStateIsTheBusiestOfTheChatSessions() {
        val w = ws("w1", "a", "/p", views = listOf(chatView("v1", "s1"), chatView("v2", "s2")))
        val states = mapOf(
            "s1" to AgentStatus(working = false),
            "s2" to AgentStatus(working = true),
        )
        assertEquals(WorkspaceActivity.WORKING, workspaceActivity(w, states))
    }

    @Test
    fun agentStateIsIdleWhenNoChatSessionIsWorking() {
        val w = ws("w1", "a", "/p", views = listOf(chatView("v1", "s1")))
        assertEquals(WorkspaceActivity.IDLE, workspaceActivity(w, mapOf("s1" to AgentStatus(working = false))))
    }

    @Test
    fun agentStateIsNoneForAWorkspaceWithNoChatView() {
        val w = ws("w1", "a", "/p")
        assertEquals(WorkspaceActivity.NONE, workspaceActivity(w, emptyMap()))
    }

    @Test
    fun multiAgentIsTrueOnlyWithTwoOrMoreChatViews() {
        assertEquals(false, ws("w1", "a", "/p", views = listOf(chatView("v1", "s1"))).isMultiAgent())
        assertEquals(true, ws("w1", "a", "/p", views = listOf(chatView("v1", "s1"), chatView("v2", "s2"))).isMultiAgent())
    }

    @Test
    fun chatSessionIdsReadsTheStateObject() {
        val w = ws("w1", "a", "/p", views = listOf(chatView("v1", "s1"), chatView("v2", "s2")))
        assertEquals(listOf("s1", "s2"), w.chatSessionIds())
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*WorkspaceGroupingTest*'
```

Expected: FAIL — `Unresolved reference: groupWorkspaces`.

- [ ] **Step 4: Write the implementation**

Create `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceGrouping.kt`:

```kotlin
package dev.supermux.workspace

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.session.formatWorkdir

/**
 * Group workspaces by project for the sidebar (spec §13.6).
 *
 * Mirrors dev.supermux.session.SessionGrouping — same key (repo_root ?: workdir),
 * same label formatting, same ordering rules. Projects stay a CALCULATED value;
 * there is no project table (spec decision 7).
 */
data class WorkspaceGroup(
    /** The raw path the group keys on. */
    val key: String,
    /** The display label from [formatWorkdir]. */
    val label: String,
    val workspaces: List<WorkspaceDto>,
)

/** What the sidebar row's status dot shows. */
enum class WorkspaceActivity { NONE, IDLE, WORKING }

/** The sessions of every chat view, in view order. */
fun WorkspaceDto.chatSessionIds(): List<String> = views.mapNotNull { it.chatSessionId() }

/** Two or more live agents share this workspace's work tree (spec §10 risk control 2). */
fun WorkspaceDto.isMultiAgent(): Boolean = views.count { it.kind == "chat" } >= 2

/** The busiest state across the workspace's chat sessions. */
fun workspaceActivity(w: WorkspaceDto, agentState: Map<String, AgentStatus>): WorkspaceActivity {
    val ids = w.chatSessionIds()
    if (ids.isEmpty()) return WorkspaceActivity.NONE
    return if (ids.any { agentState[it]?.working == true }) WorkspaceActivity.WORKING
           else WorkspaceActivity.IDLE
}

/**
 * Active workspaces, grouped by project.
 *
 * Groups are ordered by label; rows inside a group follow sortOrder then id, so a
 * new message never reshuffles the list. Only an explicit user drag changes
 * sortOrder — the same rule SessionGrouping documents.
 */
fun groupWorkspaces(workspaces: List<WorkspaceDto>, home: String): List<WorkspaceGroup> {
    val live = workspaces.filter { it.status != "archived" }

    val byPath = LinkedHashMap<String, MutableList<WorkspaceDto>>()
    for (w in live) byPath.getOrPut(w.repoRoot ?: w.workdir) { mutableListOf() }.add(w)

    return byPath.map { (key, list) ->
        WorkspaceGroup(
            key = key,
            label = formatWorkdir(key, home),
            workspaces = list.sortedWith(compareBy({ it.sortOrder }, { it.id })),
        )
    }.sortedBy { it.label }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*WorkspaceGroupingTest*'
```

Expected: PASS, 8 tests.

⚠ If `AgentStatus` has no `working` property with that exact name, read `apps/shared/.../proto/Frames.kt` around `data class AgentStatus` and use the real field. The broker computes a canonical `working` boolean and ships it in the `agent_state` frame; every client renders it verbatim.

- [ ] **Step 6: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceGrouping.kt \
        apps/shared/src/commonTest/kotlin/dev/supermux/workspace/WorkspaceGroupingTest.kt
git commit -m "feat(shared): group workspaces by project

Mirrors SessionGrouping: same repo_root ?: workdir key, same label, same
ordering. Projects stay calculated — no project table. Adds workspaceActivity
(busiest chat session) and isMultiAgent for the sidebar row."
```

---

## Task 2: Hold workspaces in `DesktopAppState`

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/state/WorkspaceReducerTest.kt` (create)

- [ ] **Step 1: Read the reducer conventions**

Open `DesktopAppState.kt` at the `fun reduce(frame: ServerFrame)` block (around line 302). Note:

- `Snapshot` uses **plain assignment** for whole-list replacements, because assignment is atomic.
- Every read-modify-write goes through `.update { }`. The comment says why: the reducer coroutine and `appendLocalEcho` race, and a lost update is a lost message. The same hazard applies to a workspace list mutated by two frames arriving together.
- `SessionAdded` **dedups by id** because the broker re-broadcasts the same session. Do the same for `WorkspaceAdded`.

- [ ] **Step 2: Write the failing tests**

Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/state/WorkspaceReducerTest.kt`:

```kotlin
package dev.supermux.desktop.state

import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlin.test.Test
import kotlin.test.assertEquals

private fun ws(id: String, name: String = id, sortOrder: Int = 0, views: List<ViewDto> = emptyList()) =
    WorkspaceDto(
        id = id, name = name, workdir = "/w", sortOrder = sortOrder, views = views,
        layout = LayoutNodeDto.Group(id = "g-$id", viewIds = views.map { it.id }, activeViewId = views.firstOrNull()?.id),
    )

private fun view(id: String, workspaceId: String, kind: String = "editor") =
    ViewDto(id = id, workspaceId = workspaceId, kind = kind)

class WorkspaceReducerTest {

    @Test
    fun snapshotSeedsTheWorkspaceList() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1"), ws("w2"))))
        assertEquals(listOf("w1", "w2"), app.workspaces.value.map { it.id })
    }

    @Test
    fun snapshotFromAnOldBrokerLeavesTheListEmpty() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.Snapshot())
        assertEquals(emptyList(), app.workspaces.value)
    }

    @Test
    fun workspaceAddedAppends() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.WorkspaceAdded(ws("w1")))
        assertEquals(listOf("w1"), app.workspaces.value.map { it.id })
    }

    @Test
    fun workspaceAddedForAKnownIdReplacesRatherThanDuplicates() {
        // The broker re-broadcasts the same workspace (early add on spawn, then the
        // authoritative one carrying repo_root / branch) — same trap as SessionAdded.
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.WorkspaceAdded(ws("w1", name = "first")))
        app.reduce(ServerFrame.WorkspaceAdded(ws("w1", name = "second")))
        assertEquals(1, app.workspaces.value.size)
        assertEquals("second", app.workspaces.value[0].name)
    }

    @Test
    fun workspaceChangedReplacesInPlaceKeepingOrder() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1"), ws("w2"))))
        app.reduce(ServerFrame.WorkspaceChanged(ws("w1", name = "renamed")))
        assertEquals(listOf("w1", "w2"), app.workspaces.value.map { it.id })
        assertEquals("renamed", app.workspaces.value[0].name)
    }

    @Test
    fun workspaceChangedForAnUnknownIdIsIgnored() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.WorkspaceChanged(ws("ghost")))
        assertEquals(emptyList(), app.workspaces.value)
    }

    @Test
    fun workspaceRemovedDropsIt() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1"), ws("w2"))))
        app.reduce(ServerFrame.WorkspaceRemoved("w1"))
        assertEquals(listOf("w2"), app.workspaces.value.map { it.id })
    }

    @Test
    fun workspacesReorderedRewritesSortOrderByPosition() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1", sortOrder = 0), ws("w2", sortOrder = 1))))
        app.reduce(ServerFrame.WorkspacesReordered(listOf("w2", "w1")))
        val byId = app.workspaces.value.associateBy { it.id }
        assertEquals(0, byId["w2"]!!.sortOrder)
        assertEquals(1, byId["w1"]!!.sortOrder)
    }

    @Test
    fun viewAddedAppendsToItsWorkspace() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1"))))
        app.reduce(ServerFrame.ViewAdded("w1", view("v1", "w1")))
        assertEquals(listOf("v1"), app.workspaces.value[0].views.map { it.id })
    }

    @Test
    fun viewRemovedDropsIt() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1", views = listOf(view("v1", "w1"), view("v2", "w1"))))))
        app.reduce(ServerFrame.ViewRemoved("w1", "v1"))
        assertEquals(listOf("v2"), app.workspaces.value[0].views.map { it.id })
    }

    @Test
    fun viewChangedReplacesInPlace() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(ws("w1", views = listOf(view("v1", "w1"))))))
        app.reduce(ServerFrame.ViewChanged("w1", view("v1", "w1").copy(title = "renamed")))
        assertEquals("renamed", app.workspaces.value[0].views[0].title)
    }

    @Test
    fun viewMovedTakesItFromOneWorkspaceAndGivesItToTheOther() {
        val app = DesktopAppState.forTest()
        app.reduce(ServerFrame.Snapshot(workspaces = listOf(
            ws("w1", views = listOf(view("v1", "w1"))),
            ws("w2"),
        )))
        app.reduce(ServerFrame.ViewMoved("v1", "w1", "w2"))
        val byId = app.workspaces.value.associateBy { it.id }
        assertEquals(emptyList(), byId["w1"]!!.views.map { it.id })
        assertEquals(listOf("v1"), byId["w2"]!!.views.map { it.id })
        assertEquals("w2", byId["w2"]!!.views[0].workspaceId)
    }
}
```

⚠ `DesktopAppState.forTest()` may not exist. Look at how the existing reducer tests in `apps/desktop/src/test/kotlin/.../state/` build an instance and use that construction instead — do not add a new factory unless the existing tests have none.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*WorkspaceReducerTest*'
```

Expected: FAIL — `Unresolved reference: workspaces`.

- [ ] **Step 4: Add the state and the frame cases**

In `DesktopAppState.kt`, add the flow beside the other `_`-prefixed flows:

```kotlin
    private val _workspaces = MutableStateFlow<List<WorkspaceDto>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceDto>> = _workspaces
```

In `reduce`, inside the `is ServerFrame.Snapshot ->` branch, next to the other plain assignments:

```kotlin
                _workspaces.value = frame.workspaces
```

Then add the eight cases. Put them immediately after `is ServerFrame.SessionsReordered ->`:

```kotlin
            is ServerFrame.WorkspaceAdded -> {
                // The broker re-broadcasts the same workspace (early add on spawn, then the
                // authoritative one carrying repo_root / branch). Replace, never duplicate —
                // the same trap SessionAdded documents above.
                _workspaces.update { cur ->
                    if (cur.none { it.id == frame.workspace.id }) cur + frame.workspace
                    else cur.map { if (it.id == frame.workspace.id) frame.workspace else it }
                }
            }
            is ServerFrame.WorkspaceChanged -> {
                // Unknown id = a workspace this client never saw added. Ignore rather than
                // append: appending would put it at the end, out of sort order.
                _workspaces.update { cur ->
                    cur.map { if (it.id == frame.workspace.id) frame.workspace else it }
                }
            }
            is ServerFrame.WorkspaceRemoved -> {
                _workspaces.update { cur -> cur.filter { it.id != frame.id } }
            }
            is ServerFrame.WorkspacesReordered -> {
                val rank = frame.orderedIds.withIndex().associate { (i, id) -> id to i }
                _workspaces.update { cur ->
                    cur.map { w -> rank[w.id]?.let { w.copy(sortOrder = it) } ?: w }
                }
            }
            is ServerFrame.ViewAdded -> updateViews(frame.workspaceId) { it + frame.view }
            is ServerFrame.ViewRemoved -> updateViews(frame.workspaceId) { vs -> vs.filter { it.id != frame.viewId } }
            is ServerFrame.ViewChanged -> updateViews(frame.workspaceId) { vs ->
                vs.map { if (it.id == frame.view.id) frame.view else it }
            }
            is ServerFrame.ViewMoved -> {
                _workspaces.update { cur ->
                    var moved: ViewDto? = null
                    val stripped = cur.map { w ->
                        if (w.id != frame.fromWorkspaceId) w
                        else {
                            moved = w.views.firstOrNull { it.id == frame.viewId }
                            w.copy(views = w.views.filter { it.id != frame.viewId })
                        }
                    }
                    val v = moved ?: return@update stripped
                    stripped.map { w ->
                        if (w.id != frame.toWorkspaceId) w
                        else w.copy(views = w.views + v.copy(workspaceId = frame.toWorkspaceId))
                    }
                }
            }
```

Add the helper next to the other private helpers:

```kotlin
    /** Replace one workspace's view list. A frame for an unknown workspace is a no-op. */
    private fun updateViews(workspaceId: String, edit: (List<ViewDto>) -> List<ViewDto>) {
        _workspaces.update { cur ->
            cur.map { if (it.id == workspaceId) it.copy(views = edit(it.views)) else it }
        }
    }
```

⚠ `ViewMoved` deliberately does **not** touch either workspace's `layout`. The broker sends a `workspace_changed` for both workspaces right after `view_moved` (Phase 1b, Task 3), and that frame carries the authoritative trees. Rebuilding the layout here would fight it.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*WorkspaceReducerTest*'
```

Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/state/WorkspaceReducerTest.kt
git commit -m "feat(desktop): hold workspaces in DesktopAppState

The eight workspace frames plus the snapshot key. WorkspaceAdded replaces on a
known id rather than duplicating — the broker re-broadcasts, exactly as it does
for sessions. ViewMoved does not rebuild layouts; the workspace_changed frames
that follow carry the authoritative trees."
```

---

## Task 3: Mock the workspace row and get approval ⛔ GATE

**Files:** none merged. This task produces a screenshot and a decision.

⚠ **Do not build Task 4 until this task's answer arrives.** The digest is explicit: an Android session-list redesign was built and then reverted on sight, and the durable rule is to mock a hero surface for approval first. The session list is the surface the user looks at most in this app.

- [ ] **Step 1: Build the mock as a throwaway preview**

Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceRowMock.kt` — a test-source file, so it never ships:

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.ui.window.singleWindowApplication

/**
 * THROWAWAY. Renders the proposed workspace sidebar next to today's session
 * sidebar for a side-by-side screenshot. Delete once the row design is approved.
 *
 * Run: ./gradlew :desktop:test --tests '*WorkspaceRowMock*' is NOT how this runs.
 * Run it as a main() from the IDE, or add a temporary Gradle JavaExec task.
 */
fun main() = singleWindowApplication(title = "workspace row mock") {
    // Two columns: left = a copy of today's SessionListPanel with fake data,
    // right = the proposed WorkspaceListPanel with the same data as workspaces.
    // Populate both from the same fixture so the comparison is honest.
}
```

Fill the body with fixture data covering the cases that decide the design:

1. A workspace with one chat view (the common case — must look close to today's session row).
2. A workspace with two chat views (needs the multi-agent mark and the child rows).
3. A workspace with a chat, a terminal, and an editor.
4. A workspace whose agent is working (status dot).
5. Two projects, so the group headers show.
6. A long workspace name that must truncate.

- [ ] **Step 2: Hold the design rules while you build it**

From the memory digest, non-negotiable:

- **No large per-row avatars.** The small status rail is the wanted design. A wall of near-identical Claude marks is what got the last redesign reverted.
- **Keep the branch and the git status.** The user hard-rejected every concept that dropped them: "Nope do not remove the branch etc".
- Rows stay lean. A workspace row must not be visually heavier than today's session row.
- Geist for language, **Geist Mono for machine content** (paths, branches).
- One teal accent, used for state and agency only. Amber and red are semantic roles.
- **Motion budgeted by frequency.** The sidebar is a 100-plus-times-a-day surface: **no animation**.

- [ ] **Step 3: Screenshot it**

```bash
# with the mock window open
import -window root /tmp/workspace-row-mock.png    # or the platform's screenshot tool
```

- [ ] **Step 4: Send the screenshot and ask**

Send the image and ask exactly this:

> Left is today's session sidebar, right is the proposed workspace sidebar with the same data. A workspace with one chat view is the common case and looks nearly identical by design. Three things I need a yes or no on: (1) the row itself, (2) how a workspace with two agents shows its child sessions, (3) the multi-agent mark. Anything you want different?

Wait for the answer. Apply it before Task 4.

- [ ] **Step 5: Delete the mock**

```bash
rm apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceRowMock.kt
```

The mock is a conversation, not an artifact. It must not be committed.

---

## Task 4: The workspace sidebar

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/WorkspaceListPanel.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceListPanelTest.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/AppShell.kt`

- [ ] **Step 1: Write the failing tests**

Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceListPanelTest.kt`:

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private fun chatView(id: String, sessionId: String, wid: String) = ViewDto(
    id = id, workspaceId = wid, kind = "chat",
    state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
)

private fun ws(id: String, name: String, workdir: String, branch: String? = null, views: List<ViewDto> = emptyList()) =
    WorkspaceDto(
        id = id, name = name, workdir = workdir, branch = branch, views = views,
        layout = LayoutNodeDto.Group(id = "g", viewIds = views.map { it.id }),
    )

class WorkspaceListPanelTest {

    @Test
    fun showsOneRowPerWorkspaceUnderItsProjectHeader() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(
                    ws("w1", "Fix Renaming", "/home/u/projects/app"),
                    ws("w2", "Add Search", "/home/u/projects/app"),
                ),
                home = "/home/u",
                activeId = null,
                onOpen = {},
            )
        }
        onNodeWithText("Fix Renaming").assertIsDisplayed()
        onNodeWithText("Add Search").assertIsDisplayed()
        onNodeWithText("~/projects/app").assertIsDisplayed()
    }

    @Test
    fun showsTheBranch() = runComposeUiTest {
        // The user hard-rejected every list concept that dropped the branch.
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "a", "/p", branch = "mux/fix-renaming")),
                home = "/home/u", activeId = null, onOpen = {},
            )
        }
        onNodeWithText("mux/fix-renaming").assertIsDisplayed()
    }

    @Test
    fun aOneChatWorkspaceShowsNoChildRows() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "solo", "/p", views = listOf(chatView("v1", "s1", "w1")))),
                home = "/home/u", activeId = null, onOpen = {},
            )
        }
        onNodeWithTag("workspace-children-w1").assertDoesNotExist()
    }

    @Test
    fun aTwoChatWorkspaceShowsItsChildRowsAndTheMultiAgentMark() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "shared", "/p", views = listOf(
                    chatView("v1", "s1", "w1"), chatView("v2", "s2", "w1"),
                ))),
                home = "/home/u", activeId = null, onOpen = {},
                sessionNames = mapOf("s1" to "agent one", "s2" to "agent two"),
            )
        }
        onNodeWithTag("workspace-children-w1").assertIsDisplayed()
        onNodeWithText("agent one").assertIsDisplayed()
        onNodeWithText("agent two").assertIsDisplayed()
        onNodeWithTag("workspace-multiagent-w1").assertIsDisplayed()
    }

    @Test
    fun clickingARowOpensThatWorkspace() = runComposeUiTest {
        var opened: String? = null
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "a", "/p")),
                home = "/home/u", activeId = null, onOpen = { opened = it },
            )
        }
        onNodeWithText("a").performClick()
        assertEquals("w1", opened)
    }

    @Test
    fun clickingAChildRowOpensThatSessionsView() = runComposeUiTest {
        var openedSession: String? = null
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "shared", "/p", views = listOf(
                    chatView("v1", "s1", "w1"), chatView("v2", "s2", "w1"),
                ))),
                home = "/home/u", activeId = null, onOpen = {},
                sessionNames = mapOf("s1" to "agent one", "s2" to "agent two"),
                onOpenSession = { _, s -> openedSession = s },
            )
        }
        onNodeWithText("agent two").performClick()
        assertEquals("s2", openedSession)
    }

    @Test
    fun anArchivedWorkspaceIsNotListed() = runComposeUiTest {
        setContent {
            WorkspaceListPanel(
                workspaces = listOf(ws("w1", "gone", "/p").copy(status = "archived")),
                home = "/home/u", activeId = null, onOpen = {},
            )
        }
        onNodeWithText("gone").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*WorkspaceListPanelTest*'
```

Expected: FAIL — `Unresolved reference: WorkspaceListPanel`.

- [ ] **Step 3: Write the panel**

Create `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/WorkspaceListPanel.kt`.

Build it by **copy-adapting `SessionListPanel.kt`**, not from scratch. Reuse its `PathGroupHeader`, its row chrome, its `SessionStatusRail`, and its drag-reorder wiring. The differences from that file:

- The row model is `WorkspaceDto`, grouped by `groupWorkspaces(workspaces, home)` from Task 1 instead of `groupSessions`.
- The row shows: name, `formatWorkdir` label, `branch`, git status, and a status dot from `workspaceActivity(w, agentState)`.
- A workspace with `isMultiAgent()` true gets a small mark, tagged `workspace-multiagent-<id>`.
- A workspace with two or more views renders child rows for its chat sessions, in a container tagged `workspace-children-<id>`. A one-view workspace renders none.
- Drag-reorder calls `onReorder(orderedIds)`, which the caller wires to `BrokerApi.reorderWorkspaces`.

Signature:

```kotlin
@Composable
fun WorkspaceListPanel(
    workspaces: List<WorkspaceDto>,
    home: String,
    activeId: String?,
    onOpen: (String) -> Unit,
    agentState: Map<String, AgentStatus> = emptyMap(),
    /** session id → display name, for the child rows. */
    sessionNames: Map<String, String> = emptyMap(),
    onOpenSession: (workspaceId: String, sessionId: String) -> Unit = { _, _ -> },
    onRename: (String, String) -> Unit = { _, _ -> },
    onArchive: (String) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
    onNewWorkspace: () -> Unit = {},
    modifier: Modifier = Modifier,
)
```

⚠ Hold every rule from Task 3 Step 2. In particular: no large per-row avatars, keep the branch, no animation on this surface.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*WorkspaceListPanelTest*'
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Swap it into `AppShell`**

In `AppShell.kt`, replace the `SessionListPanel(...)` call in the sidebar slot with `WorkspaceListPanel(...)`, wiring:

- `workspaces` from `app.workspaces.collectAsState()`
- `agentState` from the existing `agentState` collection
- `sessionNames` from the existing `sessions` list, `associate { it.id to it.name }`
- `onOpen` to whatever `ui.selectedId` setter the session list used, now holding a **workspace id**
- `onReorder` to a coroutine calling `api.reorderWorkspaces(ids)`

⚠ `ui.selectedId` changes meaning from session id to workspace id. Grep every read of it and fix each one. `AppShell` currently derives the detail pane from it; Task 5 replaces that derivation entirely, so a temporary "open the workspace's first chat session" bridge is acceptable for this task only. Mark it with a `// TODO(phase3-task5)` comment and delete it in Task 5.

- [ ] **Step 6: Run the full desktop suite**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test
```

Expected: `BUILD SUCCESSFUL`. Existing `AppShellTest` cases that assert on session rows will fail — they are asserting the old sidebar. Update them to assert workspace rows, and say so in the commit.

- [ ] **Step 7: Commit**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/WorkspaceListPanel.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/WorkspaceListPanelTest.kt \
        apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/AppShell.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/AppShellTest.kt
git commit -m "feat(desktop): the sidebar lists workspaces

Spec 13.6. Copy-adapted from SessionListPanel: same group headers, same status
rail, same drag-reorder. A one-view workspace renders as a single lean row; two
or more chats render child rows plus a multi-agent mark.

Row design approved by the user before it was built."
```

---

## Task 5: Render the layout tree

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/LayoutHost.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/LayoutHostTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/LayoutHostTest.kt`:

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutHostTest {

    @Test
    fun aSingleGroupRendersItsActiveViewOnly() = runComposeUiTest {
        setContent {
            LayoutHost(
                layout = LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
                onLayoutChange = {},
            ) { viewId -> Text("body-$viewId") }
        }
        onNodeWithText("body-v1").assertIsDisplayed()
        onNodeWithText("body-v2").assertDoesNotExist()
    }

    @Test
    fun aGroupRendersOneTabPerView() = runComposeUiTest {
        setContent {
            LayoutHost(
                layout = LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
                titleFor = { "tab-$it" },
                onLayoutChange = {},
            ) { Text("body") }
        }
        onNodeWithText("tab-v1").assertIsDisplayed()
        onNodeWithText("tab-v2").assertIsDisplayed()
    }

    @Test
    fun clickingATabReportsTheNewActiveViewThroughOnLayoutChange() = runComposeUiTest {
        var next: LayoutNode? = null
        setContent {
            LayoutHost(
                layout = LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
                titleFor = { "tab-$it" },
                onLayoutChange = { next = it },
            ) { Text("body") }
        }
        onNodeWithText("tab-v2").performClick()
        assertEquals(LayoutNode.Group("g1", listOf("v1", "v2"), "v2"), next)
    }

    @Test
    fun aRowSplitRendersBothChildren() = runComposeUiTest {
        setContent {
            LayoutHost(
                layout = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                    LayoutNode.Group("g1", listOf("v1"), "v1"),
                    LayoutNode.Group("g2", listOf("v2"), "v2"),
                )),
                onLayoutChange = {},
            ) { viewId -> Text("body-$viewId") }
        }
        onNodeWithText("body-v1").assertIsDisplayed()
        onNodeWithText("body-v2").assertIsDisplayed()
    }

    @Test
    fun aNestedSplitRendersEveryLeaf() = runComposeUiTest {
        setContent {
            LayoutHost(
                layout = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                    LayoutNode.Group("g1", listOf("v1"), "v1"),
                    LayoutNode.Split("column", listOf(0.5, 0.5), listOf(
                        LayoutNode.Group("g2", listOf("v2"), "v2"),
                        LayoutNode.Group("g3", listOf("v3"), "v3"),
                    )),
                )),
                onLayoutChange = {},
            ) { viewId -> Text("body-$viewId") }
        }
        onNodeWithText("body-v1").assertIsDisplayed()
        onNodeWithText("body-v2").assertIsDisplayed()
        onNodeWithText("body-v3").assertIsDisplayed()
    }

    @Test
    fun aSplitterExistsBetweenEveryPairOfChildren() = runComposeUiTest {
        setContent {
            LayoutHost(
                layout = LayoutNode.Split("row", listOf(0.33, 0.33, 0.34), listOf(
                    LayoutNode.Group("g1", listOf("v1"), "v1"),
                    LayoutNode.Group("g2", listOf("v2"), "v2"),
                    LayoutNode.Group("g3", listOf("v3"), "v3"),
                )),
                onLayoutChange = {},
            ) { Text("body") }
        }
        onNodeWithTag("splitter-0").assertIsDisplayed()
        onNodeWithTag("splitter-1").assertIsDisplayed()
        onNodeWithTag("splitter-2").assertDoesNotExist()
    }

    @Test
    fun closingATabReportsTheViewIdRatherThanEditingTheTree() = runComposeUiTest {
        // A close ENDS work (spec 9.3). LayoutHost must not silently drop the view
        // from the tree — the caller confirms, calls the broker, and the frame
        // comes back. Only report.
        var closed: String? = null
        setContent {
            LayoutHost(
                layout = LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
                titleFor = { "tab-$it" },
                onLayoutChange = {},
                onCloseView = { closed = it },
            ) { Text("body") }
        }
        onNodeWithTag("tab-close-v1").performClick()
        assertEquals("v1", closed)
    }

    @Test
    fun anEmptyGroupRendersThePlaceholderRatherThanCrashing() = runComposeUiTest {
        setContent {
            LayoutHost(layout = LayoutNode.Group("g1", emptyList(), null), onLayoutChange = {}) { Text("body") }
        }
        onNodeWithTag("layout-empty").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*LayoutHostTest*'
```

Expected: FAIL — `Unresolved reference: LayoutHost`.

- [ ] **Step 3: Write the host**

Create `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/LayoutHost.kt`:

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.normalizeLayout

/**
 * Renders a workspace [LayoutNode] as nested resizable splits with tab groups at
 * the leaves.
 *
 * This composable is PURE PRESENTATION over the tree. It never edits the tree
 * itself: a tab click and a splitter drag report the new tree through
 * [onLayoutChange], and a tab close reports only the view id through
 * [onCloseView]. A close ends real work (spec §9.3) — the caller confirms with
 * the user, calls the broker, and the resulting workspace_changed frame is what
 * finally changes what is drawn.
 *
 * [content] draws one view's body. The caller maps the view id to a chat, an
 * editor, a terminal, or a display (see ViewHost.kt).
 */
@Composable
fun LayoutHost(
    layout: LayoutNode,
    onLayoutChange: (LayoutNode) -> Unit,
    modifier: Modifier = Modifier,
    titleFor: (String) -> String = { it },
    onCloseView: (String) -> Unit = {},
    content: @Composable (viewId: String) -> Unit,
) {
    when (layout) {
        is LayoutNode.Group -> GroupHost(layout, onLayoutChange, modifier, titleFor, onCloseView, content)
        is LayoutNode.Split -> SplitHost(layout, onLayoutChange, modifier, titleFor, onCloseView, content)
    }
}

@Composable
private fun GroupHost(
    group: LayoutNode.Group,
    onLayoutChange: (LayoutNode) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    content: @Composable (String) -> Unit,
) {
    if (group.viewIds.isEmpty()) {
        // A workspace whose last view just closed. Valid, not an error (spec §9.3
        // answer 3: the workspace stays open).
        Box(modifier.fillMaxSize().testTag("layout-empty"), contentAlignment = Alignment.Center) {
            EmptyWorkspaceHint()
        }
        return
    }
    val active = group.activeViewId ?: group.viewIds.first()
    Column(modifier.fillMaxSize()) {
        ViewTabStrip(
            viewIds = group.viewIds,
            activeViewId = active,
            titleFor = titleFor,
            onSelect = { onLayoutChange(group.copy(activeViewId = it)) },
            onClose = onCloseView,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { content(active) }
    }
}

@Composable
private fun SplitHost(
    split: LayoutNode.Split,
    onLayoutChange: (LayoutNode) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    content: @Composable (String) -> Unit,
) {
    // Reuse the existing ResizableSplit drag chrome rather than writing new
    // splitter hit-testing: it already handles the 48dp handle and the pointer
    // cursor, and it is the widget the rest of the app drags.
    ResizableSplitN(
        direction = split.direction,
        sizes = split.sizes,
        onSizesChange = { next -> onLayoutChange(split.copy(sizes = next)) },
        modifier = modifier,
    ) { index ->
        LayoutHost(
            layout = split.children[index],
            onLayoutChange = { child ->
                val next = split.copy(children = split.children.toMutableList().also { it[index] = child })
                // normalize keeps the tree valid if a child collapsed to nothing.
                onLayoutChange(normalizeLayout(next) ?: child)
            },
            titleFor = titleFor,
            onCloseView = onCloseView,
            content = content,
        )
    }
}
```

- [ ] **Step 4: Write the two widgets it needs**

`ViewTabStrip` and `ResizableSplitN` go in the same file, below `LayoutHost`.

- **`ViewTabStrip(viewIds, activeViewId, titleFor, onSelect, onClose)`** — a `Row` of tabs. Each tab is a clickable label plus a close affordance tagged `tab-close-<viewId>`. The active tab is marked with the teal accent. **No animation** — this strip changes many times a day.

- **`ResizableSplitN(direction, sizes, onSizesChange, modifier, child: @Composable (index) -> Unit)`** — generalizes the existing two-pane `ResizableSplit.kt` to N children. `n` children means `n - 1` splitters, tagged `splitter-0` … `splitter-(n-2)`. A drag on splitter `i` moves weight between children `i` and `i + 1` only, and the total stays 1.

⚠ Read `apps/desktop/.../shell/ResizableSplit.kt` first and match its handle size and cursor behaviour. A second splitter that feels different from the existing one is worse than no split at all.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*LayoutHostTest*'
```

Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/LayoutHost.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/LayoutHostTest.kt
git commit -m "feat(desktop): render the workspace layout tree

Nested resizable splits with tab groups at the leaves. Pure presentation: a tab
click reports a new tree, a close reports only the view id. A close ends real
work, so the broker frame is what actually removes the tab."
```

---

## Task 6: Map a view to its body

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/ViewHost.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/AppShell.kt`

- [ ] **Step 1: Read the heavyweight-child constraint**

⚠ This is the highest-risk task in the plan. The digest records it plainly: **JediTerm and KCEF are heavyweight AWT `SwingPanel` children.** Compose paints nothing above them. Three rules follow, and breaking any one of them produces a bug that only shows at runtime:

1. **Build them only when realized at non-zero size.** A tab that is not active must not hold a live KCEF instance. The old shell had at most one editor; a tab tree can ask for six.
2. **Swap the pane, never overlay it.** An overlay drawn above KCEF is invisible.
3. **Marshal input off non-EDT threads.**

The practical consequence for this task: `ViewHost` must render **only the active view of each group**. `LayoutHost` already does that — do not "optimize" by keeping inactive tabs composed.

- [ ] **Step 2: Write the host**

Create `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/ViewHost.kt`:

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.proto.ViewDto
import dev.supermux.proto.stateString
import dev.supermux.proto.chatSessionId

/**
 * Draw one view's body.
 *
 * Only the ACTIVE view of each group reaches here — LayoutHost composes nothing
 * else. That is load-bearing, not an optimization: the terminal and the editor
 * are heavyweight AWT SwingPanel children, and one live KCEF per background tab
 * would exhaust memory. Do not compose an inactive tab.
 *
 * An unknown kind draws a hint rather than throwing. A future view kind must
 * degrade to "this client does not draw that yet".
 */
@Composable
fun ViewHost(
    view: ViewDto,
    workspaceId: String,
    workdir: String,
    modifier: Modifier = Modifier,
) {
    when (view.kind) {
        "chat" -> {
            val sessionId = view.chatSessionId()
            if (sessionId == null) UnknownViewHint(view.kind, modifier)
            else ChatPanelForSession(sessionId, modifier)
        }
        "terminal" -> {
            val scope = view.stateString("scope") ?: "workspace"
            val terminalId = view.stateString("terminalId") ?: "main"
            // Phase 4 adds the workspace scope to the broker. Until it lands, a
            // workspace-scoped terminal has nothing to attach to — draw the hint.
            if (scope == "session") {
                val sessionId = view.stateString("sessionId")
                if (sessionId == null) UnknownViewHint(view.kind, modifier)
                else AgentTerminalForSession(sessionId, terminalId, modifier)
            } else {
                WorkspaceTerminalPending(modifier)   // replaced in Phase 4
            }
        }
        "editor" -> EditorPanelForWorkdir(
            workdir = workdir,
            path = view.stateString("path"),
            mode = view.stateString("mode") ?: "tree",
            modifier = modifier,
        )
        "display" -> {
            val displayId = view.stateString("displayId")
            if (displayId == null) UnknownViewHint(view.kind, modifier)
            else DisplayPanelForStream(displayId, modifier)
        }
        else -> UnknownViewHint(view.kind, modifier)
    }
}
```

⚠ `ChatPanelForSession`, `AgentTerminalForSession`, `EditorPanelForWorkdir`, and `DisplayPanelForStream` are thin adapters you must write around the existing panels. Open `apps/desktop/.../shell/SessionDetail.kt` and copy how it calls the chat, editor, terminal, and display composables today — including every parameter it passes. Do **not** invent new call shapes.

- [ ] **Step 3: Swap `AppShell`'s body**

In `AppShell.kt`, replace the fixed four-pane body with:

```kotlin
    val workspaces by app.workspaces.collectAsState()
    val current = workspaces.firstOrNull { it.id == ui.selectedId }
    if (current == null) {
        ShellWelcome()
    } else {
        val tree = current.layout.toDomainOrNull() ?: LayoutNode.Group(id = "g", viewIds = emptyList())
        val viewsById = remember(current) { current.views.associateBy { it.id } }
        LayoutHost(
            layout = tree,
            titleFor = { id -> viewsById[id]?.let { viewTitle(it) } ?: "view" },
            onCloseView = { closeCandidate = viewsById[it] },
            onLayoutChange = { next ->
                scope.launch {
                    runCatching { api.patchWorkspace(current.id, PatchWorkspaceBody(layout = next.toDto())) }
                }
            },
        ) { viewId ->
            val v = viewsById[viewId]
            if (v != null) ViewHost(v, current.id, current.workdir)
        }
    }
```

Then delete the `// TODO(phase3-task5)` bridge added in Task 4 Step 5.

⚠ **`onLayoutChange` writes to the server on every splitter drag.** Debounce it. A drag emits a frame per pointer move, and one PATCH per move floods the broker and every other device. Hold the tree in local state, render from local, and PATCH at most once every 300 ms plus once on drag end.

⚠ **Do not write the layout back from a narrow window.** Spec §8.3: small clients read the layout and never write it. A desktop window is always wide, so this client always writes — but keep the write in one place so the rule is easy to apply when the phone plans land.

- [ ] **Step 4: Run the full desktop suite**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test
```

Expected: `BUILD SUCCESSFUL`. Existing `AppShellTest` and `SessionDetailTest` cases that assert on the four-pane layout will fail — they assert a shell that no longer exists. Rewrite them against the tree, and say so in the commit.

- [ ] **Step 5: Commit**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/ViewHost.kt \
        apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/AppShell.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/
git commit -m "feat(desktop): the workspace layout replaces the four-pane shell

ViewHost maps a ViewDto to its body; only the active view of each group is
composed, which is load-bearing — JediTerm and KCEF are heavyweight AWT
children and one live KCEF per background tab would exhaust memory.

Layout writes are debounced: a splitter drag emits a frame per pointer move,
and one PATCH per move would flood the broker and every peer device."
```

---

## Task 7: Confirm before a close that ends work

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/CloseViewDialog.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/CloseViewDialogTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/CloseViewDialogTest.kt`:

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.ViewDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private fun view(kind: String, state: Map<String, String> = emptyMap()) = ViewDto(
    id = "v1", workspaceId = "w1", kind = kind,
    state = JsonObject(state.mapValues { JsonPrimitive(it.value) }),
)

class CloseViewDialogTest {

    @Test
    fun aChatCloseNamesTheSessionItArchives() = runComposeUiTest {
        setContent {
            CloseViewDialog(
                view = view("chat", mapOf("sessionId" to "s1")),
                sessionNames = mapOf("s1" to "Fix Session Renaming"),
                onConfirm = {}, onDismiss = {},
            )
        }
        onNodeWithText("Close this chat? This archives the session Fix Session Renaming.").assertIsDisplayed()
    }

    @Test
    fun aTerminalCloseSaysItKillsTheTerminal() = runComposeUiTest {
        setContent {
            CloseViewDialog(view = view("terminal", mapOf("terminalId" to "main")), onConfirm = {}, onDismiss = {})
        }
        onNodeWithText("Close this terminal? This stops the terminal main.").assertIsDisplayed()
    }

    @Test
    fun aDisplayCloseSaysItStopsTheStream() = runComposeUiTest {
        setContent {
            CloseViewDialog(view = view("display", mapOf("displayId" to "d1")), onConfirm = {}, onDismiss = {})
        }
        onNodeWithText("Close this display? This stops the stream.").assertIsDisplayed()
    }

    @Test
    fun confirmingCallsOnConfirm() = runComposeUiTest {
        var confirmed = false
        setContent {
            CloseViewDialog(view = view("terminal"), onConfirm = { confirmed = true }, onDismiss = {})
        }
        onNodeWithText("Close").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun theDialogHasExactlyTwoActionsAndNeitherIsAFinishAction() = runComposeUiTest {
        // Spec 9.3: the confirmation is one question with two buttons. It is NOT
        // the Finish flow — no Merge, no Open PR, no Keep, no Discard.
        setContent {
            CloseViewDialog(view = view("chat", mapOf("sessionId" to "s1")), onConfirm = {}, onDismiss = {})
        }
        onNodeWithText("Close").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
        onNodeWithText("Merge locally").assertDoesNotExist()
        onNodeWithText("Open PR").assertDoesNotExist()
        onNodeWithText("Discard").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*CloseViewDialogTest*'
```

Expected: FAIL — `Unresolved reference: CloseViewDialog`.

- [ ] **Step 3: Write the dialog**

Create `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/CloseViewDialog.kt`:

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString

/**
 * Spec §9.3 — a close ends the work behind the view, so the user is asked first.
 *
 * ONE question, TWO buttons. This is deliberately NOT the Finish flow: the user
 * was explicit that closing a chat settles only that view, with no Merge / Open
 * PR / Keep / Discard. The work tree and the branch stay on disk; Finish stays
 * available later from the archived row and the workspace menu.
 *
 * An editor view never reaches here — closing one stops nothing.
 */
@Composable
fun CloseViewDialog(
    view: ViewDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    sessionNames: Map<String, String> = emptyMap(),
) {
    val message = when (view.kind) {
        "chat" -> {
            val name = view.chatSessionId()?.let { sessionNames[it] } ?: "this session"
            "Close this chat? This archives the session $name."
        }
        "terminal" -> "Close this terminal? This stops the terminal ${view.stateString("terminalId") ?: "?"}."
        "display" -> "Close this display? This stops the stream."
        else -> "Close this view?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Close") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** True for the kinds whose close ends real work. An editor close is silent. */
fun ViewDto.closeNeedsConfirmation(): Boolean = kind != "editor"
```

- [ ] **Step 4: Wire it into `AppShell`**

Where Task 6 set `closeCandidate`, add:

```kotlin
    closeCandidate?.let { v ->
        if (!v.closeNeedsConfirmation()) {
            LaunchedEffect(v.id) {
                runCatching { api.closeView(v.workspaceId, v.id) }
                closeCandidate = null
            }
        } else {
            CloseViewDialog(
                view = v,
                sessionNames = sessionNames,
                onDismiss = { closeCandidate = null },
                onConfirm = {
                    scope.launch {
                        runCatching { api.closeView(v.workspaceId, v.id) }
                        closeCandidate = null
                    }
                },
            )
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/CloseViewDialog.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/CloseViewDialogTest.kt \
        apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/AppShell.kt
git commit -m "feat(desktop): confirm a close that ends work

Spec 9.3: one question, two buttons, naming what stops. Explicitly not the
Finish flow — a test asserts no Merge / Open PR / Discard action exists on this
dialog. An editor close skips the dialog because it stops nothing."
```

---

## Task 8: Live verification against the real broker

**Files:** none. This task runs the app and reads the screen.

⚠ Every milestone of the original desktop client was live-verified against the real broker, and the digest records that this loop caught bugs static review missed — an invisible markdown preview behind the browser, cross-session leaks, a permanently-stuck upload chip. Do not skip it.

- [ ] **Step 1: Start a broker on this machine**

```bash
bun src/main.ts
```

- [ ] **Step 2: Run the desktop client with hot reload**

```bash
cd apps
./gradlew :desktop:hotRun --auto
```

⚠ `hotRun` needs a JetBrains Runtime. On a headless Linux box the window cannot open at all — run this from a desktop session, or on the Mac.

- [ ] **Step 3: Walk the checklist**

Confirm each, and screenshot the ones marked 📷:

1. 📷 The sidebar lists workspaces, grouped by project, with branch and git status on each row.
2. A workspace with one chat opens and looks like today's session view.
3. Creating a new session from the launcher adds a workspace row, not an orphan session.
4. Renaming a session in the chat header renames its workspace row **live**, with no restart.
5. 📷 Adding a second view produces a tab strip. Both tabs switch.
6. 📷 Dragging a view into a split produces two panes. The splitter drags and holds its position.
7. Closing an editor tab is silent. Closing a chat tab asks first, then the row leaves the sidebar and lands in Settled.
8. Closing a chat tab does **not** open the Finish flow.
9. The layout survives a client restart — it came back from the server, not from disk.
10. Open the same workspace on a second client (the web PWA is fine) and confirm a rename appears there too.

- [ ] **Step 4: Check for the AWT trap**

Open a workspace with an editor tab and a terminal tab in the same group. Switch between them ten times, then check memory:

```bash
ps -o rss=,comm= -p $(pgrep -f 'dev.supermux.desktop.MainKt')
```

Expected: RSS settles rather than climbing on every switch. A steady climb means an inactive tab is keeping a KCEF or JediTerm instance alive — go back to Task 6 Step 1.

- [ ] **Step 5: Report to the user**

Send the screenshots from Step 3 and the memory reading from Step 4. Name anything on the checklist that did not pass. Do not describe a step as verified unless you watched it work.

---

## Self-review notes

**Spec coverage.** This plan implements spec §13.3 (the desktop client) and §13.6 (the workspace sidebar), plus the client half of §9.3 (the close confirmation) and §5.3 (rendering the tree).

**Not implemented here, by design:**
- §7.3 and §7.4 — a workspace-scoped terminal has no broker endpoint yet. `ViewHost` draws `WorkspaceTerminalPending` for it, and Phase 4 replaces that.
- §8.3 — the small-screen rule. A desktop window is always wide, so this client always writes the layout. The rule matters when the phone plans land.
- §11 — one `Viewing` frame per visible chat view. Two chats can now be on screen at once, so this **is** now reachable on desktop. It is deliberately deferred so Phase 3 stays about layout, but it must be the first task of whatever plan follows: today the client sends one `Viewing` for `ui.selectedId`, which after Task 4 is a workspace id, not a session id. **That is a real bug this plan introduces** — track it.
- Every other client.

**Type consistency check.** `WorkspaceDto`, `ViewDto`, `LayoutNodeDto` come from `dev.supermux.proto` (Phase 1b Task 4). `LayoutNode`, `normalizeLayout`, `toDto`, `toDomainOrNull` come from `dev.supermux.workspace` (Phase 2). `groupWorkspaces`, `workspaceActivity`, `isMultiAgent`, `chatSessionIds` are defined in Task 1 and used in Task 4. `LayoutHost(layout, onLayoutChange, modifier, titleFor, onCloseView, content)` has the same parameter list in Tasks 5 and 6.

⚠ **Known defect this plan introduces, to fix in the next desktop plan:** `ui.selectedId` becomes a workspace id in Task 4, but the `Viewing` frame still sends it as a session id. Push suppression for the open chat breaks until §11 is implemented.
