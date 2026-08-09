package dev.supermux.desktop.shell

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.supermux.ui.panes.PaneHost
import dev.supermux.ui.panes.PaneStripChrome
import dev.supermux.ui.panes.PaneTabStrip
import dev.supermux.ui.panes.TabSlotState

@OptIn(ExperimentalTestApi::class)
class PaneHostTest {

    @Test
    fun aSingleGroupRendersItsActiveViewOnly() = runComposeUiTest {
        setContent {
            PaneHost(
                layout = LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
                onLayoutChange = {},
            ) { viewId -> Text("body-$viewId") }
        }
        onNodeWithText("body-v1").assertIsDisplayed()
        onNodeWithText("body-v2").assertDoesNotExist()
    }

    @Test
    fun aGroupRendersOneTabPerView() = runComposeUiTest {
        setContent {
            PaneHost(
                layout = LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
                titleFor = { "tab-$it" },
                onLayoutChange = {},
            ) { Text("body") }
        }
        onNodeWithText("tab-v1").assertIsDisplayed()
        onNodeWithText("tab-v2").assertIsDisplayed()
    }

    @Test
    fun clickingATabReportsTheNewActiveViewThroughOnLayoutChange() = runComposeUiTest {
        var next: LayoutNode? = null
        setContent {
            PaneHost(
                layout = LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
                titleFor = { "tab-$it" },
                onLayoutChange = { next = it },
            ) { Text("body") }
        }
        onNodeWithText("tab-v2").performClick()
        assertEquals(LayoutNode.Group("g1", listOf("v1", "v2"), "v2"), next)
    }

    @Test
    fun aRowSplitRendersBothChildren() = runComposeUiTest {
        setContent {
            PaneHost(
                layout = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                    LayoutNode.Group("g1", listOf("v1"), "v1"),
                    LayoutNode.Group("g2", listOf("v2"), "v2"),
                )),
                onLayoutChange = {},
            ) { viewId -> Text("body-$viewId") }
        }
        onNodeWithText("body-v1").assertIsDisplayed()
        onNodeWithText("body-v2").assertIsDisplayed()
    }

    @Test
    fun aNestedSplitRendersEveryLeaf() = runComposeUiTest {
        setContent {
            PaneHost(
                layout = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                    LayoutNode.Group("g1", listOf("v1"), "v1"),
                    LayoutNode.Split("column", listOf(0.5, 0.5), listOf(
                        LayoutNode.Group("g2", listOf("v2"), "v2"),
                        LayoutNode.Group("g3", listOf("v3"), "v3"),
                    )),
                )),
                onLayoutChange = {},
            ) { viewId -> Text("body-$viewId") }
        }
        onNodeWithText("body-v1").assertIsDisplayed()
        onNodeWithText("body-v2").assertIsDisplayed()
        onNodeWithText("body-v3").assertIsDisplayed()
    }

    @Test
    fun aSplitterExistsBetweenEveryPairOfChildren() = runComposeUiTest {
        setContent {
            PaneHost(
                layout = LayoutNode.Split("row", listOf(0.33, 0.33, 0.34), listOf(
                    LayoutNode.Group("g1", listOf("v1"), "v1"),
                    LayoutNode.Group("g2", listOf("v2"), "v2"),
                    LayoutNode.Group("g3", listOf("v3"), "v3"),
                )),
                onLayoutChange = {},
            ) { Text("body") }
        }
        onNodeWithTag("splitter-0").assertIsDisplayed()
        onNodeWithTag("splitter-1").assertIsDisplayed()
        onNodeWithTag("splitter-2").assertDoesNotExist()
    }

    @Test
    fun closingATabReportsTheViewIdRatherThanEditingTheTree() = runComposeUiTest {
        // A close ENDS work (spec 9.3). PaneHost must not silently drop the view
        // from the tree — the caller confirms, calls the broker, and the frame
        // comes back. Only report.
        var closed: String? = null
        setContent {
            PaneHost(
                layout = LayoutNode.Group("g1", listOf("v1", "v2"), "v1"),
                titleFor = { "tab-$it" },
                onLayoutChange = {},
                onCloseView = { closed = it },
            ) { Text("body") }
        }
        onNodeWithTag("tab-close-v1").performClick()
        assertEquals("v1", closed)
    }

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
    fun theAddButtonIsHiddenWhenTheCallerCannotCreateViews() = runComposeUiTest {
        setContent {
            PaneHost(layout = LayoutNode.Group("g1", listOf("v1"), "v1"), onLayoutChange = {}) { Text("body") }
        }
        onNodeWithTag("tab-add-view").assertDoesNotExist()
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
        onNodeWithTag("tab-add-view-display").assertIsDisplayed()
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

    @Test
    fun tabSlot_drawsTheCallerSuppliedChip() = runComposeUiTest {
        setContent {
            PaneTabStrip(
                groupId = "g1",
                viewIds = listOf("v1", "v2"),
                activeViewId = "v1",
                titleFor = { it },
                onSelect = {},
                dragState = null,
                onDrop = {},
                chrome = PaneStripChrome.None,
                tabSlot = { itemId, state ->
                    Text("slot-$itemId-${state.selected}", modifier = Modifier.testTag("custom-tab-$itemId"))
                },
            )
        }
        // dragState = null takes the plain Modifier.clickable fallback (see PaneTabStrip),
        // which merges descendant semantics for accessibility — so the slot's own testTag and
        // text are only visible in the unmerged tree, not the strip's merged view-tab-v1 node.
        onNodeWithTag("custom-tab-v1", useUnmergedTree = true).assertExists()
        onNodeWithText("slot-v1-true", useUnmergedTree = true).assertExists()
        // The inactive tab exercises the false branch of TabSlotState.selected.
        onNodeWithText("slot-v2-false", useUnmergedTree = true).assertExists()
        // The layer itself must draw no chip: the close affordance is the slot's business now.
        // This fails loudly if a built-in chip is ever reintroduced to the strip.
        onNodeWithTag("tab-close-v1", useUnmergedTree = true).assertDoesNotExist()
    }
}
