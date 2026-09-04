package dev.supermux.host

import dev.supermux.proto.ClientFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Platform-neutral viewing presence: frame shapes, the surface-visible rule and the
 *  previous-host clear on a workspace switch (moved out of Android's ViewingPresenceTest). */
class ViewingPresenceTest {

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
