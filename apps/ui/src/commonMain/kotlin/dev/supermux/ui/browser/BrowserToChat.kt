package dev.supermux.ui.browser

import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId

/**
 * Where a Browser tab sends a picture of its page (#122): the workspace's chat, which comes forward with the image
 * attached in its composer, unsent, so the user writes about it before sending. False when there is no chat to take it.
 */
fun interface BrowserToChat {
    fun sendImage(png: ByteArray, name: String): Boolean
}

/** The workspace's. Null where a Browser tab can't send pictures to a chat: then it offers no screenshot at all. */
val LocalBrowserToChat = staticCompositionLocalOf<BrowserToChat?> { null }

/** The chat a screenshot goes to: the selected session's when it is in this workspace, else the workspace's own, else its first chat. */
fun screenshotChat(views: Collection<ViewDto>, selectedSessionId: String?, primarySessionId: String?): ViewDto? {
    val chats = views.filter { it.kind == "chat" && it.chatSessionId() != null }
    return chats.firstOrNull { it.chatSessionId() == selectedSessionId }
        ?: chats.firstOrNull { it.chatSessionId() == primarySessionId }
        ?: chats.firstOrNull()
}
