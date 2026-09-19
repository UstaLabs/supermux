package dev.supermux.ui.shell.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The registry side of a separate-composition extra window (Android activity, iPad scene). */
class ExtraWindowsTest {
    private val claim = PersistedWindowHost("w1", "ws", listOf("v1", "v2"), 0f, 0f, 0f, 0f)

    @Test
    fun aClaimSurvivesTheStringAnIpadSceneCarriesIt() {
        assertEquals(claim, decodeClaim(claim.encode()))
        val empty = claim.copy(claimedViewIds = emptyList())
        assertEquals(empty, decodeClaim(empty.encode()))
    }

    @Test
    fun anythingElseIsNotAClaim() {
        assertNull(decodeClaim(""))
        assertNull(decodeClaim("w1|ws"))
        assertNull(decodeClaim("|ws|v1"))
    }

    @Test
    fun aRestoredWindowParksItsClaimOnceAndClosingForgetsIt() {
        val windows = RegistryShellWindows(keepPendingComposed = true)
        ExtraWindows.adopt(windows, claim)
        ExtraWindows.adopt(windows, claim)
        assertEquals(listOf(claim), windows.pending)
        assertEquals(setOf("ws"), windows.extraWorkspaceIds())

        ExtraWindows.close(windows, "w1")
        assertTrue(windows.pending.isEmpty())
        assertTrue(windows.extraWorkspaceIds().isEmpty())
    }

    @Test
    fun desktopDoesNotComposeAWorkspaceForAPendingWindow() {
        val windows = RegistryShellWindows()
        ExtraWindows.adopt(windows, claim)
        assertTrue(windows.extraWorkspaceIds().isEmpty())
    }

    @Test
    fun aRestoredClaimWhoseViewsWereClosedIsDroppedAndOneThatLostSomeKeepsTheRest() {
        val windows = RegistryShellWindows(keepPendingComposed = true)
        windows.setWorkspaceOnMain("ws")
        val gone = PersistedWindowHost("gone", "ws", listOf("closed"), 0f, 0f, 0f, 0f)
        val partial = PersistedWindowHost("partial", "ws", listOf("v2", "closed-too"), 0f, 0f, 0f, 0f)
        windows.pending = listOf(gone, partial)
        val tree = dev.supermux.workspace.LayoutNode.Split(
            "row",
            listOf(0.5, 0.5),
            listOf(
                dev.supermux.workspace.LayoutNode.Group("g1", listOf("v1"), "v1"),
                dev.supermux.workspace.LayoutNode.Group("g2", listOf("v2"), "v2"),
            ),
        )
        windows.onWorkspaceTree("ws", tree)
        assertTrue(windows.pending.isEmpty())
        assertEquals(setOf("v2"), windows.registry.extras().single { it.id == "partial" }.claimedViewIds)
        assertTrue(windows.registry.extras().none { it.id == "gone" })
    }

    @Test
    fun aRestoredClaimWaitsForATreeThatHasLoaded() {
        val windows = RegistryShellWindows(keepPendingComposed = true)
        windows.pending = listOf(claim)
        windows.onWorkspaceTree("ws", dev.supermux.workspace.LayoutNode.Group("g", emptyList(), null))
        assertEquals(listOf(claim), windows.pending)
    }
}
