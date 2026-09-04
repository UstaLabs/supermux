package dev.supermux.android.push

import dev.supermux.proto.ViewDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Push-tap resolution + the notification-cancel set (Android-only, moved from host/). */
class PushTapTest {

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
    fun pushTapResolvesWorkspaceAndActiveView() {
        val ws = WorkspaceDto(
            id = "w1",
            name = "one",
            workdir = "/",
            views = viewsTwoChats(),
        )
        val tap = resolvePushTap("s2", listOf(ws))
        assertEquals("w1", tap.workspaceId)
        assertEquals("v-chat-b", tap.activeViewId)
        assertEquals("s2", tap.sessionId)
        assertEquals(false, tap.sessionOnly)
    }

    @Test
    fun pushTapFallsBackToSessionOnlyWhenNoWorkspace() {
        val tap = resolvePushTap("s-orphan", emptyList())
        assertNull(tap.workspaceId)
        assertNull(tap.activeViewId)
        assertEquals("s-orphan", tap.sessionId)
        assertEquals(true, tap.sessionOnly)
    }

    @Test
    fun notificationCancelSetDedupsVisiblePlusSelected() {
        assertEquals(listOf("s1", "s2"), notificationCancelSessionIds(listOf("s1", "s2"), "s1"))
        assertEquals(listOf("s1", "s2"), notificationCancelSessionIds(listOf("s1"), "s2"))
        assertEquals(listOf("s1"), notificationCancelSessionIds(emptyList(), "s1"))
        assertEquals(emptyList(), notificationCancelSessionIds(emptyList(), null))
    }

    @Test
    fun pushTapHandleSkipsWhenAlreadyConsumed() {
        assertEquals(
            PushTapHandle.Skip,
            pushTapHandleDecision("s1", handledSessionId = "s1", workspacesReady = true),
        )
        assertEquals(
            PushTapHandle.Skip,
            pushTapHandleDecision(null, handledSessionId = null, workspacesReady = true),
        )
        assertEquals(
            PushTapHandle.Skip,
            pushTapHandleDecision("", handledSessionId = null, workspacesReady = true),
        )
    }

    @Test
    fun pushTapHandleRetriesWhenWorkspacesEmptyThenConsumes() {
        assertEquals(
            PushTapHandle.ApplyRetry,
            pushTapHandleDecision("s1", handledSessionId = null, workspacesReady = false),
        )
        assertEquals(
            PushTapHandle.ApplyConsume,
            pushTapHandleDecision("s1", handledSessionId = null, workspacesReady = true),
        )
    }

    @Test
    fun secondTapOnSameSessionAfterLeavingAppliesAgain() {
        assertEquals(
            PushTapHandle.Skip,
            pushTapHandleDecision("s1", handledSessionId = "s1", workspacesReady = true),
        )
        assertEquals(
            PushTapHandle.ApplyConsume,
            pushTapHandleDecision("s1", handledSessionId = null, workspacesReady = true),
        )
    }
}
