package dev.supermux.ui.panes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #151 (Ahmet): closing a tab that isn't the one on show flashed it for a split second. The press on its × also
 * selected the tab, so it showed until the close took it away. A click on × closes; it never selects.
 */
@OptIn(ExperimentalTestApi::class)
class TabCloseNoSelectTest {
    @Test fun clickingTheXOfATabNotOnShowClosesItWithoutShowingIt() = runComposeUiTest {
        var tree: LayoutNode by mutableStateOf(LayoutNode.Group("g", listOf("a", "b"), "a"))
        val closed = mutableListOf<String>()
        setContent {
            Box(Modifier.size(600.dp, 300.dp)) {
                PaneHost(layout = tree, onLayoutChange = { tree = it }, onCloseView = { closed += it }) { Text("body-$it") }
            }
        }
        waitForIdle()
        onNodeWithTag("tab-close-b", useUnmergedTree = true).performMouseInput { moveTo(center); click(center) }
        waitForIdle()
        assertEquals(listOf("b"), closed)
        assertEquals("a", (tree as LayoutNode.Group).activeViewId, "the × selected the tab it closes")
    }

    @Test fun clickingTheTabStillSelectsIt() = runComposeUiTest {
        var tree: LayoutNode by mutableStateOf(LayoutNode.Group("g", listOf("a", "b"), "a"))
        setContent {
            Box(Modifier.size(600.dp, 300.dp)) {
                PaneHost(layout = tree, onLayoutChange = { tree = it }) { Text("body-$it") }
            }
        }
        waitForIdle()
        onNodeWithTag("view-tab-b", useUnmergedTree = true).performMouseInput { click(centerLeft + androidx.compose.ui.geometry.Offset(20f, 0f)) }
        waitForIdle()
        assertEquals("b", (tree as LayoutNode.Group).activeViewId)
    }
}
