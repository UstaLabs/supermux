package dev.supermux.android.workspace

import dev.supermux.android.session.workspaceChatView
import dev.supermux.android.session.workspaceDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivateChatViewTest {

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
