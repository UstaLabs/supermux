package dev.supermux.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.net.GitRequirement
import dev.supermux.ui.host.GitBannerCopy
import dev.supermux.ui.host.GitBannerTags
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HostingSettingsScreenTest {

    private class Recorder : HostingActions {
        val calls = mutableListOf<String>()
        override fun setHosting(on: Boolean) { calls += "hosting:$on" }
        override fun setBackground(on: Boolean) { calls += "background:$on" }
        override fun setRelay(on: Boolean) { calls += "relay:$on" }
        override fun restart() { calls += "restart" }
        override fun retry() { calls += "retry" }
        override fun showLog() { calls += "log" }
        override fun pairDevice() { calls += "pair" }
        override fun manageIt() { calls += "manage" }
        override suspend fun installGit(): Boolean { calls += "installGit"; return true }
    }

    private fun running(
        readOnly: Boolean = false,
        logTail: List<String> = emptyList(),
        relayUrl: String? = "https://h-abc.relay.supermux.dev",
        backgroundError: String? = null,
        gitRequirement: GitRequirement? = null,
    ) = HostingUiState(
        hosting = true,
        statusDot = "🟢",
        statusText = if (readOnly) "Running · set up outside the app" else "Running · 3 sessions · v1.5.0",
        readOnly = readOnly,
        localUrl = "http://192.168.1.5:7777",
        relayUrl = relayUrl,
        relay = relayUrl != null,
        background = true,
        sessions = 3,
        logTail = logTail,
        failed = logTail.isNotEmpty(),
        restartEnabled = !readOnly,
        backgroundError = backgroundError,
        gitRequirement = gitRequirement,
    )

    private val off = HostingUiState(
        hosting = false, statusDot = "⚪", statusText = "Not hosting", readOnly = false, localUrl = null,
        relayUrl = null, relay = false, background = false, sessions = 0, logTail = emptyList(),
    )

    @Test fun a_running_shows_status_background_restart_and_pair() = runComposeUiTest {
        setPlatformContent { HostingSettingsScreen(running(), Recorder()) }
        onNodeWithTag("hosting_switch").assertIsOn()
        onNodeWithTag("hosting_status").assertTextContains("Running", substring = true)
        onNodeWithTag("hosting_status").assertTextContains("3 sessions", substring = true)
        onNodeWithTag("hosting_background").assertIsOn()
        onNodeWithTag("hosting_restart").assertExists()
        onNodeWithTag("hosting_pair").assertExists()
        onNodeWithTag("hosting_show_log").assertExists()
        onNodeWithText("Your agents stay reachable after you quit, sign out or restart.").assertExists()
    }

    @Test fun b_off_shows_only_the_switch_and_the_line() = runComposeUiTest {
        setPlatformContent { HostingSettingsScreen(off, Recorder()) }
        onNodeWithTag("hosting_switch").assertIsOff()
        onNodeWithText("This app connects to brokers elsewhere.").assertExists()
        onNodeWithTag("hosting_restart").assertDoesNotExist()
        onNodeWithTag("hosting_status").assertDoesNotExist()
        onNodeWithTag("hosting_background").assertDoesNotExist()
    }

    @Test fun c_read_only_offers_manage_and_disables_background() = runComposeUiTest {
        val rec = Recorder()
        setPlatformContent { HostingSettingsScreen(running(readOnly = true), rec) }
        onNodeWithTag("hosting_status").assertTextContains("set up outside the app", substring = true)
        onNodeWithTag("hosting_background").assertIsNotEnabled()
        onNodeWithText("Managed by its own service").assertExists()
        onNodeWithTag("hosting_manage").assertExists().performClick()
        assertEquals(listOf("manage"), rec.calls)
    }

    @Test fun d_cant_start_shows_log_tail_and_try_again() = runComposeUiTest {
        val rec = Recorder()
        setPlatformContent { HostingSettingsScreen(running(logTail = listOf("boom", "EADDRINUSE")), rec) }
        onNodeWithTag("hosting_log_tail").assertTextContains("EADDRINUSE", substring = true)
        onNodeWithTag("hosting_restart").assertDoesNotExist()
        onNodeWithTag("hosting_retry").assertExists().performClick()
        assertEquals(listOf("retry"), rec.calls)
    }

    @Test fun e_turning_off_confirms_first_and_then_stops() = runComposeUiTest {
        val rec = Recorder()
        setPlatformContent { HostingSettingsScreen(running(), rec) }
        onNodeWithTag("hosting_switch").performClick()
        assertEquals(emptyList(), rec.calls)
        onNodeWithTag("hosting_off_confirm_text").assertTextContains(
            "Stop hosting? This stops supermux and your 3 running sessions, and removes it from startup. " +
                "Your data stays in ~/.mux.",
        )
        onNodeWithTag("hosting_off_confirm").performClick()
        assertEquals(listOf("hosting:false"), rec.calls)
    }

    @Test fun with_no_sessions_the_confirm_does_not_count_them() = runComposeUiTest {
        setPlatformContent { HostingSettingsScreen(running().copy(sessions = 0), Recorder()) }
        onNodeWithTag("hosting_switch").performClick()
        onNodeWithTag("hosting_off_confirm_text").assertTextEquals(
            "Stop hosting? This stops supermux and removes it from startup. Your data stays in ~/.mux.",
        )
    }

    @Test fun turning_off_can_be_cancelled() = runComposeUiTest {
        val rec = Recorder()
        setPlatformContent { HostingSettingsScreen(running(), rec) }
        onNodeWithTag("hosting_switch").performClick()
        onNodeWithTag("hosting_off_cancel").performClick()
        onNodeWithTag("hosting_off_dialog").assertDoesNotExist()
        assertEquals(emptyList(), rec.calls)
    }

    @Test fun turning_on_needs_no_confirm() = runComposeUiTest {
        val rec = Recorder()
        setPlatformContent { HostingSettingsScreen(off, rec) }
        onNodeWithTag("hosting_switch").performClick()
        assertEquals(listOf("hosting:true"), rec.calls)
    }

    @Test fun background_error_is_shown() = runComposeUiTest {
        setPlatformContent { HostingSettingsScreen(running(backgroundError = "Background needs the packaged app."), Recorder()) }
        onNodeWithTag("hosting_background_error").assertTextContains("Background needs the packaged app.")
    }

    @Test fun copy_puts_the_remote_address_on_the_clipboard() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform = platform) { HostingSettingsScreen(running(), Recorder()) }
        onNodeWithTag("hosting_local_url").assertTextContains("http://192.168.1.5:7777")
        onNodeWithTag("hosting_remote_url").assertTextContains("https://h-abc.relay.supermux.dev")
        onNodeWithTag("hosting_copy_remote").assertExists().performClick()
        assertEquals(listOf("https://h-abc.relay.supermux.dev"), platform.copied)
    }

    @Test fun no_relay_url_hides_the_remote_row() = runComposeUiTest {
        setPlatformContent { HostingSettingsScreen(running(relayUrl = null), Recorder()) }
        onNodeWithTag("hosting_remote_url").assertDoesNotExist()
        onNodeWithTag("hosting_copy_remote").assertDoesNotExist()
        onNodeWithTag("hosting_relay").assertIsOff()
    }

    @Test fun switches_and_buttons_call_their_actions() = runComposeUiTest {
        val rec = Recorder()
        setPlatformContent { HostingSettingsScreen(running(), rec) }
        onNodeWithTag("hosting_background").assertIsEnabled().performClick()
        onNodeWithTag("hosting_relay").performClick()
        onNodeWithTag("hosting_restart").performClick()
        onNodeWithTag("hosting_show_log").performClick()
        onNodeWithTag("hosting_pair").performClick()
        assertEquals(listOf("background:false", "relay:false", "restart", "log", "pair"), rec.calls)
    }

    @Test fun there_is_no_end_to_end_caveat() = runComposeUiTest {
        setPlatformContent { HostingSettingsScreen(running(), Recorder()) }
        onNodeWithText("end-to-end", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test fun git_missing_shows_the_broker_banner_and_install_runs_the_action() = runComposeUiTest {
        val r = Recorder()
        val req = GitRequirement(ok = false, install = "xcode-select", hint = "Install Apple's Command Line Tools")
        setPlatformContent { HostingSettingsScreen(running(gitRequirement = req), r) }
        onNodeWithTag(GitBannerTags.BANNER).assertExists()
        onNodeWithText(GitBannerCopy.TITLE).assertExists()
        onNodeWithTag(GitBannerTags.HINT).assertTextEquals("Install Apple's Command Line Tools")
        onNodeWithTag(GitBannerTags.INSTALL).assertTextEquals(GitBannerCopy.INSTALL).performClick()
        waitForIdle()
        assertEquals(listOf("installGit"), r.calls)
    }

    @Test fun git_present_shows_no_banner() = runComposeUiTest {
        setPlatformContent { HostingSettingsScreen(running(gitRequirement = GitRequirement(ok = true)), Recorder()) }
        onNodeWithTag(GitBannerTags.BANNER).assertDoesNotExist()
        onNodeWithTag(GitBannerTags.INSTALL).assertDoesNotExist()
    }
}
