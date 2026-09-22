package dev.supermux.state

import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun workspaceChatView(id: String, sessionId: String, workspaceId: String = "w") = ViewDto(
    id = id,
    workspaceId = workspaceId,
    kind = "chat",
    state = buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) },
)

private fun workspaceDto(
    id: String = "w1",
    name: String = id,
    views: List<ViewDto> = emptyList(),
) = WorkspaceDto(id = id, name = name, status = "active", workdir = "/w", views = views)

class AwaitChatViewTest {

    @Test
    fun viewArrivesLateThenActivates() = runTest {
        val flow = MutableStateFlow(listOf(workspaceDto(id = "w1", views = emptyList())))
        var activated: ChatViewTarget? = null
        val job = launch {
            activated = awaitChatViewForSession(flow, "s-new", timeoutMs = 5_000)
        }
        delay(80)
        flow.value = listOf(
            workspaceDto(
                id = "w1",
                views = listOf(workspaceChatView("v-new", "s-new", "w1")),
            ),
        )
        job.join()
        assertEquals(ChatViewTarget("w1", "v-new"), activated)
    }

    @Test
    fun neverArrivesReturnsNull() = runTest {
        val flow = MutableStateFlow(listOf(workspaceDto(id = "w1", views = emptyList())))
        val result = awaitChatViewForSession(flow, "s-missing", timeoutMs = 50)
        assertNull(result)
    }
}
