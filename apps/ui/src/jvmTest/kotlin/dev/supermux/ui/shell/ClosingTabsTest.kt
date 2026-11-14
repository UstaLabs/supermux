package dev.supermux.ui.shell

import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #151: a closed tab leaves the screen at once. Until the broker's frame drops it, the panes draw the layout without
 * the tabs being closed; a failed close takes the tab off that list and it comes back.
 */
class ClosingTabsTest {
    private val two = LayoutNode.Split(
        "row", listOf(0.5, 0.5),
        listOf(LayoutNode.Group("g1", listOf("a", "b"), "b"), LayoutNode.Group("g2", listOf("c"), "c")),
    )

    @Test fun aClosingTabIsNotDrawn() {
        assertEquals(
            LayoutNode.Split("row", listOf(0.5, 0.5), listOf(LayoutNode.Group("g1", listOf("a"), "a"), LayoutNode.Group("g2", listOf("c"), "c"))),
            withoutClosing(two, setOf("b")),
        )
    }

    @Test fun closingAPanesLastTabTakesThePane() {
        assertEquals(LayoutNode.Group("g1", listOf("a", "b"), "b"), withoutClosing(two, setOf("c")))
    }

    @Test fun closingEveryTabLeavesAnEmptyPane() {
        val out = withoutClosing(LayoutNode.Group("g", listOf("a"), "a"), setOf("a")) as LayoutNode.Group
        assertEquals(emptyList(), out.viewIds)
    }

    @Test fun nothingClosingIsTheSameTree() {
        assertEquals(two, withoutClosing(two, emptySet()))
    }
}
