package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.panes.PaneHost
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Two [PaneHost]s over the same workspace tree, partitioned by [WindowHostRegistry].
 * No real OS [androidx.compose.ui.window.Window] — this is the layout claim, not chrome.
 */
@OptIn(ExperimentalTestApi::class)
class WindowHostPaneTest {
    private val bounds = WindowBounds(0f, 0f, 800f, 600f)

    private fun tree(): LayoutNode = LayoutNode.Split(
        "row",
        listOf(0.5, 0.5),
        listOf(
            LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
            LayoutNode.Group("g2", listOf("v3"), "v3"),
        ),
    )

    @Test
    fun claimedGroupLeavesMainAndShowsOnExtra() = runComposeUiTest {
        val registry = WindowHostRegistry()
        registry.setWorkspaceOnMain("ws")
        val t = tree()
        val extra = registry.tryClaim("ws", setOf("v1", "v2"), bounds, "extra-1", t)
        assertNotNull(extra)
        val mainLayout = registry.layoutFor(registry.main(), t)
        val extraLayout = registry.layoutFor(extra, t)
        assertNotNull(mainLayout)
        assertNotNull(extraLayout)

        setContent {
            Column {
                PaneHost(
                    layout = mainLayout,
                    onLayoutChange = {},
                    modifier = Modifier.size(400.dp).testTag("window-host-main"),
                    emptyGroupSlot = { WorkspaceEmptyHint() },
                ) { id -> Text("body-$id", modifier = Modifier.testTag("body-$id")) }
                PaneHost(
                    layout = extraLayout,
                    onLayoutChange = {},
                    modifier = Modifier.size(400.dp).testTag("window-host-extra"),
                    emptyGroupSlot = { WorkspaceEmptyHint() },
                ) { id -> Text("body-$id", modifier = Modifier.testTag("body-$id")) }
            }
        }

        onNodeWithTag("view-tab-v3").assertIsDisplayed()
        onNodeWithTag("body-v3").assertIsDisplayed()
        onNodeWithTag("view-tab-v1").assertIsDisplayed()
        onNodeWithTag("body-v1").assertIsDisplayed()
        onNodeWithTag("view-tab-v2").assertIsDisplayed()
        onNodeWithTag("body-v2").assertDoesNotExist()
        onNodeWithTag("layout-empty").assertDoesNotExist()
    }

    @Test
    fun canvasClaimShowsEmptyHintOnMainAsDropSurface() = runComposeUiTest {
        val registry = WindowHostRegistry()
        registry.setWorkspaceOnMain("ws")
        val t = tree()
        assertNotNull(registry.tryClaimCanvas("ws", bounds, "canvas"))
        val mainLayout = registry.layoutFor(registry.main(), t) ?: emptyHostLayout(t)
        setContent {
            PaneHost(
                layout = mainLayout,
                onLayoutChange = {},
                modifier = Modifier.size(400.dp).testTag("window-host-main"),
                emptyGroupSlot = { WorkspaceEmptyHint() },
            ) { id -> Text("body-$id", modifier = Modifier.testTag("body-$id")) }
        }
        onNodeWithTag("layout-empty").assertIsDisplayed()
        onNodeWithTag("body-v1").assertDoesNotExist()
    }
}
