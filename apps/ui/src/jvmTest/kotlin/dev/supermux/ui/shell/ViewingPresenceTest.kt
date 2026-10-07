package dev.supermux.ui.shell

import dev.supermux.proto.ViewDto
import dev.supermux.workspace.LayoutNode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which chats are on screen for the Compact tab model / the wide tree (cluster G8 shared it). */
class ViewingPresenceTest {

    private fun chatView(id: String, sessionId: String, workspaceId: String = "w1") = ViewDto(
        id = id,
        workspaceId = workspaceId,
        kind = "chat",
        state = buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) },
    )

    private fun termView(id: String, workspaceId: String = "w1") = ViewDto(
        id = id,
        workspaceId = workspaceId,
        kind = "terminal",
    )

    private fun splitTwoChats(): LayoutNode = LayoutNode.Split(
        direction = "row",
        sizes = listOf(0.5, 0.5),
        children = listOf(
            LayoutNode.Group("g1", listOf("v-chat-a", "v-term"), "v-chat-a"),
            LayoutNode.Group("g2", listOf("v-chat-b"), "v-chat-b"),
        ),
    )

    private fun viewsTwoChats() = listOf(
        chatView("v-chat-a", "s1"),
        termView("v-term"),
        chatView("v-chat-b", "s2"),
    )

    @Test
    fun phoneVisibleIdIsOnlyTheActiveTabChat() {
        val ids = visibleWorkspaceChatIdsAt(
            compact = true,
            layout = splitTwoChats(),
            views = viewsTwoChats(),
            activeViewId = "v-chat-b",
        )
        assertEquals(listOf("s2"), ids)
    }

    @Test
    fun phoneActiveTerminalYieldsNoChatIds() {
        val ids = visibleWorkspaceChatIdsAt(
            compact = true,
            layout = splitTwoChats(),
            views = viewsTwoChats(),
            activeViewId = "v-term",
        )
        assertEquals(emptyList(), ids)
    }

    @Test
    fun tabletVisibleIdsAreActiveTabsOnlyAndExcludeBackgroundChat() {
        val layout = LayoutNode.Split(
            direction = "row",
            sizes = listOf(0.5, 0.5),
            children = listOf(
                LayoutNode.Group("g1", listOf("v-chat-a", "v-chat-bg"), "v-chat-a"),
                LayoutNode.Group("g2", listOf("v-chat-b"), "v-chat-b"),
            ),
        )
        val views = listOf(
            chatView("v-chat-a", "s1"),
            chatView("v-chat-bg", "s-bg"),
            termView("v-term"),
            chatView("v-chat-b", "s2"),
        )
        val ids = visibleWorkspaceChatIdsAt(
            compact = false,
            layout = layout,
            views = views,
            activeViewId = "v-chat-a",
        )
        assertEquals(listOf("s1", "s2"), ids)
    }
}
