package dev.supermux.desktop.shell

import androidx.compose.material3.Text
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

@OptIn(ExperimentalTestApi::class)
class LayoutHostTest {

    @Test
    fun aSingleGroupRendersItsActiveViewOnly() = runComposeUiTest {
        setContent {
            LayoutHost(
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
            LayoutHost(
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
            LayoutHost(
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
            LayoutHost(
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
            LayoutHost(
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
            LayoutHost(
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
        // A close ENDS work (spec 9.3). LayoutHost must not silently drop the view
        // from the tree — the caller confirms, calls the broker, and the frame
        // comes back. Only report.
        var closed: String? = null
        setContent {
            LayoutHost(
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
            LayoutHost(layout = LayoutNode.Group("g1", emptyList(), null), onLayoutChange = {}) { Text("body") }
        }
        onNodeWithTag("layout-empty").assertIsDisplayed()
    }

    // ── The "+" belongs on the TAB STRIP, not the sidebar row ────────────────
    // Ahmet: "the plus button is on the wrong place ... it should be next to the
    // tabs, when i click on tab it should ask me (popover?) if i wanna start a
    // terminal or a display or a chat etc. and if i click it then it should start
    // it there as a new tab with a view".

    @Test
    fun theTabStripCarriesAnAddButton() = runComposeUiTest {
        setContent {
            LayoutHost(
                layout = LayoutNode.Group("g1", listOf("v1"), "v1"),
                onLayoutChange = {},
                onAddView = { _, _ -> },
            ) { Text("body") }
        }
        onNodeWithTag("tab-add-view").assertIsDisplayed()
    }

    @Test
    fun theAddButtonIsHiddenWhenTheCallerCannotCreateViews() = runComposeUiTest {
        setContent {
            LayoutHost(layout = LayoutNode.Group("g1", listOf("v1"), "v1"), onLayoutChange = {}) { Text("body") }
        }
        onNodeWithTag("tab-add-view").assertDoesNotExist()
    }

    @Test
    fun clickingAddOpensAPopoverOfViewKinds() = runComposeUiTest {
        setContent {
            LayoutHost(
                layout = LayoutNode.Group("g1", listOf("v1"), "v1"),
                onLayoutChange = {},
                onAddView = { _, _ -> },
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
        var got: Pair<String, NewViewKind>? = null
        setContent {
            LayoutHost(
                layout = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                    LayoutNode.Group("left", listOf("v1"), "v1"),
                    LayoutNode.Group("right", listOf("v2"), "v2"),
                )),
                onLayoutChange = {},
                onAddView = { g, k -> got = g to k },
            ) { Text("body") }
        }
        // Two strips, two "+" — the SECOND one must report the right-hand group.
        onAllNodesWithTag("tab-add-view")[1].performClick()
        onNodeWithTag("tab-add-view-terminal").performClick()
        assertEquals("right" to NewViewKind.TERMINAL, got)
    }
}
