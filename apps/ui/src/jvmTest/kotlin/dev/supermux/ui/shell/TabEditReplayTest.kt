package dev.supermux.ui.shell

import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.addViewToGroup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #151, found in Ahmet's hot-run trace: a tab opened at once leaves a layout edit ("put it in this pane") that is
 * replayed onto every broker frame until the layout is saved. Closed meanwhile, the tab came back as a "view" tab:
 * the replay re-added a view the broker had removed. An open's placement now places only a tab still being opened
 * or one the broker still has, and a close is an edit of its own.
 */
class TabEditReplayTest {
    private val withTab = LayoutNode.Group("g", listOf("a", "x"), "x")
    private val brokerRemovedIt = LayoutNode.Group("g", listOf("a"), "a")
    private val place: (LayoutNode, String) -> LayoutNode = { t, id -> addViewToGroup(t, "g", id) }

    @Test fun aTabStillBeingOpenedIsPlaced() {
        assertEquals(withTab, placeWhileOpening(brokerRemovedIt, "x", opening = true, place))
    }

    @Test fun aTabTheBrokerHasIsPlaced() {
        assertEquals(withTab, placeWhileOpening(withTab, "x", opening = false, place))
    }

    @Test fun aTabTheBrokerRemovedIsNotPutBack() {
        assertEquals(brokerRemovedIt, placeWhileOpening(brokerRemovedIt, "x", opening = false, place))
    }

    @Test fun aCloseTakesTheTabOutOfAnyFrame() {
        assertEquals(brokerRemovedIt, withoutTab(withTab, "x"))
        assertEquals(brokerRemovedIt, withoutTab(brokerRemovedIt, "x"))
    }

    @Test fun closingTheLastTabLeavesAnEmptyPane() {
        assertEquals(emptyList(), (withoutTab(LayoutNode.Group("g", listOf("x"), "x"), "x") as LayoutNode.Group).viewIds)
    }

    // A tab removed elsewhere: the broker's view_removed lands a frame before its layout. That frame's layout still
    // lists the tab, but its view is gone, so the panes do not draw it.
    @Test fun aTabWhoseViewIsGoneIsNotDrawn() {
        assertEquals(brokerRemovedIt, withoutUnknownTabs(withTab, known = setOf("a")))
    }

    @Test fun everyKnownTabIsDrawn() {
        assertEquals(withTab, withoutUnknownTabs(withTab, known = setOf("a", "x")))
    }

    @Test fun beforeAnyViewIsKnownTheLayoutIsDrawnAsItIs() {
        assertEquals(withTab, withoutUnknownTabs(withTab, known = emptySet()))
    }
}
