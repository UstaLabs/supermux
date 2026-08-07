package dev.supermux.desktop.shell

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.addViewToGroup
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.removeViewFromLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Invariants of [LayoutHost]'s editing model: an edit names WHAT to change — a
 * group id, a split path — and is applied to the tree as it is now.
 *
 * Honesty about what these are: they are guards, not a reproduction. They were
 * written for a theory that turned out to be wrong. Ahmet reported that resizing
 * revived a closed view and dropped a new one, and the first explanation was
 * that [ResizableSplitN] holds its drag handler in `pointerInput(totalPx, index)`
 * and so kept a stale `onSizesChange`. A probe disproved that: recompose with a
 * new callback, drag, and the handler receives the NEW one. These tests passed
 * before the change as well as after, which is exactly why they are not
 * presented as regression coverage.
 *
 * The real cause was in the sync layer and is covered by [LayoutRebaseTest],
 * which fails without its fix. What is kept here is still worth keeping: it pins
 * down that a resize only changes sizes, that a tab click only changes which tab
 * is active, and that neither carries a snapshot of the group's contents along
 * with it. That property is what makes an edit safe to replay onto a broker
 * frame, and nothing else asserts it.
 */
@OptIn(ExperimentalTestApi::class)
class LayoutHostStaleEditTest {

    private fun twoPanes(): LayoutNode = LayoutNode.Split(
        "row", listOf(0.5, 0.5),
        listOf(
            LayoutNode.Group("left", listOf("a"), "a"),
            LayoutNode.Group("right", listOf("b", "c"), "b"),
        ),
    )

    @Test
    fun resizingAfterAViewClosedDoesNotBringItBack() = runComposeUiTest {
        var tree: LayoutNode by mutableStateOf(twoPanes())

        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }) { Text("body-$it") }
        }
        waitForIdle()
        // Build the splitter's drag handler against the two-pane tree.
        onNodeWithTag("splitter-0").assertExists()

        // Close the left pane's only view. The group empties, so normalizeLayout
        // collapses the split to the surviving group — exactly what Ahmet saw.
        tree = removeViewFromLayout(tree, "a")!!
        waitForIdle()
        assertTrue(tree is LayoutNode.Group, "the split must collapse when a pane loses its last view")

        // With no split left there is no splitter to drag, which is the point: the
        // old handler must not be reachable, and nothing may restore "a".
        onNodeWithTag("splitter-0").assertDoesNotExist()
        assertFalse("a" in collectViewIds(tree), "a closed view must not come back")
    }

    @Test
    fun resizingAfterAViewWasAddedDoesNotDropIt() = runComposeUiTest {
        var tree: LayoutNode by mutableStateOf(twoPanes())

        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }) { Text("body-$it") }
        }
        waitForIdle()
        onNodeWithTag("splitter-0").assertExists()

        // A new terminal opens in the right pane, without changing the pane count.
        tree = addViewToGroup(tree, "right", "d")
        waitForIdle()

        // Now resize, using that same handler.
        onNodeWithTag("splitter-0").performTouchInput { swipeRight() }
        waitForIdle()

        assertTrue("d" in collectViewIds(tree), "a view created before the resize must survive it")
        val right = (tree as LayoutNode.Split).children[1] as LayoutNode.Group
        assertEquals(listOf("b", "c", "d"), right.viewIds)
        assertEquals("d", right.activeViewId, "the new view stays active across a resize")
    }

    @Test
    fun resizingStillActuallyResizes() = runComposeUiTest {
        // The guard above must not have cost us the feature.
        var tree: LayoutNode by mutableStateOf(twoPanes())

        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }) { Text("body-$it") }
        }
        waitForIdle()

        onNodeWithTag("splitter-0").performTouchInput { swipeRight() }
        waitForIdle()

        val sizes = (tree as LayoutNode.Split).sizes
        assertTrue(sizes[0] > 0.5, "dragging the splitter right must grow the left pane, got $sizes")
        assertEquals(1.0, sizes.sum(), 1e-6)
    }

    @Test
    fun aTabClickUsesTheLiveTreeNotACapturedGroup() = runComposeUiTest {
        // The same hazard on the tab strip: select by group id, so a click that
        // arrives after the tree moved on cannot restore the group's old contents.
        var tree: LayoutNode by mutableStateOf(twoPanes())

        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }) { Text("body-$it") }
        }
        waitForIdle()

        tree = addViewToGroup(tree, "right", "d")
        waitForIdle()

        onNodeWithTag("view-tab-c").performTouchInput { down(center); up() }
        waitForIdle()

        val right = (tree as LayoutNode.Split).children[1] as LayoutNode.Group
        assertEquals("c", right.activeViewId)
        assertEquals(listOf("b", "c", "d"), right.viewIds, "selecting a tab must not rebuild the group")
    }
}
