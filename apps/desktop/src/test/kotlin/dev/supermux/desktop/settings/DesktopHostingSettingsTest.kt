package dev.supermux.desktop.settings

import dev.supermux.desktop.host.HostingPrefs
import dev.supermux.desktop.host.HostingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHostingSettingsTest {
    private val prefs = HostingPrefs(hosting = true)
    private val running = HostingStatus.Running(HostingPrefs.DEFAULT_PORT, readOnly = false)

    private fun state(status: HostingStatus = running, gitAvailable: Boolean? = null, sessions: Int = 2) =
        desktopHostingUiState(
            status = status, prefs = prefs, sessions = sessions, build = "1.5.0 (abc)",
            localUrl = "http://192.168.1.5:9898", relayUrl = null, logTail = emptyList(),
            canPair = true, backgroundError = null, gitAvailable = gitAvailable,
        )

    @Test fun git_missing_only_when_the_running_broker_says_false() {
        assertTrue(state(gitAvailable = false).gitMissing)
        assertFalse(state(gitAvailable = true).gitMissing)
        assertFalse(state(gitAvailable = null).gitMissing)
        assertFalse(state(status = HostingStatus.Starting, gitAvailable = false).gitMissing)
        assertFalse(state(status = HostingStatus.CantStart("x"), gitAvailable = false).gitMissing)
    }

    @Test fun running_maps_status_line_url_and_buttons() {
        val s = state()
        assertEquals("Running · 2 sessions · v1.5.0", s.statusText)
        assertEquals("http://192.168.1.5:9898", s.localUrl)
        assertTrue(s.restartEnabled)
        assertTrue(s.canPair)
        assertEquals(2, s.sessions)
    }

    @Test fun not_running_hides_the_address_and_pairing() {
        val s = state(status = HostingStatus.Starting)
        assertNull(s.localUrl)
        assertFalse(s.canPair)
        assertFalse(s.restartEnabled)
    }
}
