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
}
