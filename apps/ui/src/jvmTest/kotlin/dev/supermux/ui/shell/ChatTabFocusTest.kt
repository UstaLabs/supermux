package dev.supermux.ui.shell

import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sidebar's chat row → that chat's tab to the front (Ahmet, 2026-09-21). */
class ChatTabFocusTest {
    private val split = LayoutNode.Split(
        direction = "row",
        children = listOf(
            LayoutNode.Group("left", listOf("chat1", "term"), "term"),
            LayoutNode.Group("right", listOf("chat2", "editor"), "editor"),
        ),
        sizes = listOf(0.5, 0.5),
    )

    private fun active(layout: LayoutNode, group: String) =
        ((layout as LayoutNode.Split).children.first { (it as LayoutNode.Group).id == group } as LayoutNode.Group)
            .activeViewId

    @Test
    fun theChatBecomesTheFrontTabOfItsOwnGroupOnly() {
        val next = frontTab(split, "chat2")
        assertEquals("chat2", active(next, "right"))
        assertEquals("term", active(next, "left"))
    }

    @Test
    fun aViewInNoGroupLeavesTheLayoutAlone() {
        assertEquals(split, frontTab(split, "nope"))
    }

    @Test
    fun aChatRowClickSelectsTheSessionAndQueuesTheFocusUntilConsumed() {
        val ui = ShellUiState()
        ui.focusChatTab("s2")
        assertEquals("s2", ui.selectedId)
        assertEquals("s2", ui.chatTabFocus)
        ui.consumeChatTabFocus()
        assertNull(ui.chatTabFocus)
        // Clicking the chat that is already selected still asks again.
        ui.focusChatTab("s2")
        assertEquals("s2", ui.chatTabFocus)
    }
}
