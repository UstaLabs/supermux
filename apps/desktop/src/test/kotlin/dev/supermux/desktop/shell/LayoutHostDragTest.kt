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
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _, _ -> }) { Text("body") }
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
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _, _ -> }) { Text("body") }
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
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _, _ -> }) { Text("body") }
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
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _, _ -> }) { Text("body") }
        }
        // Drag tab "a" from the left strip onto the right strip.
        onNodeWithTag("view-tab-a").performTouchInput {
            down(center); moveTo(androidx.compose.ui.geometry.Offset(900f, 10f)); up()
        }
        val split = tree as LayoutNode.Split
        assertEquals(listOf("b"), (split.children[0] as LayoutNode.Group).viewIds)
        assertEquals(true, (split.children[1] as LayoutNode.Group).viewIds.contains("a"))
    }

    @Test
    fun droppingATabOnTheRightEdgeSplitsTheGroupIntoARow() = runComposeUiTest {
        var tree: LayoutNode = LayoutNode.Group("g1", listOf("a", "b"), "a")
        setContent {
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _, _ -> }) { Text("body") }
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
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _, _ -> }) { Text("body") }
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
            LayoutHost(layout = tree, onLayoutChange = { tree = it }, onAddView = { _, _, _ -> }) { Text("body") }
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
                onLayoutChange = {}, onAddView = { _, _, _ -> },
            ) { Text("body") }
        }
        onNodeWithTag("drop-zone-right").assertDoesNotExist()
    }
}
