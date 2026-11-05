package dev.supermux.ui.panes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pane layer's half of live tear-off: when a drag is handed to the host, and what it hands over. */
@OptIn(ExperimentalTestApi::class)
class PaneHostTearOffTest {

    private class FakeTearOff(var takeOnStart: Boolean = false, var takeOutside: Boolean = true) : TabTearOff {
        var registered: TabDropEndpoint? = null
        val starts = mutableListOf<TabHandoff>()
        val outsides = mutableListOf<TabHandoff>()

        override fun register(endpoint: TabDropEndpoint) {
            registered = endpoint
        }

        override fun unregister(endpoint: TabDropEndpoint) {
            if (registered === endpoint) registered = null
        }

        override fun takeOverOnStart(handoff: TabHandoff): Boolean = takeOnStart.also { starts += handoff }

        override fun takeOverOutside(handoff: TabHandoff): Boolean = takeOutside.also { outsides += handoff }
    }

    private val abc = LayoutNode.Group("g1", listOf("a", "b", "c"), "a")

    @Test
    fun `a mouse drag past the window's edge is handed to the host, which then owns it`() = runComposeUiTest {
        var tree: LayoutNode = abc
        var missed: String? = null
        val host = FakeTearOff()
        val drag = PaneDragController()
        setContent {
            PaneHost(
                layout = tree,
                onLayoutChange = { tree = it },
                onDragEndMiss = { missed = it },
                dragState = drag,
                tearOff = host,
                addSlot = {},
            ) { Text("body") }
        }
        onNodeWithTag("view-tab-b").mouseDragTo(Offset(600f, 5_000f))

        val h = host.outsides.single()
        assertEquals("b", h.viewId)
        assertEquals("g1", h.groupId)
        assertEquals(1, h.index)
        assertEquals("a", h.activeViewId)
        assertTrue(h.tabSize.width > 0f && h.groupSize.height > h.tabSize.height)
        // Handed over: nothing dropped here, nothing missed, no ghost left behind.
        assertEquals(abc, tree)
        assertNull(missed)
        assertNull(drag.draggingViewId)
        onNodeWithTag("tab-drag-ghost").assertDoesNotExist()
    }

    @Test
    fun `just past the window's edge the drag stays here, and its release is that edge's drop`() = runComposeUiTest {
        var tree: LayoutNode = abc
        var missed: String? = null
        val host = FakeTearOff()
        setContent {
            PaneHost(
                layout = tree,
                onLayoutChange = { tree = it },
                onDragEndMiss = { missed = it },
                tearOff = host,
                addSlot = {},
            ) { Text("body") }
        }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val tab = onNodeWithTag("view-tab-b").fetchSemanticsNode().boundsInRoot
        // 4 px under the window: inside the tear-off margin, so no window opens — and an
        // overshoot that small still means the pane's bottom edge.
        onNodeWithTag("view-tab-b").mouseDragTo(Offset(root.width / 2f - tab.left, root.bottom + 4f - tab.top))

        assertTrue(host.outsides.isEmpty())
        val split = tree as LayoutNode.Split
        assertEquals("column", split.direction)
        assertEquals(listOf("b"), (split.children[1] as LayoutNode.Group).viewIds)
        assertNull(missed)
    }

    @Test
    fun `the host can take a drag the moment it starts`() = runComposeUiTest {
        var tree: LayoutNode = abc
        val host = FakeTearOff(takeOnStart = true)
        val drag = PaneDragController()
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, dragState = drag, tearOff = host, addSlot = {}) {
                Text("body")
            }
        }
        onNodeWithTag("view-tab-a").mouseDragBy(Offset(150f, 0f))

        assertEquals("a", host.starts.single().viewId)
        assertTrue(host.outsides.isEmpty())
        assertEquals(abc, tree)
        assertNull(drag.draggingViewId)
    }

    @Test
    fun `a window's only tab pulled down out of its strip is handed to the host`() = runComposeUiTest {
        val host = FakeTearOff()
        setContent {
            PaneHost(layout = LayoutNode.Group("g1", listOf("a"), "a"), tearOff = host, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-a").mouseDragBy(Offset(0f, 200f))
        assertEquals("a", host.outsides.single().viewId)
    }

    @Test
    fun `with other tabs in the window, a tab pulled down stays here for the drop zones`() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        val host = FakeTearOff()
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, tearOff = host, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-b").mouseDragBy(Offset(0f, 200f))
        assertTrue(host.outsides.isEmpty())
        assertTrue(tree is LayoutNode.Split)
    }

    @Test
    fun `a window's only tab pulled sideways out of its strip stays here`() = runComposeUiTest {
        val host = FakeTearOff()
        setContent {
            // A sidebar's worth of room to the strip's left.
            Row {
                Spacer(Modifier.width(200.dp))
                PaneHost(layout = LayoutNode.Group("g1", listOf("a"), "a"), tearOff = host, addSlot = {}) {
                    Text("body")
                }
            }
        }
        onNodeWithTag("view-tab-a").mouseDragBy(Offset(-150f, 0f))
        assertTrue(host.outsides.isEmpty())
    }

    @Test
    fun `the layer registers itself with its host as a place a tab can land`() = runComposeUiTest {
        val host = FakeTearOff()
        setContent {
            PaneHost(layout = abc, tearOff = host, addSlot = {}) { Text("body") }
        }
        waitForIdle()
        assertNotNull(host.registered)
    }

    @Test
    fun `a secondary-button drag neither moves nor selects a tab`() = runComposeUiTest {
        var tree: LayoutNode = abc
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, addSlot = {}) { Text("body") }
        }
        onNodeWithTag("view-tab-c").performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            moveBy(Offset(-150f, 0f))
            release(MouseButton.Secondary)
        }
        assertEquals(abc, tree)
    }

    @Test
    fun `Esc mid-drag ends it, and the release neither drops nor selects`() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b", "c"), "b")
        val drag = PaneDragController()
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, dragState = drag, addSlot = {}) { Text("body") }
        }
        val tab = onNodeWithTag("view-tab-a")
        tab.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(60f, 0f))
        }
        assertTrue(drag.isDragging)
        assertTrue(drag.abort())
        tab.performMouseInput {
            moveBy(Offset(60f, 0f))
            release()
        }
        assertEquals(LayoutNode.Group("g1", listOf("a", "b", "c"), "b"), tree)
        assertNull(drag.draggingViewId)
    }

    @Test
    fun `a tab dragged in from another window is previewed like one of this window's own`() = runComposeUiTest {
        var tree: LayoutNode = abc
        val drag = PaneDragController()
        setContent {
            PaneHost(layout = tree, onLayoutChange = { tree = it }, dragState = drag, addSlot = {}) { Text("body") }
        }
        waitForIdle()
        val b = onNodeWithTag("view-tab-b").fetchSemanticsNode().boundsInRoot
        // Over the strip between a and b.
        val at = Offset(b.left + 1f, b.center.y)
        drag.beginIncoming("z", at, "z", Offset(10f, 10f), Size(80f, 28f))
        waitForIdle()
        onNodeWithTag("tab-insert-caret").assertIsDisplayed()
        onNodeWithTag("tab-drag-ghost").assertIsDisplayed()

        val target = drag.finish()
        assertEquals(PaneDropTarget.MoveToGroup("z", "g1", 1), target)
    }
}

/** A mouse press on this node's centre, a drag by [by], and a release. */
@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.mouseDragBy(by: Offset) =
    performMouseInput { moveTo(center); press(); moveBy(by); release() }

/** A mouse press on this node's centre, a drag to [to] (node-local), and a release. */
@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.mouseDragTo(to: Offset) =
    performMouseInput { moveTo(center); press(); moveTo(to); release() }
