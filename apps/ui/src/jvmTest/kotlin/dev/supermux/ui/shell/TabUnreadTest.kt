package dev.supermux.ui.shell

import dev.supermux.proto.ViewDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabUnreadTest {
    private fun chat(sid: String) = ViewDto(
        id = "v-$sid", workspaceId = "w", kind = "chat",
        state = JsonObject(mapOf("sessionId" to JsonPrimitive(sid))),
    )

    @Test
    fun aBackgroundChatTabWithNewsWearsTheDot() {
        assertTrue(chat("s2").tabUnread(selected = false, unreadSessions = setOf("s2")))
    }

    @Test
    fun theTabOnShowIsBeingReadSoItNever() {
        assertFalse(chat("s2").tabUnread(selected = true, unreadSessions = setOf("s2")))
    }

    @Test
    fun readChatsAndNonChatTabsDoNot() {
        assertFalse(chat("s1").tabUnread(selected = false, unreadSessions = setOf("s2")))
        assertFalse(ViewDto(id = "t", workspaceId = "w", kind = "terminal").tabUnread(false, setOf("s2")))
        assertFalse(null.tabUnread(false, setOf("s2")))
    }
}
