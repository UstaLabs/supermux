package dev.supermux.ui.panes

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Rect
import dev.supermux.ui.shell.TabLongPressMenu
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PaneHostDragTest {

    @Test
    fun draggingATabToTheRightReordersIt() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "a")
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-a").mouseDragBy(Offset(120f, 0f))
        assertEquals(listOf("b", "a", "c"), (tree as LayoutNode.Group).viewIds)
    }

    @Test
    fun reorderingDoesNotChangeWhichTabIsActive() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "c")
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-a").mouseDragBy(Offset(120f, 0f))
        assertEquals("c", (tree as LayoutNode.Group).activeViewId)
    }

    @Test
    fun aClickWithoutMovementStillSelectsTheTab() = runComposeUiTest {
        // The drag gesture must not swallow the plain click that switches tabs.
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-b").performClick()
        assertEquals("b", (tree as LayoutNode.Group).activeViewId)
    }

    @Test
    fun draggingATabOntoAnotherGroupsStripMovesIt() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
            LayoutNode.Group("g1", listOf("a", "b"), "a"),
            LayoutNode.Group("g2", listOf("c"), "c"),
        ))
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        // Drag tab "a" from the left strip onto the right strip.
        onNodeWithTag("view-tab-a").mouseDragTo(Offset(900f, 10f))
        val split = tree as LayoutNode.Split
        assertEquals(listOf("b"), (split.children[0] as LayoutNode.Group).viewIds)
        assertEquals(true, (split.children[1] as LayoutNode.Group).viewIds.contains("a"))
    }

    @Test
    fun droppingATabOnTheRightEdgeSplitsTheGroupIntoARow() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-b").mouseDragTo(nearRightEdge("view-tab-b"))
        val split = tree as LayoutNode.Split
        assertEquals("row", split.direction)
        assertEquals(listOf("a"), (split.children[0] as LayoutNode.Group).viewIds)
        assertEquals(listOf("b"), (split.children[1] as LayoutNode.Group).viewIds)
    }

    @Test
    fun droppingOnTheBottomEdgeSplitsIntoAColumn() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-b").mouseDragTo(Offset(600f, 780f))
        assertEquals("column", (tree as LayoutNode.Split).direction)
    }

    @Test
    fun aSingleViewGroupCannotBeSplitByItsOwnOnlyTab() = runComposeUiTest {
        // Splitting the only view would leave an empty group. splitGroup already
        // refuses; the UI must not pretend it worked.
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a"), "a")
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-a").mouseDragTo(nearRightEdge("view-tab-a"))
        assertEquals(LayoutNode.Group("g1", listOf("a"), "a"), tree)
    }

    @Test
    fun dropZonesAppearOnlyWhileDragging() = runComposeUiTest {
        setContent {
            PaneHost(
                layout = LayoutNode.Group("g1", listOf("a", "b"), "a"),
                onLayoutChange = {}, addSlot = {},
            ) { Text("body") }
        }
        onNodeWithTag("drop-zone-right").assertDoesNotExist()
    }

    // ── Regression: a shared PaneDragController must not carry stale bounds ────────
    //
    // AppShell hoists ONE PaneDragController for the app's lifetime and hands it to
    // whichever workspace is on screen. Nothing ever unregistered a group's
    // bounds, so after visiting a second workspace the maps still held panes and
    // strips from the first. resolveDrop hit-tests every registered rect, a stale
    // one occupies the same screen area as the live one and can win, and the
    // resolved group id then does not exist in the current tree — so
    // reorderWithinGroup / moveViewToGroup / splitGroup all no-op, next == tree,
    // and onLayoutChange is never called.
    //
    // Symptom, in the user's words: "if i am dragging from the tab title and
    // leave it nothing happens."
    //
    // Every other test in this file builds a FRESH state and mounts ONE
    // workspace, which is why they all passed while the app was broken.

    @Test
    fun aDragStillWorksAfterSwitchingWorkspacesWithASharedDragState() = runComposeUiTest {
        val shared = PaneDragController()
        var workspace by mutableStateOf("a")
        var treeB: LayoutNode = LayoutNode.Group("gb", listOf("x", "y", "z"), "x")

        setContent {
            if (workspace == "a") {
                PaneHost(
                    layout = LayoutNode.Group("ga", listOf("p", "q"), "p"),
                    onLayoutChange = {},
                    dragState = shared,
                    addSlot = {},
                ) { Text("body-a") }
            } else {
                PaneHost(
                    layout = treeB,
                    onLayoutChange = { treeB = it },
                    dragState = shared,
                    addSlot = {},
                ) { Text("body-b") }
            }
        }

        waitForIdle()
        workspace = "b"
        waitForIdle()

        // An ordinary same-strip reorder inside the workspace now on screen.
        onNodeWithTag("view-tab-x").performMouseInput {
            moveTo(Offset(10f, 12f))
            press()
            moveBy(Offset(140f, 0f))
            release()
        }

        assertEquals(listOf("y", "x", "z"), (treeB as LayoutNode.Group).viewIds)
    }

    // ── Touch: hold to drag, swipe to scroll ───────────────────────────────────────
    //
    // A finger that moves at once is scrolling the strip, not dragging a tab. Before,
    // every swipe that began on a tab dragged it, so an overflowing strip could not be
    // scrolled by touch at all.

    @Test
    fun aQuickTouchSwipeOnATabNeitherReordersNorSelectsIt() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "b")
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-a").performTouchInput { down(center); moveBy(Offset(120f, 0f)); up() }
        assertEquals(LayoutNode.Group("g1", listOf("a", "b", "c"), "b"), tree)
    }

    @Test
    fun aTouchTapStillSelectsTheTab() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-b").performTouchInput { down(center); up() }
        assertEquals("b", (tree as LayoutNode.Group).activeViewId)
    }

    @Test
    fun holdingATabThenDraggingItByTouchReordersIt() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "a")
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        val tab = onNodeWithTag("view-tab-a")
        tab.performTouchInput { down(center) }
        mainClock.advanceTimeBy(1_000)
        tab.performTouchInput { moveBy(Offset(60f, 0f)); moveBy(Offset(60f, 0f)); up() }
        assertEquals(listOf("b", "a", "c"), (tree as LayoutNode.Group).viewIds)
    }

    @Test
    fun aHeldTabThatMovesIsDraggedAndNeverOpensItsMenu() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "a")
        setContent { MenuStrip(tree) { tree = it } }
        val tab = onNodeWithTag("view-tab-a")
        tab.performTouchInput { down(center) }
        mainClock.advanceTimeBy(1_000)
        tab.performTouchInput { moveBy(Offset(60f, 0f)); moveBy(Offset(60f, 0f)); up() }
        waitForIdle()
        onNodeWithTag("tab-menu-new-window-a").assertDoesNotExist()
        assertEquals(listOf("b", "a", "c"), (tree as LayoutNode.Group).viewIds)
    }

    @Test
    fun aHeldTabThatLiftsInPlaceOpensItsMenu() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "a")
        setContent { MenuStrip(tree) { tree = it } }
        val tab = onNodeWithTag("view-tab-a")
        tab.performTouchInput { down(center) }
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("tab-menu-new-window-a").assertDoesNotExist()
        tab.performTouchInput { up() }
        waitForIdle()
        onNodeWithTag("tab-menu-new-window-a").assertIsDisplayed()
        assertEquals(listOf("a", "b", "c"), (tree as LayoutNode.Group).viewIds)
    }

    /** A strip whose tabs wear the workspace's long-press menu, as WorkspacePanes draws them. */
    @Composable
    private fun MenuStrip(tree: LayoutNode, onChange: (LayoutNode) -> Unit) {
        PaneHost(
            layout = tree,
            onLayoutChange = onChange,
            addSlot = {},
            tabSlot = { id, state ->
                TabLongPressMenu(
                    enabled = true,
                    itemId = id,
                    onMoveToNewWindow = {},
                    bulkEntries = { emptyList() },
                    openOnRelease = true,
                ) { DefaultTabChip(id, id, state, FontFamily.Monospace, onClose = {}) }
            },
        ) { Text("body") }
    }

    // ── A drop off every target is a miss ────────────────────────────────────────
    //
    // The pane fallback in resolveDrop used to accept ANY distance, so the origin pane
    // always matched, onDragEndMiss never ran, and desktop's drag-off-the-window
    // tear-out could not happen.

    @Test
    fun aDropFarOutsideEveryPaneIsAMissAndLeavesTheTreeAlone() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        var missed: String? = null
        setContent {
            PaneHost(
                layout = tree,
                onLayoutChange = { tree = it },
                onDragEndMiss = { missed = it },
                addSlot = {},
            ) { Text("body") }
        }
        onNodeWithTag("view-tab-b").mouseDragTo(Offset(600f, 5_000f))
        assertEquals("b", missed)
        assertEquals(LayoutNode.Group("g1", listOf("a", "b"), "a"), tree)
    }

    @Test
    fun aDropJustPastThePaneEdgeStillSplits() {
        val d = draggingFromStrip(pointer = Offset(200f, 640f)) // 40 px below the pane
        val target = d.finish()
        assertTrue(target is PaneDropTarget.Split && target.direction == "column", "got $target")
    }

    @Test
    fun aDropFarBelowThePaneIsAMiss() {
        val d = draggingFromStrip(pointer = Offset(200f, 900f))
        assertNull(d.finish())
    }

    // ── Strip auto-scroll ─────────────────────────────────────────────────────────

    @Test
    fun aDragHeldNearAStripEndScrollsItThatWay() {
        val strip = Rect(0f, 0f, 400f, 32f)
        assertTrue(draggingFromStrip(Offset(395f, 16f)).stripAutoScrollStep(strip, 40f, 12f) > 0f)
        assertTrue(draggingFromStrip(Offset(5f, 16f)).stripAutoScrollStep(strip, 40f, 12f) < 0f)
        assertEquals(0f, draggingFromStrip(Offset(200f, 16f)).stripAutoScrollStep(strip, 40f, 12f))
        // Down in the pane body is not "near the strip's end".
        assertEquals(0f, draggingFromStrip(Offset(395f, 300f)).stripAutoScrollStep(strip, 40f, 12f))
    }

    @Test
    fun aStripScrolledUnderAStillPointerMovesTheReorderSlot() {
        val d = draggingFromStrip(Offset(60f, 16f))
        d.originStripScrolled(200f)
        assertEquals(PaneDropTarget.Reorder("g", "a", 2), d.finish())
    }

    /** A controller mid-drag of tab "a" (100 px slots, a b c d) in group "g", pointer at [pointer]. */
    private fun draggingFromStrip(pointer: Offset): PaneDragController {
        val d = PaneDragController()
        d.registerStrip("g", Rect(0f, 0f, 400f, 32f))
        d.registerPane("g", Rect(0f, 32f, 400f, 600f))
        listOf("a", "b", "c", "d").forEachIndexed { i, id ->
            d.registerTab("g", id, Rect(i * 100f, 0f, i * 100f + 100f, 32f))
        }
        val start = Offset(50f, 16f)
        d.begin("a", "g", start, listOf("a", "b", "c", "d"), "a", Rect(0f, 0f, 100f, 32f))
        d.updatePointer(pointer, start, PANE_DRAG_THRESHOLD_PX)
        return d
    }
}

/**
 * A point (local to [tag]'s node) just inside the window's right edge, half-way down — the right
 * drop zone of a pane filling the window. A fixed x would depend on the test window's width, and
 * a release far past it is now a miss rather than an edge drop.
 */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.nearRightEdge(tag: String): Offset {
    val root = onRoot().fetchSemanticsNode().boundsInRoot
    val node = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    return Offset(root.right - 10f - node.left, root.height / 2f - node.top)
}

/** A mouse press on this node's centre, a drag by [by], and a release. */
@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.mouseDragBy(by: Offset) =
    performMouseInput { moveTo(center); press(); moveBy(by); release() }

/** A mouse press on this node's centre, a drag to [to] (node-local), and a release. */
@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.mouseDragTo(to: Offset) =
    performMouseInput { moveTo(center); press(); moveTo(to); release() }
