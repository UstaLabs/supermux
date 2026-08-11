// The pane layer's own tests live in :ui. These are the DESKTOP WIRING tests: they assert that the
// workspace's slot implementations — WorkspaceAddButton, WorkspaceEmptyHint — are plugged into
// PaneHost correctly, which is a fact about this app, not about the layer.
package dev.supermux.desktop.shell

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
                addSlot = { WorkspaceAddButton { _, _ -> } },
            ) { Text("body") }
        }
        onNodeWithTag("tab-add-view").assertIsDisplayed()
    }
    @Test
    fun clickingAddOpensAPopoverOfViewKinds() = runComposeUiTest {
        setContent {
            PaneHost(
                layout = LayoutNode.Group("g1", listOf("v1"), "v1"),
                onLayoutChange = {},
                addSlot = { WorkspaceAddButton { _, _ -> } },
            ) { Text("body") }
        }
        onNodeWithTag("tab-add-view").performClick()
        onNodeWithTag("tab-add-view-chat").assertIsDisplayed()
        onNodeWithTag("tab-add-view-terminal").assertIsDisplayed()
        onNodeWithTag("tab-add-view-editor").assertIsDisplayed()
        // Diff is an `editor` view in diff mode, so its tag is its own — not the wire.
        onNodeWithTag("tab-add-view-diff").assertIsDisplayed()
        onNodeWithTag("tab-add-view-display").assertIsDisplayed()
    }
    // Ahmet: "add the diff as in the + section in workspace panes, it will by
    // default open on a split".
    @Test
    fun pickingDiffAsksForASplitRatherThanATab() = runComposeUiTest {
        var got: Triple<String, NewViewKind, NewViewPlacement>? = null
        setContent {
            PaneHost(
                layout = LayoutNode.Group("only", listOf("v1"), "v1"),
                onLayoutChange = {},
                addSlot = { g -> WorkspaceAddButton { k, p -> got = Triple(g, k, p) } },
            ) { Text("body") }
        }
        onNodeWithTag("tab-add-view").performClick()
        onNodeWithTag("tab-add-view-diff").performClick()
        assertEquals(Triple("only", NewViewKind.DIFF, NewViewPlacement.SPLIT_RIGHT), got)
    }
    @Test
    fun pickingAKindReportsItWithTheGroupItWasClickedIn() = runComposeUiTest {
        var got: Triple<String, NewViewKind, NewViewPlacement>? = null
        setContent {
            PaneHost(
                layout = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                    LayoutNode.Group("left", listOf("v1"), "v1"),
                    LayoutNode.Group("right", listOf("v2"), "v2"),
                )),
                onLayoutChange = {},
                addSlot = { g -> WorkspaceAddButton { k, p -> got = Triple(g, k, p) } },
            ) { Text("body") }
        }
        // Two strips, two "+" — the SECOND one must report the right-hand group.
        // One step only: pick kind → always HERE (no placement submenu).
        onAllNodesWithTag("tab-add-view")[1].performClick()
        onNodeWithTag("tab-add-view-terminal").performClick()
        assertEquals(Triple("right", NewViewKind.TERMINAL, NewViewPlacement.HERE), got)
    }
    @Test
    fun pickingAKindAlwaysLandsInThisPane() = runComposeUiTest {
        var got: Triple<String, NewViewKind, NewViewPlacement>? = null
        setContent {
            PaneHost(
                layout = LayoutNode.Group("only", listOf("v1"), "v1"),
                onLayoutChange = {},
                addSlot = { g -> WorkspaceAddButton { k, p -> got = Triple(g, k, p) } },
            ) { Text("body") }
        }
        onNodeWithTag("tab-add-view").performClick()
        onNodeWithTag("tab-add-view-terminal").performClick()
        // No second step; placement submenu is gone.
        onNodeWithTag("tab-add-place-here").assertDoesNotExist()
        onNodeWithTag("tab-add-place-split_right").assertDoesNotExist()
        assertEquals(Triple("only", NewViewKind.TERMINAL, NewViewPlacement.HERE), got)
    }
}
