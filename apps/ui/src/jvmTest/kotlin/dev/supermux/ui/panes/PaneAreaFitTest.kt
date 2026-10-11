package dev.supermux.ui.panes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntRect
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.normalizeLayout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole-area resize (window edge, sidebar) is drawn at once by [PaneAreaFit] and committed
 * to the tree once it settles. The commit used to never come: the area re-placed itself in a
 * loop (see [PaneAreaFit.tick]), and a seam dragged afterwards did not follow the pointer.
 */
@OptIn(ExperimentalTestApi::class)
class PaneAreaFitTest {
    @AfterTest
    fun clear() {
        testWindowScreenBounds = null
    }

    @Test
    fun anAreaResizeSettlesIntoTheTreeAndTheSeamStillDrags() = runComposeUiTest {
        var tree: LayoutNode by mutableStateOf(
            LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                LayoutNode.Group("g1", listOf("v1"), "v1"),
                LayoutNode.Group("g2", listOf("v2"), "v2"),
            )),
        )
        var areaW by mutableStateOf(800)
        testWindowScreenBounds = { IntRect(0, 0, 1000, 600) }
        setContent {
            val d = LocalDensity.current
            Box(Modifier.size(with(d) { areaW.toDp() }, with(d) { 600.toDp() })) {
                PaneHost(layout = tree, onEdit = { e -> tree = normalizeLayout(e(tree)) ?: tree }) {
                    Text("body-$it")
                }
            }
        }
        fun seamX() = onNodeWithTag("splitter-0").fetchSemanticsNode().boundsInRoot.center.x
        fun sizes() = (tree as LayoutNode.Split).sizes
        waitForIdle()
        assertEquals(400f, seamX(), 1f)

        // The sidebar grows by 200px: the window is unchanged, so the area's LEFT edge moved
        // and the seam keeps its place on screen (200px further left inside the area).
        areaW = 600
        waitForIdle()
        assertEquals(200f, seamX(), 1f)
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(200.0 / 600.0, sizes()[0], 1e-3, "the fit is written to the tree once it settles")

        onNodeWithTag("splitter-0").performTouchInput {
            down(center)
            moveBy(Offset(50f, 0f))
            moveBy(Offset(50f, 0f))
            up()
        }
        waitForIdle()
        assertTrue(sizes()[0] > 0.4, "the drag reached the tree, got ${sizes()}")
        assertEquals(sizes()[0].toFloat() * 600f, seamX(), 2f, "the seam is drawn where the tree says")
    }
}
