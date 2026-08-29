package dev.supermux.android.workspace

import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class ChatViewTarget(val workspaceId: String, val viewId: String)

/**
 * Wait for the broker [ViewAdded] that hosts [sessionId], then return that view.
 * Null if it never appears within [timeoutMs].
 */
suspend fun awaitChatViewForSession(
    workspaces: Flow<List<WorkspaceDto>>,
    sessionId: String,
    timeoutMs: Long = 5_000,
): ChatViewTarget? = withTimeoutOrNull(timeoutMs) {
    val list = workspaces.first { ws ->
        ws.any { w -> w.views.any { v -> v.chatSessionId() == sessionId } }
    }
    val w = list.first { w -> w.views.any { v -> v.chatSessionId() == sessionId } }
    val v: ViewDto = w.views.first { it.chatSessionId() == sessionId }
    ChatViewTarget(w.id, v.id)
}
