package dev.supermux.ui.browser

import dev.supermux.proto.ViewDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #122: a Browser tab's screenshot goes to a chat of its workspace — the one on show, else the workspace's own. */
class ScreenshotTargetTest {
    private fun chat(id: String, session: String) =
        ViewDto(id = id, workspaceId = "w1", kind = "chat", state = JsonObject(mapOf("sessionId" to JsonPrimitive(session))))
    private val browser = ViewDto(id = "b1", workspaceId = "w1", kind = "browser", state = JsonObject(emptyMap()))
    private val views = listOf(chat("c1", "s1"), browser, chat("c2", "s2"))

    @Test fun theSelectedSessionsChatWhenItIsInThisWorkspace() {
        assertEquals("c2", screenshotChat(views, selectedSessionId = "s2", primarySessionId = "s1")?.id)
    }

    @Test fun elseTheWorkspacesOwnSession() {
        assertEquals("c1", screenshotChat(views, selectedSessionId = "elsewhere", primarySessionId = "s1")?.id)
    }

    @Test fun elseTheFirstChat() {
        assertEquals("c1", screenshotChat(views, selectedSessionId = null, primarySessionId = null)?.id)
    }

    @Test fun aWorkspaceWithNoChatHasNowhereToSendIt() {
        assertNull(screenshotChat(listOf(browser), selectedSessionId = "s1", primarySessionId = "s1"))
    }
}
