package dev.supermux.desktop.settings

import dev.supermux.desktop.host.HostingPrefs
import dev.supermux.desktop.host.HostingStatus
import dev.supermux.net.GitRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHostingSettingsTest {
    private val prefs = HostingPrefs(hosting = true)
    private val running = HostingStatus.Running(HostingPrefs.DEFAULT_PORT, readOnly = false)

    private fun state(status: HostingStatus = running, git: GitRequirement? = null, sessions: Int = 2) =
        desktopHostingUiState(
            status = status, prefs = prefs, sessions = sessions, build = "1.5.0 (abc)",
            localUrl = "http://192.168.1.5:9898", relayUrl = null, logTail = emptyList(),
            canPair = true, backgroundError = null, gitRequirement = git,
        )

    private val missing = GitRequirement(ok = false, install = "xcode-select", hint = "Install Apple's Command Line Tools")

    @Test fun the_git_banner_maps_from_the_running_brokers_requirement() {
        assertEquals(missing, state(git = missing).gitRequirement)
        assertTrue(state(git = missing).gitMissing)
        assertFalse(state(git = GitRequirement(ok = true)).gitMissing)
        assertFalse(state(git = null).gitMissing)
        // Only a running broker's answer counts.
        assertNull(state(status = HostingStatus.Starting, git = missing).gitRequirement)
        assertFalse(state(status = HostingStatus.CantStart("x"), git = missing).gitMissing)
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

    // ── Install… goes to the local broker ──

    private val posted = mutableListOf<Pair<String, String>>()
    private val post: suspend (String, String) -> dev.supermux.net.InstallGitResult? = { url, token ->
        posted += url to token
        dev.supermux.net.InstallGitResult(ok = true)
    }

    @Test fun install_uses_the_stored_this_computer_token() = kotlinx.coroutines.test.runTest {
        val hosts = listOf(dev.supermux.host.PairedHost(recordId = "r1", hostId = "h1", displayName = "Mac", directUrl = "http://127.0.0.1:9898", token = "stored"))
        assertTrue(installGitOnLocalBroker("http://127.0.0.1:9898", "h1", hosts, wizardToken = "minted", log = {}, post = post))
        assertEquals(listOf("http://127.0.0.1:9898" to "stored"), posted)
    }

    @Test fun a_fresh_unpaired_install_uses_the_wizards_minted_token() = kotlinx.coroutines.test.runTest {
        assertTrue(installGitOnLocalBroker("http://127.0.0.1:9898", "h1", emptyList(), wizardToken = "minted", log = {}, post = post))
        assertEquals(listOf("http://127.0.0.1:9898" to "minted"), posted)
    }

    @Test fun no_token_at_all_does_not_post() = kotlinx.coroutines.test.runTest {
        val log = mutableListOf<String>()
        assertFalse(installGitOnLocalBroker("http://127.0.0.1:9898", null, emptyList(), wizardToken = null, log = { log += it }, post = post))
        assertTrue(posted.isEmpty())
        assertEquals(listOf("install git: no token for this computer"), log)
    }
}
