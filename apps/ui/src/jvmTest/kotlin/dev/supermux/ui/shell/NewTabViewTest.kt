package dev.supermux.ui.shell

import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A New tab is a real view of kind "new" (#91, Ahmet 2026-11-08: "it is impossible to close a new tab", and it
 * could not be dragged): closing, dragging, splitting, reordering and resizing it are every view's paths. What is
 * its own: the split buttons move it to the workspace's edge, and a pick puts the chosen view in its place.
 */
class NewTabViewTest {
    private val one = LayoutNode.Group("g", listOf("a", "nt"), "nt")
    private val two = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(LayoutNode.Group("g1", listOf("a"), "a"), LayoutNode.Group("g2", listOf("b", "nt"), "nt")))

    @Test fun splitRightMovesTheNewTabIntoANewRightmostColumn() {
        val out = newTabToEdge(two, "nt", "row", "p") as LayoutNode.Split
        assertEquals("row", out.direction)
        assertEquals(listOf(LayoutNode.Group("g1", listOf("a"), "a"), LayoutNode.Group("g2", listOf("b"), "b"), LayoutNode.Group("p", listOf("nt"), "nt")), out.children)
    }

    @Test fun splitDownMovesTheNewTabIntoANewFullWidthRow() {
        val out = newTabToEdge(two, "nt", "column", "p") as LayoutNode.Split
        assertEquals("column", out.direction)
        assertEquals(listOf("a", "b"), (out.children[0] as LayoutNode.Split).children.map { (it as LayoutNode.Group).viewIds.single() })
        assertEquals(LayoutNode.Group("p", listOf("nt"), "nt"), out.children[1])
    }

    @Test fun movingItAgainChangesNothing() {
        val once = newTabToEdge(one, "nt", "row", "p")
        assertEquals(once, newTabToEdge(once, "nt", "row", "p"))
    }

    @Test fun aPickTakesTheNewTabsPlaceAndShows() {
        val broker = LayoutNode.Group("g", listOf("a", "nt", "b", "v"), "v")
        assertEquals(LayoutNode.Group("g", listOf("a", "v", "nt", "b"), "v"), pickedInPlace(broker, "nt", "v"))
    }

    @Test fun aPickAddedToAnotherPaneMovesToTheNewTabsPane() {
        val broker = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(LayoutNode.Group("g1", listOf("a", "v"), "v"), LayoutNode.Group("p", listOf("nt"), "nt")))
        val out = pickedInPlace(broker, "nt", "v") as LayoutNode.Split
        assertEquals(LayoutNode.Group("g1", listOf("a"), "a"), out.children[0])
        assertEquals(LayoutNode.Group("p", listOf("v", "nt"), "v"), out.children[1])
    }

    @Test fun closingANewTabAsksNothing() {
        assertFalse(closeNeedsConfirmation(NEW_TAB_KIND))
    }
}
