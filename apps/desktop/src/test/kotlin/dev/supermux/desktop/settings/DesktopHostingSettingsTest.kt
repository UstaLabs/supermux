package dev.supermux.desktop.settings

import dev.supermux.desktop.host.HostingPrefs
import dev.supermux.desktop.host.HostingStatus
import dev.supermux.desktop.host.LidSleepHelper
import dev.supermux.desktop.host.LidStatus
import dev.supermux.ui.settings.HostingCopy
import dev.supermux.ui.settings.LidClosedUi
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

    // ── keep awake ──

    private val on = dev.supermux.net.KeepAwakeState(enabled = true, onBattery = true, active = true, supported = true)

    @Test fun keep_awake_holding_shows_no_line() {
        val ui = keepAwakeUi(on, hasBattery = true, appHeld = false)!!
        assertTrue(ui.enabled)
        assertTrue(ui.switchEnabled)
        assertTrue(ui.showOnBattery)
        assertNull(ui.warning)
        assertNull(ui.note)
        assertNull(keepAwakeUi(null, true, false))
        assertFalse(keepAwakeUi(on, hasBattery = false, appHeld = false)!!.showOnBattery)
        assertFalse(keepAwakeUi(on, hasBattery = null, appHeld = false)!!.showOnBattery)
    }

    @Test fun not_held_shows_the_reason_and_hint_as_a_warning() {
        val denied = on.copy(active = false, reason = "The desktop refused it", reasonCode = "denied", hint = "Add a polkit rule")
        val ui = keepAwakeUi(denied, hasBattery = false, appHeld = false)!!
        assertEquals("The desktop refused it\nAdd a polkit rule", ui.warning)
        assertNull(ui.note)
        // Linux: the app holds it instead — a neutral note, no warning.
        val held = keepAwakeUi(denied, hasBattery = false, appHeld = true)!!
        assertNull(held.warning)
        assertEquals("Kept awake by the supermux app while it's open.", held.note)
    }

    @Test fun on_battery_is_a_neutral_pause() {
        val ui = keepAwakeUi(on.copy(onBattery = false, active = false, reason = "On battery", reasonCode = "on_battery"), true, false)!!
        assertEquals("Paused while on battery", ui.note)
        assertNull(ui.warning)
    }

    @Test fun unsupported_disables_the_switch_with_the_reason() {
        val ui = keepAwakeUi(on.copy(active = false, supported = false, reason = "No inhibitor", reasonCode = "unsupported"), false, false)!!
        assertFalse(ui.switchEnabled)
        assertEquals("No inhibitor", ui.note)
        assertNull(ui.warning)
    }

    @Test fun off_shows_nothing_even_with_a_stale_reason() {
        val ui = keepAwakeUi(on.copy(enabled = false, active = false, reason = "x", reasonCode = "gave_up"), false, false)!!
        assertNull(ui.warning)
        assertNull(ui.note)
    }

    private val installed = LidStatus(pref = true, state = LidSleepHelper.InstallState.INSTALLED)

    @Test fun lid_closed_is_for_mac_laptops_only() {
        assertEquals(LidClosedUi(on = true, installed = true), lidClosedUi(true, true, installed))
        assertNull(lidClosedUi(isMac = true, hasBattery = false, lid = installed))
        assertNull(lidClosedUi(isMac = true, hasBattery = null, lid = installed))
        assertNull(lidClosedUi(isMac = false, hasBattery = true, lid = installed))
    }

    @Test fun the_lid_switch_is_never_on_while_nothing_holds() {
        val missing = lidClosedUi(true, true, LidStatus(pref = true, state = LidSleepHelper.InstallState.NOT_INSTALLED))!!
        assertFalse(missing.on)
        assertFalse(missing.installed)
        assertEquals(HostingCopy.LID_NOT_INSTALLED, missing.note)
        val other = lidClosedUi(true, true, LidStatus(pref = true, state = LidSleepHelper.InstallState.OTHER_USER))!!
        assertFalse(other.on)
        assertFalse(other.installed)
        assertEquals("The lid helper is installed for another user. Tick to reinstall it for you.", other.note)
        // Never chosen, not installed: the password note.
        assertEquals("macOS will ask for your password once.", lidClosedUi(true, true, LidStatus())!!.note)
    }

    @Test fun a_home_outside_users_disables_the_lid_switch() {
        val ui = lidClosedUi(true, true, installed.copy(homeSupported = false))!!
        assertFalse(ui.enabled)
        assertFalse(ui.on)
        assertEquals("Not available for this account's home folder.", ui.note)
    }

    @Test fun a_battery_pause_is_noted() {
        val ui = lidClosedUi(true, true, installed.copy(pausedOnBattery = true))!!
        assertTrue(ui.on)
        assertEquals("Paused while on battery", ui.note)
    }

    @Test fun the_auto_login_hint_needs_a_mac_with_filevault_off() {
        fun power(isMac: Boolean, fv: Boolean?) = desktopPowerUi(on, true, false, null, isMac, LidStatus(), fv)
        assertTrue(power(true, true).autoLoginHint)
        assertFalse(power(true, false).autoLoginHint)
        assertFalse(power(true, null).autoLoginHint)
        assertFalse(power(false, true).autoLoginHint)
    }

    @Test fun only_a_running_broker_shows_keep_awake_but_lid_and_reboot_stay() {
        val p = desktopPowerUi(on, true, false, null, true, installed, true)
        val shown = desktopHostingUiState(
            status = running, prefs = prefs, sessions = 0, build = null, localUrl = null, relayUrl = null,
            logTail = emptyList(), canPair = true, backgroundError = null, gitRequirement = null, power = p,
        )
        assertEquals(p, shown.power)
        val starting = desktopHostingUiState(
            status = HostingStatus.Starting, prefs = prefs, sessions = 0, build = null, localUrl = null, relayUrl = null,
            logTail = emptyList(), canPair = true, backgroundError = null, gitRequirement = null, power = p,
        )
        assertNull(starting.power!!.keepAwake)
        assertEquals(p.lidClosed, starting.power!!.lidClosed)
        assertTrue(starting.power!!.autoLoginHint)
    }
}
