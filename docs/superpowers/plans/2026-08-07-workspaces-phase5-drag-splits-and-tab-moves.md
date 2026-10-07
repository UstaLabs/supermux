# Workspaces Phase 5 — Drag: Splits and Tab Moves Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the user a way to actually *create* splits and move views. Today the layout engine is complete and tested but unreachable — no gesture in the app calls `splitGroup`, no tab can be dragged, and the client never calls `POST /views/:id/move`.

**Architecture:** Two pure tree operations are added to the shared `LayoutTree` (reorder within a group, move between groups), joined to the two that already exist (`splitGroup`, `removeViewFromLayout`). The desktop then grows one drag interaction on the tab strip, with three drop targets: a position inside a strip, another group's strip, and a pane **edge** that splits. Cross-workspace moves reuse the existing broker route.

**Tech Stack:** Kotlin Multiplatform (`apps/shared`), Compose Desktop (`apps/desktop`), Compose UI tests under Xvfb.

**Spec:** `docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md` §5.3, §9.4, §13.3.

**Depends on:** Phases 1–4, all merged to `dev` at `81b56274`.

---

## The constraint that shapes every task

⚠ **Compose paints NOTHING above a `SwingPanel`.** JediTerm (terminal) and KCEF (editor) are heavyweight AWT children. A drag preview or a drop-zone highlight drawn over a pane containing one of them **will be invisible** — the digest is explicit: *"nothing Compose paints above them → the KCEF editor can't have overlays, swap the pane don't overlay."*

So the design is:

1. **While a drag is in progress, the dragged-over pane swaps its heavyweight content for a Compose drop-zone surface.** Do not try to overlay it. `LayoutHost` already composes only the active view; during a drag it composes the drop surface instead.
2. **Tab strips are ordinary Compose** — reorder and cross-strip drops need no swapping and should work normally.
3. The drag preview follows the pointer in the **tab strip layer**, never over a pane.

If you find yourself writing `Box(Modifier.matchParentSize())` over a view body, stop — that is the failure mode this section exists to prevent.

---

## Task 1: The two missing tree operations (shared)

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutTree.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutTreeTest.kt`

`splitGroup` and `removeViewFromLayout` already exist and are tested. Two are missing.

- [ ] **Step 1: Write the failing tests**

Append to `LayoutTreeTest.kt`, inside the class:

```kotlin
    // --- Phase 5: reorder within a group, move between groups ---

    @Test
    fun reorderWithinGroup_movesAViewToANewIndex() {
        val l = group("g1", listOf("a", "b", "c"), "a")
        assertEquals(
            LayoutNode.Group("g1", listOf("b", "a", "c"), "a"),
            reorderWithinGroup(l, "g1", "a", 1),
        )
    }

    @Test
    fun reorderWithinGroup_clampsAnOutOfRangeIndex() {
        val l = group("g1", listOf("a", "b"), "a")
        assertEquals(LayoutNode.Group("g1", listOf("b", "a"), "a"), reorderWithinGroup(l, "g1", "a", 99))
    }

    @Test
    fun reorderWithinGroup_keepsTheActiveViewActive() {
        val l = group("g1", listOf("a", "b", "c"), "c")
        assertEquals("c", (reorderWithinGroup(l, "g1", "a", 2) as LayoutNode.Group).activeViewId)
    }

    @Test
    fun reorderWithinGroup_isANoOpForAnUnknownGroup() {
        val l = group("g1", listOf("a", "b"), "a")
        assertEquals(l, reorderWithinGroup(l, "nope", "a", 1))
    }

    @Test
    fun moveViewToGroup_movesAcrossAndActivatesItThere() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
            group("g1", listOf("a", "b"), "a"),
            group("g2", listOf("c"), "c"),
        ))
        val out = moveViewToGroup(l, "a", "g2", index = 0)!!
        val split = out as LayoutNode.Split
        assertEquals(LayoutNode.Group("g1", listOf("b"), "b"), split.children[0])
        assertEquals(LayoutNode.Group("g2", listOf("a", "c"), "a"), split.children[1])
    }

    @Test
    fun moveViewToGroup_collapsesTheSplitWhenTheSourceGroupEmpties() {
        // Last view leaves g1 → g1 disappears → the split has one child → it collapses.
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
            group("g1", listOf("a"), "a"),
            group("g2", listOf("c"), "c"),
        ))
        assertEquals(
            LayoutNode.Group("g2", listOf("a", "c"), "a"),
            moveViewToGroup(l, "a", "g2", index = 0),
        )
    }

    @Test
    fun moveViewToGroup_isANoOpWhenTheTargetGroupIsUnknown() {
        val l = group("g1", listOf("a", "b"), "a")
        assertEquals(l, moveViewToGroup(l, "a", "nope", 0))
    }

    @Test
    fun moveViewToGroup_movingWithinTheSameGroupJustReorders() {
        val l = group("g1", listOf("a", "b", "c"), "a")
        assertEquals(
            LayoutNode.Group("g1", listOf("b", "a", "c"), "a"),
            moveViewToGroup(l, "a", "g1", index = 1),
        )
    }

    @Test
    fun everyPhase5OperationLeavesAValidTree() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
            group("g1", listOf("a", "b"), "a"),
            group("g2", listOf("c"), "c"),
        ))
        assertNull(validateLayout(reorderWithinGroup(l, "g1", "a", 1)))
        assertNull(validateLayout(moveViewToGroup(l, "a", "g2", 0)!!))
        assertNull(validateLayout(splitGroup(group("g1", listOf("a", "b"), "a"), "g1", "b", "column", "gNew")))
    }
```

- [ ] **Step 2: Run them and watch them fail**

```bash
cd apps && ./gradlew :shared:jvmTest --tests '*LayoutTreeTest*'
```

Expected: FAIL — `Unresolved reference: reorderWithinGroup`.

- [ ] **Step 3: Implement both**

Add to `LayoutTree.kt`, after `splitGroup`:

```kotlin
/**
 * Move [viewId] to [index] within its own group. Out-of-range indices clamp.
 * The active view is unchanged — reordering tabs must not switch which one you
 * are looking at.
 */
fun reorderWithinGroup(node: LayoutNode, groupId: String, viewId: String, index: Int): LayoutNode = when (node) {
    is LayoutNode.Group -> {
        if (node.id != groupId || viewId !in node.viewIds) node
        else {
            val rest = node.viewIds.filter { it != viewId }
            val at = index.coerceIn(0, rest.size)
            node.copy(viewIds = rest.subList(0, at) + viewId + rest.subList(at, rest.size))
        }
    }
    is LayoutNode.Split -> node.copy(children = node.children.map { reorderWithinGroup(it, groupId, viewId, index) })
}

/**
 * Move [viewId] out of wherever it is and into [toGroupId] at [index], and make
 * it active there — you dragged it, you want to see it.
 *
 * Emptying the source group collapses it, and a split left with one child
 * collapses too; that is [normalizeLayout]'s job and it runs here. Returns null
 * only if the whole tree emptied, which cannot happen while the moved view still
 * exists — but the signature stays nullable to match [removeViewFromLayout].
 */
fun moveViewToGroup(node: LayoutNode, viewId: String, toGroupId: String, index: Int): LayoutNode? {
    // Same-group move is a reorder; going through remove+add would briefly empty
    // a one-view group and collapse the split out from under the user.
    val owner = groupIdOf(node, viewId)
    if (owner == toGroupId) return reorderWithinGroup(node, toGroupId, viewId, index)
    if (!hasGroup(node, toGroupId)) return node

    val without = removeViewFromLayout(node, viewId) ?: return node
    if (!hasGroup(without, toGroupId)) return node
    return normalizeLayout(insertIntoGroup(without, toGroupId, viewId, index))
}

/** The id of the group holding [viewId], or null. */
fun groupIdOf(node: LayoutNode, viewId: String): String? = when (node) {
    is LayoutNode.Group -> node.id.takeIf { viewId in node.viewIds }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { groupIdOf(it, viewId) }
}

private fun hasGroup(node: LayoutNode, groupId: String): Boolean = when (node) {
    is LayoutNode.Group -> node.id == groupId
    is LayoutNode.Split -> node.children.any { hasGroup(it, groupId) }
}

private fun insertIntoGroup(node: LayoutNode, groupId: String, viewId: String, index: Int): LayoutNode = when (node) {
    is LayoutNode.Group -> {
        if (node.id != groupId) node
        else {
            val at = index.coerceIn(0, node.viewIds.size)
            LayoutNode.Group(
                id = node.id,
                viewIds = node.viewIds.subList(0, at) + viewId + node.viewIds.subList(at, node.viewIds.size),
                activeViewId = viewId,
            )
        }
    }
    is LayoutNode.Split -> node.copy(children = node.children.map { insertIntoGroup(it, groupId, viewId, index) })
}
```

- [ ] **Step 4: Green, then mirror to TypeScript**

```bash
cd apps && ./gradlew :shared:jvmTest --tests '*LayoutTreeTest*'
```

Then add the same two functions and the same cases to `src/core/workspace/layout-tree.ts` and `layout-tree.test.ts`. The two suites are a **parity pair** — the whole point of Phase 2 was that they cannot drift. `bun test src/core/workspace/layout-tree.test.ts` must pass with the same case names.

- [ ] **Step 5: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutTree.kt \
        apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutTreeTest.kt \
        src/core/workspace/layout-tree.ts src/core/workspace/layout-tree.test.ts
git commit -m "feat(shared): reorderWithinGroup and moveViewToGroup

The two tree operations the drag gestures need. Same-group moves reorder rather
than remove+add, which would briefly empty a one-view group and collapse the
split out from under the user. Mirrored into the TypeScript twin with the same
cases — the suites are a parity pair."
```

---

## Task 2: Drag a tab to reorder it within its strip

**Files:**
- Modify: `apps/desktop/.../shell/LayoutHost.kt`
- Test: `apps/desktop/src/test/kotlin/.../shell/LayoutHostDragTest.kt` (create)

- [ ] **Step 1: Read the existing drag conventions**

`SessionDragReorder.kt` in `apps/desktop/.../session/` already implements drag-to-reorder for the session list. **Read it first and reuse its approach** — the app should feel consistent, and it has already solved pointer-offset and drop-index maths for a list.

- [ ] **Step 2: Write the failing tests**

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutHostDragTest {

    @Test
    fun draggingATabToTheRightReordersIt() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "a")
        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _ -> }) { Text("body") }
        }
        onNodeWithTag("view-tab-a").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(120f, 0f)); up()
        }
        assertEquals(listOf("b", "a", "c"), (tree as LayoutNode.Group).viewIds)
    }

    @Test
    fun reorderingDoesNotChangeWhichTabIsActive() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "c")
        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _ -> }) { Text("body") }
        }
        onNodeWithTag("view-tab-a").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(120f, 0f)); up()
        }
        assertEquals("c", (tree as LayoutNode.Group).activeViewId)
    }

    @Test
    fun aClickWithoutMovementStillSelectsTheTab() = runComposeUiTest {
        // The drag gesture must not swallow the plain click that switches tabs.
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _ -> }) { Text("body") }
        }
        onNodeWithTag("view-tab-b").performClick()
        assertEquals("b", (tree as LayoutNode.Group).activeViewId)
    }
}
```

- [ ] **Step 3: Run them, watch them fail, then implement**

Add drag state to `ViewTabStrip`: track the dragged view id and the pointer x, compute a drop index from the tab widths (`onGloballyPositioned` per tab, as `SessionDragReorder` does), and on release call `onReorder(viewId, index)` which `GroupHost` turns into `reorderWithinGroup`.

⚠ **A plain click must still select the tab.** Use a drag threshold — below it, the gesture is a click. The third test exists for exactly this and it is the easiest thing to break.

- [ ] **Step 4: Green, then commit**

```bash
cd apps && xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*LayoutHostDragTest*'
git commit -m "feat(desktop): drag a tab to reorder it within its group"
```

---

## Task 3: Drag a tab onto another group's strip to move it

**Files:** same as Task 2.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun draggingATabOntoAnotherGroupsStripMovesIt() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
            LayoutNode.Group("g1", listOf("a", "b"), "a"),
            LayoutNode.Group("g2", listOf("c"), "c"),
        ))
        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _ -> }) { Text("body") }
        }
        // Drag tab "a" from the left strip onto the right strip.
        onNodeWithTag("view-tab-a").performTouchInput {
            down(center); moveTo(androidx.compose.ui.geometry.Offset(900f, 10f)); up()
        }
        val split = tree as LayoutNode.Split
        assertEquals(listOf("b"), (split.children[0] as LayoutNode.Group).viewIds)
        assertEquals(true, (split.children[1] as LayoutNode.Group).viewIds.contains("a"))
    }
```

- [ ] **Step 2: Implement**

Each strip registers its window-space bounds. On drag release, find the strip whose bounds contain the pointer; if it is not the origin strip, call `moveViewToGroup(tree, viewId, thatGroupId, index)`.

Use a shared drag state hoisted to `LayoutHost` so strips can see each other — a `remember`ed holder passed down, not a global.

- [ ] **Step 3: Green, then commit**

---

## Task 4: Drag a tab to a pane edge to create a split

**Files:** same, plus a new `DropZones.kt` if it keeps `LayoutHost` readable.

This is the task that makes splits reachable at all.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun droppingATabOnTheRightEdgeSplitsTheGroupIntoARow() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _ -> }) { Text("body") }
        }
        onNodeWithTag("view-tab-b").performTouchInput {
            down(center); moveTo(androidx.compose.ui.geometry.Offset(1180f, 400f)); up()
        }
        val split = tree as LayoutNode.Split
        assertEquals("row", split.direction)
        assertEquals(listOf("a"), (split.children[0] as LayoutNode.Group).viewIds)
        assertEquals(listOf("b"), (split.children[1] as LayoutNode.Group).viewIds)
    }

    @Test
    fun droppingOnTheBottomEdgeSplitsIntoAColumn() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _ -> }) { Text("body") }
        }
        onNodeWithTag("view-tab-b").performTouchInput {
            down(center); moveTo(androidx.compose.ui.geometry.Offset(600f, 780f)); up()
        }
        assertEquals("column", (tree as LayoutNode.Split).direction)
    }

    @Test
    fun aSingleViewGroupCannotBeSplitByItsOwnOnlyTab() = runComposeUiTest {
        // Splitting the only view would leave an empty group. splitGroup already
        // refuses; the UI must not pretend it worked.
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a"), "a")
        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _ -> }) { Text("body") }
        }
        onNodeWithTag("view-tab-a").performTouchInput {
            down(center); moveTo(androidx.compose.ui.geometry.Offset(1180f, 400f)); up()
        }
        assertEquals(LayoutNode.Group("g1", listOf("a"), "a"), tree)
    }

    @Test
    fun dropZonesAppearOnlyWhileDragging() = runComposeUiTest {
        setContent {
            LayoutHost(
                layout = LayoutNode.Group("g1", listOf("a", "b"), "a"),
                onLayoutChange = {}, onAddView = { _, _ -> },
            ) { Text("body") }
        }
        onNodeWithTag("drop-zone-right").assertDoesNotExist()
    }
```

- [ ] **Step 2: Implement — and read this before you draw anything**

⚠ **Do not overlay the drop zones on the view body.** Compose paints nothing above `SwingPanel`, so a highlight over a terminal or editor is invisible and the feature will look broken exactly where it matters most.

Instead: while a drag is active, `GroupHost` **replaces** its body content with a Compose drop-zone surface — four edge regions (`drop-zone-left/right/top/bottom`) plus a centre region meaning "move into this group". The heavyweight child is unmounted for the duration of the drag and remounted on release. That is the "swap the pane, don't overlay" rule the digest states.

Edge regions are the outer ~25% of each side; the centre is the rest. Left/right → `direction = "row"`, top/bottom → `"column"`; dropping on left/top puts the new group **first**.

On release over an edge, call `splitGroup(tree, groupId, viewId, direction, newGroupId = randomUUID())`.

- [ ] **Step 3: Green, then commit**

---

## Task 5: Persist and move across workspaces

**Files:** `apps/desktop/.../shell/AppShell.kt`, `apps/desktop/.../state/DesktopAppState.kt`

- [ ] **Step 1: Persist the new trees**

Every Task 2–4 gesture ends in `onLayoutChange`, which `AppShell` already debounces into `PATCH /workspaces/:id`. Confirm by reading it — **no new persistence code should be needed.** If you find yourself adding a second PATCH path, stop: the debounce exists and a second writer will fight it.

- [ ] **Step 2: Cross-workspace move**

Add `moveViewToWorkspace(viewId, toWorkspaceId)` to `DesktopAppState` calling `api.moveView(viewId, MoveViewBody(toWorkspaceId))` — the route exists and is tested; the client has simply never called it.

Wire it to a drop on a **workspace row in the sidebar**. Spec §9.4: the session's work directory does NOT change, and a chat view in a workspace with a different workdir is valid — the UI must not pretend otherwise.

- [ ] **Step 3: Test and commit**

---

## Task 6: Live verification — the one that counts

**Files:** none.

⚠ This phase is all gestures, and this project has already shipped a panel that passed 16 tests while being visibly wrong. **Tests are necessary here and nowhere near sufficient.**

- [ ] **Step 1: Run it against the real broker**

```bash
bun src/main.ts        # already running as mux.service
cd apps && ./gradlew :desktop:hotRun --auto   # with SM_WORKSPACES=1
```

- [ ] **Step 2: Walk this list, and screenshot each 📷**

1. 📷 drag a tab left/right within its strip — it reorders, the active tab does not change
2. a plain click still switches tabs (the most likely regression)
3. 📷 drag a tab onto another pane's strip — it moves, and the source group collapses if it emptied
4. 📷 drag a tab to the **right edge** — a vertical split appears, both panes render
5. 📷 drag to the **bottom edge** — a horizontal split
6. **drag a tab over a pane showing the EDITOR** — the drop zones must be **visible**. This is the KCEF case; if they are invisible, the swap in Task 4 is not working
7. same over a pane showing a **terminal** (JediTerm)
8. splits survive a client restart — they came back from the server
9. open the same workspace on a second client; a split made on one appears on the other
10. drag a tab onto a different workspace row in the sidebar — it moves; the session's workdir is unchanged

- [ ] **Step 3: Check memory after a drag storm**

```bash
ps -o rss=,comm= -p $(pgrep -f 'dev.supermux.desktop.MainKt')
```

Do twenty drags across panes with an editor and a terminal open. RSS must settle. A climb means the swap in Task 4 is remounting KCEF/JediTerm without disposing the old one.

- [ ] **Step 4: Report with the screenshots**

Name anything that did not pass. Do not describe a step as verified without having watched it.

---

## Gates

```bash
bun test                                   # 3129 pass / 2 pre-existing fail
cd apps && ./gradlew :shared:jvmTest       # 516
cd apps && xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test   # 1138
```

Counts go up, failures stay at the two pre-existing Windows-opencode tests.

⚠ `/tmp` fills and produces hundreds of false `EDQUOT` failures. On mass failures run `rm -rf /tmp/mux-test-state-*` and re-run before concluding anything.

---

## Out of scope

- Dragging a **view body** (only tabs drag)
- Dropping onto the **splitter** itself
- Keyboard-driven splitting
- Touch/phone gestures — desktop only
- Reordering **groups** within a split
