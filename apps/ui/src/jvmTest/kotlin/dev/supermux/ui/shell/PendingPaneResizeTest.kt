package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.panes.PaneHost
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.normalizeLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pane the split buttons opened resizes by its seam before anything is picked in it (Ahmet,
 * 2026-11-08: "that causes it not to be able to resize"). PaneHost is wired here as WorkspacePanes
 * wires it: the New tabs drawn in, every edit made on the real tree through [newTabEdit].
 */
@OptIn(ExperimentalTestApi::class)
class PendingPaneResizeTest {
    private val one = LayoutNode.Group("g", listOf("a"), "a")

    @Test
    fun aSplitRightPaneResizesByItsSeam() = runComposeUiTest {
        val real = mutableStateOf<LayoutNode>(one)
        val newTabs = NewTabsState().apply { splitWorkspace("row", one) }
        val nt = newTabs.slots.keys.single()
        setContent { Workspace(real, newTabs) }
        waitForIdle()
        val before = onNodeWithTag("view-tab-$nt").getUnclippedBoundsInRoot().left

        onNodeWithTag("splitter-0").performTouchInput {
            down(center)
            moveBy(Offset(-150f, 0f))
            up()
        }
        waitForIdle()

        val after = onNodeWithTag("view-tab-$nt").getUnclippedBoundsInRoot().left
        assertTrue(after < before - 100.dp, "the New tab's pane still starts at $after (it was at $before)")
        assertEquals(one, real.value, "the real tree has no split to resize")
    }

    @Test
    fun aSplitDownPaneResizesByItsSeam() = runComposeUiTest {
        val real = mutableStateOf<LayoutNode>(one)
        val newTabs = NewTabsState().apply { splitWorkspace("column", one) }
        val nt = newTabs.slots.keys.single()
        setContent { Workspace(real, newTabs) }
        waitForIdle()
        val before = onNodeWithTag("view-tab-$nt").getUnclippedBoundsInRoot().top

        onNodeWithTag("splitter-0").performTouchInput {
            down(center)
            moveBy(Offset(0f, -150f))
            up()
        }
        waitForIdle()

        val after = onNodeWithTag("view-tab-$nt").getUnclippedBoundsInRoot().top
        assertTrue(after < before - 100.dp, "the New tab's pane still starts at $after (it was at $before)")
        assertEquals(one, real.value, "the real tree has no split to resize")
    }

    @Composable
    private fun Workspace(real: MutableState<LayoutNode>, newTabs: NewTabsState) {
        Box(Modifier.size(800.dp, 600.dp)) {
            PaneHost(
                layout = withNewTabs(real.value, newTabs.slots.toMap()),
                onEdit = { edit ->
                    newTabEdit(real.value, newTabs.slots.toMap(), edit)?.let { routed ->
                        for ((id, slot) in routed.slots) if (newTabs.slots[id] != slot) newTabs.slots[id] = slot
                        real.value = normalizeLayout(routed.onReal(real.value)) ?: real.value
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { Text("body-$it") }
        }
    }
}
