// The pane layer's own tests live in :ui. These are the DESKTOP WIRING tests: they assert that the
// workspace's slot implementations — WorkspaceAddButton, WorkspaceEmptyHint — are plugged into
// PaneHost correctly, which is a fact about this app, not about the layer.
package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.supermux.ui.panes.PaneHost
import dev.supermux.ui.panes.PaneStripChrome

@OptIn(ExperimentalTestApi::class)
class WorkspacePaneWiringTest {
    @Test
    fun anEmptyGroupRendersThePlaceholderRatherThanCrashing() = runComposeUiTest {
        setContent {
            PaneHost(
                layout = LayoutNode.Group("g1", emptyList(), null),
                onLayoutChange = {},
                emptyGroupSlot = { WorkspaceEmptyHint() },
            ) { Text("body") }
        }
        onNodeWithTag("layout-empty").assertIsDisplayed()
        // The box is the layer's; the wording is the caller's. Assert both, or a deleted
        // emptyGroupSlot at the AppShell call site would leave the suite green.
        onNodeWithText("This workspace has no open views").assertIsDisplayed()
    }
    // ── The "+" belongs on the TAB STRIP, not the sidebar row ────────────────
    // Ahmet: "the plus button is on the wrong place ... it should be next to the
    // tabs, when i click on tab it should ask me (popover?) if i wanna start a
    // terminal or a display or a chat etc. and if i click it then it should start
    // it there as a new tab with a view".
    @Test
    fun theTabStripCarriesAnAddButton() = runComposeUiTest {
        setContent {
            PaneHost(
                layout = LayoutNode.Group("g1", listOf("v1"), "v1"),
                onLayoutChange = {},
                addSlot = { WorkspaceAddButton { } },
            ) { Text("body") }
        }
        onNodeWithTag("tab-add-view").assertIsDisplayed()
    }
    // Ahmet (2026-11-07): "instead of [a drop-down], we should have an empty tab that has the options
    // of what to show." The "+" reports its pane; WorkspacePanes opens a New tab there.
    @Test
    fun clickingAddReportsThePaneItWasClickedIn() = runComposeUiTest {
        var got: String? = null
        setContent {
            PaneHost(
                layout = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                    LayoutNode.Group("left", listOf("v1"), "v1"),
                    LayoutNode.Group("right", listOf("v2"), "v2"),
                )),
                onLayoutChange = {},
                addSlot = { g -> WorkspaceAddButton { got = g } },
            ) { Text("body") }
        }
        onAllNodesWithTag("tab-add-view")[1].performClick()
        assertEquals("right", got)
        onNodeWithTag("tab-add-view-menu").assertDoesNotExist()
    }

    @Test
    fun theSplitButtonsSayWhichWay() = runComposeUiTest {
        val got = mutableListOf<String>()
        setContent { WorkspaceSplitButtons { got += it } }
        onNodeWithTag("tab-split-right").performClick()
        onNodeWithTag("tab-split-down").performClick()
        assertEquals(listOf("row", "column"), got)
    }
}
