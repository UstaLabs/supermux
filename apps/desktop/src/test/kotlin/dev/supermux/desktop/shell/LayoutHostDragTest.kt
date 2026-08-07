package dev.supermux.desktop.shell

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
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
