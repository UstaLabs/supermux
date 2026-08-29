package dev.supermux.android.host

import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.LayoutNode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun viewingFramesForZeroIdsSendsNotVisible() {
        assertEquals(
            listOf(ClientFrame.Viewing(null, false)),
            viewingFramesFor(emptyList()),
        )
    }

    @Test
    fun viewingFramesForOneIdIsBareForm() {
        assertEquals(
            listOf(ClientFrame.Viewing("s1", true, null)),
            viewingFramesFor(listOf("s1")),
        )
    }

    @Test
    fun viewingFramesForNIdsIsOneFrameWithWholeSet() {
        assertEquals(
            listOf(ClientFrame.Viewing("s1", true, listOf("s1", "s2"))),
            viewingFramesFor(listOf("s1", "s2")),
        )
    }

    @Test
    fun snapshotPredicateRequiresHomeNoOverlayForegroundAndResolvedWorkspace() {
        val ok = ViewingSurface(
            homeRoute = true,
            overlayOpen = false,
            workspaceResolved = true,
            appForeground = true,
        )
        assertEquals(true, viewingSurfaceVisible(ok))
        assertEquals(false, viewingSurfaceVisible(ok.copy(homeRoute = false)))
        assertEquals(false, viewingSurfaceVisible(ok.copy(overlayOpen = true)))
        assertEquals(false, viewingSurfaceVisible(ok.copy(workspaceResolved = false)))
        assertEquals(false, viewingSurfaceVisible(ok.copy(appForeground = false)))
    }

    @Test
    fun visibleIdsEmptyWhenSnapshotWorkspaceDoesNotMatchSelection() {
        val snap = WorkspaceViewingSnapshot(
            workspaceId = "w-old",
            visibleChatSessionIds = listOf("s1", "s2"),
            appForeground = true,
        )
        assertEquals(
            emptyList(),
            visibleWorkspaceChatIds(
                surfaceVisible = true,
                selectedWorkspaceId = "w-new",
                snapshot = snap,
            ),
        )
    }

    @Test
    fun phoneVisibleIdIsOnlyTheActiveTabChat() {
        val ids = visibleChatIdsForAndroid(
            tablet = false,
            layout = splitTwoChats(),
            views = viewsTwoChats(),
            activeViewId = "v-chat-b",
        )
        assertEquals(listOf("s2"), ids)
    }

    @Test
    fun phoneActiveTerminalYieldsNoChatIds() {
        val ids = visibleChatIdsForAndroid(
            tablet = false,
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
        val ids = visibleChatIdsForAndroid(
            tablet = true,
            layout = layout,
            views = views,
            activeViewId = "v-chat-a",
        )
        assertEquals(listOf("s1", "s2"), ids)
    }

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

    @Test
    fun previousHostClearUsesPreviousSnapshotFirstIdOnWorkspaceSwitch() {
        val hostA = WorkspaceViewingSnapshot("w-a", listOf("s-a"), appForeground = true)
        val hostB = WorkspaceViewingSnapshot("w-b", listOf("s-b"), appForeground = true)
        assertEquals("s-a", previousHostClearSessionId(hostA, hostB))
        assertNull(previousHostClearSessionId(hostA, hostA.copy(visibleChatSessionIds = listOf("s-a2"))))
        assertNull(previousHostClearSessionId(null, hostB))
        assertNull(previousHostClearSessionId(hostA, null))
    }

    @Test
    fun framesForSnapshotUsesBareFormAndListWhenEmpty() {
        assertEquals(
            listOf(ClientFrame.Viewing(null, false)),
            framesForSnapshot(null),
        )
        assertEquals(
            listOf(ClientFrame.Viewing(null, false)),
            framesForSnapshot(
                WorkspaceViewingSnapshot("w1", listOf("s1"), appForeground = false),
            ),
        )
        assertEquals(
            listOf(ClientFrame.Viewing("s1", true, null)),
            framesForSnapshot(
                WorkspaceViewingSnapshot("w1", listOf("s1"), appForeground = true),
            ),
        )
        assertEquals(
            listOf(ClientFrame.Viewing("s1", true, listOf("s1", "s2"))),
            framesForSnapshot(
                WorkspaceViewingSnapshot("w1", listOf("s1", "s2"), appForeground = true),
            ),
        )
    }
}
