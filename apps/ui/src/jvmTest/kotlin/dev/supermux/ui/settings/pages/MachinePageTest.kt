package dev.supermux.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BrokerRestart
import dev.supermux.net.GitRequirement
import dev.supermux.net.RunUpdateResult
import dev.supermux.net.UpdateStatus
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.settings.HostingActions
import dev.supermux.ui.settings.HostingPhase
import dev.supermux.ui.settings.HostingPowerUi
import dev.supermux.ui.settings.HostingUiState
import dev.supermux.ui.settings.KeepAwakeUi
import dev.supermux.ui.settings.LidClosedUi
import dev.supermux.ui.settings.HostingCopy
import dev.supermux.ui.settings.SystemSettingsActions
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The machine page's header and While you're away (#97 Step 2, spec §2.4, §10.12, §13; prototype step2.js). */
@OptIn(ExperimentalTestApi::class)
class MachinePageTest {

    private val server = KitMachine("m2", "studio-mini", local = false, online = true, status = "Running")
    private val laptop = KitMachine("m1", "Ahmet's MacBook", local = true, online = true, status = "Running")
    private val chats = MachineChats(total = 7, working = listOf("Settings redesign", "Fix relay reconnect", "Docs pass"))

    private fun status(
        current: String = "0.17.5",
        latest: String? = null,
        available: Boolean = false,
        mode: String = "binary",
        state: String = "idle",
    ) = UpdateStatus(current = current, latest = latest, updateAvailable = available, mode = mode, state = state)

    private class Hosting : HostingActions {
        val calls = mutableListOf<String>()
        override fun setHosting(on: Boolean) { calls += "hosting $on" }
        override fun setBackground(on: Boolean) { calls += "background $on" }
        override fun setRelay(on: Boolean) { calls += "relay $on" }
        override fun restart() { calls += "restart" }
        override fun retry() { calls += "retry" }
        override fun showLog() { calls += "showLog" }
        override fun pairDevice() { calls += "pair" }
        override fun manageIt() { calls += "manage" }
        override suspend fun installGit(): Boolean { calls += "git"; return true }
        override fun setKeepAwake(on: Boolean) { calls += "awake $on" }
        override fun setKeepAwakeOnBattery(on: Boolean) { calls += "battery $on" }
        override fun setLidClosed(on: Boolean) { calls += "lid $on" }
        override fun uninstallLidHelper() { calls += "uninstall lid" }
    }

    private fun hostingState(
        phase: HostingPhase = HostingPhase.Running,
        readOnly: Boolean = false,
        relay: Boolean = true,
        git: GitRequirement? = null,
        failReason: String? = null,
        power: HostingPowerUi? = HostingPowerUi(keepAwake = KeepAwakeUi(enabled = true, onBattery = true, showOnBattery = true)),
        relayUrl: String? = "https://h-abc.relay.supermux.dev",
        checkoutMoved: String? = null,
        backgroundError: String? = null,
    ) = HostingUiState(
        hosting = phase != HostingPhase.Off, statusDot = "", statusText = "", readOnly = readOnly,
        localUrl = "http://192.168.1.24:9898", relayUrl = relayUrl, relay = relay,
        background = true, sessions = 7, logTail = emptyList(), gitRequirement = git,
        power = power, phase = phase, failReason = failReason,
        checkoutMoved = checkoutMoved, backgroundError = backgroundError,
    )

    private fun ComposeUiTest.page(
        machine: KitMachine,
        system: SystemSettingsActions,
        hosting: ThisComputerHosting? = null,
        platform: FakePlatform = FakePlatform(),
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
    ) = setPlatformContent(platform = platform, widthClass = widthClass, pointer = widthClass != WindowWidthClass.Compact) {
        Machines(machine) {
            MachinePage(system, chats = chats, hosting = hosting, pollDelayMs = 10, pollAttempts = 50)
        }
    }

    @Composable
    private fun Machines(m: KitMachine, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(m), m.id) {}) { content() }
    }

    @Test fun a_running_server_says_what_runs_and_its_away_settings_live_on_it() = runComposeUiTest {
        page(server, SystemSettingsActions(updateStatus = { status() }))
        waitForIdle()
        onNodeWithTag("machine_scope").assertTextContains("A server you paired")
        onNodeWithTag("machine_status").assertTextEquals("Running · supermux 0.17.5 · 7 chats, 3 working")
        onNodeWithTag("machine_away_note").assertTextEquals(
            "Running in the background, staying awake and remote access are set on studio-mini itself.",
        )
        onNodeWithTag("machine_check_updates").assertTextEquals("Check for updates")
        onNodeWithTag("machine_host_on").assertDoesNotExist()
    }

    @Test fun restart_asks_nothing_and_says_it_is_down_until_it_is_back() = runComposeUiTest {
        var restarts = 0
        var down = false
        val system = SystemSettingsActions(
            updateStatus = { if (down) null else status() },
            restart = { restarts++; down = true; BrokerRestart.Accepted },
        )
        page(server, system)
        waitForIdle()
        onNodeWithTag("machine_restart").performClick()
        waitForIdle()
        assertEquals(1, restarts)
        onNodeWithTag("settings_sheet").assertDoesNotExist()
        onNodeWithTag("machine_status").assertTextEquals("Restarting… · chats reconnect on their own")
        onNodeWithTag("machine_restart").assertTextEquals("Restarting…").assertIsNotEnabled()
        down = false
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag("machine_status").assertTextEquals("Running · supermux 0.17.5 · 7 chats, 3 working") }.isSuccess
        }
        onNodeWithTag("machine_restart").assertTextEquals("Restart").assertIsEnabled()
    }

    @Test fun a_supermux_no_service_runs_says_it_cannot_restart_itself() = runComposeUiTest {
        page(server, SystemSettingsActions(updateStatus = { status() }, restart = { BrokerRestart.CantRestartItself }))
        waitForIdle()
        onNodeWithTag("machine_restart").performClick()
        waitForIdle()
        onNodeWithTag("machine_cant_restart").assertTextContains(
            "supermux on studio-mini isn’t run by a service, so it can’t restart itself. Restart it where it runs.",
        )
        onNodeWithTag("machine_cant_restart_ok").performClick()
        waitForIdle()
        onNodeWithTag("machine_cant_restart").assertDoesNotExist()
    }

    @Test fun a_supermux_the_app_updates_says_so_instead_of_checking() = runComposeUiTest {
        page(laptop, SystemSettingsActions(updateStatus = { status(mode = "managed") }))
        waitForIdle()
        onNodeWithTag("machine_updates_words").assertTextEquals("Updated with the app")
        onNodeWithTag("machine_check_updates").assertDoesNotExist()
        onNodeWithTag("machine_scope").assertTextContains("This computer")
    }

    @Test fun on_a_phone_it_names_the_machine_the_app_updates() = runComposeUiTest {
        page(laptop, SystemSettingsActions(updateStatus = { status(mode = "managed") }), widthClass = WindowWidthClass.Compact)
        waitForIdle()
        onNodeWithTag("machine_updates_words").assertTextEquals("Updated with the app on Ahmet's MacBook")
    }

    @Test fun check_then_up_to_date() = runComposeUiTest {
        page(server, SystemSettingsActions(updateStatus = { status() }, checkUpdate = { status() }))
        waitForIdle()
        onNodeWithTag("machine_check_updates").performClick()
        waitForIdle()
        onNodeWithTag("machine_updates_words").assertTextEquals("Up to date · 0.17.5")
        onNodeWithTag("machine_update_row").assertDoesNotExist()
    }

    @Test fun an_update_walks_from_ready_through_progress_to_restart_to_finish() = runComposeUiTest {
        val after = ArrayDeque(listOf(status(latest = "0.17.6", available = true, state = "downloading"), status(latest = "0.17.6", state = "restart-required")))
        var current = status()
        var restarts = 0
        val system = SystemSettingsActions(
            updateStatus = { if (after.isNotEmpty() && current.updateAvailable) after.removeFirst().also { current = it } else current },
            checkUpdate = { status(latest = "0.17.6", available = true).also { current = it } },
            runUpdate = { RunUpdateResult(started = true) },
            restart = { restarts++; BrokerRestart.Accepted },
        )
        page(server, system)
        waitForIdle()
        onNodeWithTag("machine_check_updates").performClick()
        waitForIdle()
        onNodeWithText("Update supermux on studio-mini").assertExists()
        onNodeWithTag("machine_update_desc").assertTextEquals("0.17.6 is ready · you have 0.17.5")
        onNodeWithTag("machine_update_button").assertTextEquals("Update").performClick()
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag("machine_update_desc").assertTextEquals("0.17.6 is installed · restart to finish") }.isSuccess
        }
        onNodeWithTag("machine_update_button").assertTextEquals("Restart").performClick()
        waitForIdle()
        assertEquals(1, restarts)
    }

    @Test fun a_failed_update_says_nothing_changed_and_offers_try_again() = runComposeUiTest {
        val failed = UpdateStatus(current = "0.17.5", latest = "0.17.6", mode = "binary", state = "failed", lastError = "studio-mini lost its connection")
        page(server, SystemSettingsActions(updateStatus = { failed }))
        waitForIdle()
        onNodeWithTag("machine_update_desc").assertTextEquals("Couldn’t update to 0.17.6: studio-mini lost its connection. Nothing changed.")
        onNodeWithTag("machine_update_button").assertTextEquals("Try again")
    }

    @Test fun offline_has_no_buttons() = runComposeUiTest {
        page(server.copy(online = false, lastSeen = "2h ago"), SystemSettingsActions(updateStatus = { status() }))
        waitForIdle()
        onNodeWithTag("machine_status").assertTextEquals("Offline · last seen 2h ago")
        onNodeWithTag("machine_restart").assertDoesNotExist()
    }

    // ── This computer ───────────────────────────────────────────────────────────────────────

    @Test fun this_computer_restarts_through_its_supervisor() = runComposeUiTest {
        var supervised = 0
        var api = 0
        val h = Hosting()
        page(
            laptop,
            SystemSettingsActions(updateStatus = { status(mode = "managed") }, restart = { api++; BrokerRestart.Accepted }),
            hosting = ThisComputerHosting(hostingState(), h, restart = { supervised++ }),
        )
        waitForIdle()
        onNodeWithTag("machine_restart").performClick()
        waitForIdle()
        assertEquals(1, supervised)
        assertEquals(0, api)
    }

    @Test fun a_service_run_supermux_restarts_through_the_api_and_shows_its_switches_read_only() = runComposeUiTest {
        var supervised = 0
        var api = 0
        val h = Hosting()
        page(
            laptop,
            SystemSettingsActions(updateStatus = { status() }, restart = { api++; BrokerRestart.Accepted }),
            hosting = ThisComputerHosting(hostingState(readOnly = true), h, restart = { supervised++ }),
        )
        waitForIdle()
        onNodeWithTag("machine_service").assertTextContains("Run by your system’s service.", substring = true)
        onNodeWithTag("machine_keep_running").assertIsNotEnabled()
        onNodeWithTag("machine_relay").assertIsNotEnabled()
        onNodeWithTag("machine_stop_hosting").assertDoesNotExist()
        onNodeWithTag("machine_restart").performClick()
        waitForIdle()
        assertEquals(0, supervised)
        assertEquals(1, api)
        onNodeWithTag("machine_manage").performClick()
        waitForIdle()
        onNodeWithText("Let the app manage supermux?").assertExists()
        onNodeWithTag("machine_manage_confirm").performClick()
        waitForIdle()
        assertEquals(listOf("manage"), h.calls)
    }

    @Test fun while_you_are_away_holds_the_hosting_switches_and_addresses() = runComposeUiTest {
        val h = Hosting()
        val platform = FakePlatform()
        page(laptop, SystemSettingsActions(updateStatus = { status(mode = "managed") }), ThisComputerHosting(hostingState(), h, recentLog = listOf("a", "b", "c", "d")), platform)
        waitForIdle()
        onNodeWithTag("machine_keep_running").assertTextContains("Keep running in the background", substring = true)
        onNodeWithTag("machine_keep_awake").assertExists()
        onNodeWithTag("machine_battery").assertExists()
        onNodeWithTag("machine_relay").assertTextContains(
            "Your phone reaches it away from home too. Turning this on or off restarts supermux.", substring = true,
        )
        onNodeWithTag("machine_relay").performScrollTo().performClick()
        waitForIdle()
        assertTrue("relay false" in h.calls)
        onNodeWithTag("machine_address_lan_text", useUnmergedTree = true).assertTextEquals("http://192.168.1.24:9898")
        onNodeWithTag("machine_copy_lan").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("http://192.168.1.24:9898"), platform.copied)
        onNodeWithTag("machine_log_tail", useUnmergedTree = true).assertTextEquals("b\nc\nd")
    }

    @Test fun stop_hosting_asks_first_and_names_the_working_chats() = runComposeUiTest {
        val h = Hosting()
        page(laptop, SystemSettingsActions(updateStatus = { status(mode = "managed") }), ThisComputerHosting(hostingState(), h))
        waitForIdle()
        onNodeWithTag("machine_stop_hosting").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("machine_stop_text").assertTextEquals(
            "This stops supermux and the 3 chats working right now: “Settings redesign”, “Fix relay reconnect” and “Docs pass”. They stop mid-reply.",
        )
        onNodeWithTag("machine_stop_text_2").assertTextEquals(
            "The other 4 chats wait until you host again. Nothing is deleted, and supermux no longer starts with this computer.",
        )
        assertTrue(h.calls.isEmpty())
        onNodeWithTag("machine_stop_confirm").performClick()
        waitForIdle()
        assertEquals(listOf("hosting false"), h.calls)
    }

    @Test fun not_hosting_offers_the_turn_on_sheet() = runComposeUiTest {
        val started = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        page(
            laptop.copy(online = false),
            SystemSettingsActions(),
            ThisComputerHosting(hostingState(HostingPhase.Off), Hosting(), startHosting = { a, b, c -> started += Triple(a, b, c) }),
        )
        waitForIdle()
        onNodeWithTag("machine_status").assertTextEquals("Not hosting · supermux isn’t running on this computer")
        onNodeWithTag("machine_restart").assertDoesNotExist()
        onNodeWithTag("machine_host_on").performClick()
        waitForIdle()
        onNodeWithTag("machine_turn_on_sheet").assertExists()
        onNodeWithTag("machine_turn_on_relay").performClick()
        onNodeWithTag("machine_turn_on_start").performClick()
        waitForIdle()
        assertEquals(listOf(Triple(true, true, false)), started)
        onNodeWithTag("machine_turn_on_sheet").assertDoesNotExist()
    }

    @Test fun a_start_that_failed_says_why_and_tries_again() = runComposeUiTest {
        val h = Hosting()
        page(
            laptop.copy(online = false),
            SystemSettingsActions(),
            ThisComputerHosting(
                hostingState(HostingPhase.Failed, failReason = "another program is using port 9898"), h,
                readLog = { listOf("one", "two") },
            ),
        )
        waitForIdle()
        onNodeWithTag("machine_status").assertTextEquals("Didn’t start")
        onNodeWithTag("machine_failed").assertTextContains("supermux didn’t start: another program is using port 9898")
        onNodeWithTag("machine_retry").performClick()
        waitForIdle()
        assertEquals(listOf("retry"), h.calls)
        onNodeWithTag("machine_show_log").performClick()
        waitForIdle()
        onNodeWithTag("machine_log_lines").assertTextEquals("one\ntwo")
        onNodeWithTag("machine_log_count").assertTextEquals("2 lines")
    }

    @Test fun missing_git_offers_install() = runComposeUiTest {
        val h = Hosting()
        page(
            laptop, SystemSettingsActions(updateStatus = { status(mode = "managed") }),
            ThisComputerHosting(hostingState(git = GitRequirement(ok = false, install = GitRequirement.INSTALL_XCODE_SELECT)), h),
        )
        waitForIdle()
        onNodeWithTag("machine_git").assertTextContains("git isn’t installed on this computer.", substring = true)
        onNodeWithTag("machine_install_git").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("git"), h.calls)
    }

    @Test fun stop_copy_with_one_or_no_working_chat() {
        assertEquals(
            "This stops supermux and the chat working right now: “a”. It stops mid-reply." to
                "Nothing is deleted, and supermux no longer starts with this computer.",
            MachineCopy.stopText(MachineChats(1, listOf("a"))),
        )
        assertEquals(
            "This stops supermux on this computer. No chat is working right now." to
                "Your 2 chats wait until you host again. Nothing is deleted, and supermux no longer starts with this computer.",
            MachineCopy.stopText(MachineChats(2)),
        )
    }

    // ── Carried from today's System and Hosting screens (#97 S2-4) ────────────────────────────────

    @Test fun a_failed_check_says_so_and_keeps_what_it_knew() = runComposeUiTest {
        page(server, SystemSettingsActions(updateStatus = { status() }, checkUpdate = { null }))
        waitForIdle()
        onNodeWithTag("machine_check_updates").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("machine_check_failed").assertExists() }.isSuccess }
        onNodeWithTag("machine_check_failed").assertTextEquals("Couldn’t check for updates on studio-mini.")
        onNodeWithTag("machine_status").assertTextEquals("Running · supermux 0.17.5 · 7 chats, 3 working")
        onNodeWithTag("machine_check_updates").assertTextEquals("Check for updates")
    }

    @Test fun an_update_that_cannot_start_says_why_and_one_unanswered_says_so() = runComposeUiTest {
        var answer: RunUpdateResult? = RunUpdateResult(started = false, instruction = "Use git pull.")
        page(server, SystemSettingsActions(updateStatus = { status(latest = "0.17.6", available = true) }, runUpdate = { answer }))
        waitForIdle()
        onNodeWithTag("machine_update_button").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("machine_update_button").assertTextEquals("Try again") }.isSuccess }
        onNodeWithTag("machine_update_desc").assertTextEquals("Couldn’t update to 0.17.6: Use git pull. Nothing changed.")
        answer = null
        onNodeWithTag("machine_update_button").performClick()
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag("machine_update_desc").assertTextContains("studio-mini didn’t answer", substring = true) }.isSuccess
        }
    }

    @Test fun keep_awake_battery_and_lid_drive_their_actions() = runComposeUiTest {
        val h = Hosting()
        val power = HostingPowerUi(
            keepAwake = KeepAwakeUi(enabled = true, onBattery = false, showOnBattery = true),
            lidClosed = LidClosedUi(on = false, installed = true),
        )
        page(laptop, SystemSettingsActions(updateStatus = { status(mode = "managed") }), ThisComputerHosting(hostingState(power = power), h))
        waitForIdle()
        onNodeWithTag("machine_keep_awake").performScrollTo().performClick()
        onNodeWithTag("machine_battery").performScrollTo().performClick()
        onNodeWithTag("machine_lid").performScrollTo().performClick()
        onNodeWithTag("machine_lid_uninstall").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("awake false", "battery true", "lid true", "uninstall lid"), h.calls)
    }

    @Test fun no_battery_no_battery_row_and_no_mac_laptop_no_lid() = runComposeUiTest {
        val power = HostingPowerUi(keepAwake = KeepAwakeUi(enabled = true, onBattery = false, showOnBattery = false))
        page(laptop, SystemSettingsActions(updateStatus = { status(mode = "managed") }), ThisComputerHosting(hostingState(power = power), Hosting()))
        waitForIdle()
        onNodeWithTag("machine_keep_awake").assertExists()
        onNodeWithTag("machine_battery").assertDoesNotExist()
        onNodeWithTag("machine_lid").assertDoesNotExist()
        onNodeWithTag("machine_lid_uninstall").assertDoesNotExist()
    }

    @Test fun keep_awake_says_why_it_is_not_held_and_unsupported_turns_the_switch_off() = runComposeUiTest {
        var power by mutableStateOf(
            HostingPowerUi(keepAwake = KeepAwakeUi(enabled = true, onBattery = false, warning = "Not held: log in again", note = HostingCopy.PAUSED_ON_BATTERY)),
        )
        setPlatformContent(platform = FakePlatform(), widthClass = WindowWidthClass.Expanded, pointer = true) {
            Machines(laptop) {
                MachinePage(
                    SystemSettingsActions(updateStatus = { status(mode = "managed") }), chats = chats,
                    hosting = ThisComputerHosting(hostingState(power = power), Hosting()),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("machine_keep_awake_warning").assertTextEquals("Not held: log in again")
        onNodeWithTag("machine_keep_awake_note").assertTextEquals("Paused while on battery")
        onNodeWithTag("machine_keep_awake").assertIsEnabled()
        power = HostingPowerUi(keepAwake = KeepAwakeUi(enabled = false, onBattery = false, switchEnabled = false, note = "Not supported here"))
        waitForIdle()
        onNodeWithTag("machine_keep_awake").assertIsNotEnabled()
        onNodeWithTag("machine_keep_awake_note").assertTextEquals("Not supported here")
        onNodeWithTag("machine_keep_awake_warning").assertDoesNotExist()
    }

    @Test fun the_lid_switch_greys_while_asking_and_where_it_cannot_be_used() = runComposeUiTest {
        var lid by mutableStateOf(LidClosedUi(on = false, installed = true, busy = true))
        setPlatformContent(platform = FakePlatform(), widthClass = WindowWidthClass.Expanded, pointer = true) {
            Machines(laptop) {
                MachinePage(
                    SystemSettingsActions(updateStatus = { status(mode = "managed") }), chats = chats,
                    hosting = ThisComputerHosting(
                        hostingState(power = HostingPowerUi(keepAwake = KeepAwakeUi(enabled = true, onBattery = false), lidClosed = lid)),
                        Hosting(),
                    ),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("machine_lid").assertIsNotEnabled()
        onNodeWithTag("machine_lid_note").assertTextEquals("Waiting for your password…")
        onNodeWithTag("machine_lid_uninstall").assertIsNotEnabled()
        lid = LidClosedUi(on = false, installed = false, enabled = false, note = HostingCopy.LID_HOME_UNSUPPORTED)
        waitForIdle()
        onNodeWithTag("machine_lid").assertIsNotEnabled()
        onNodeWithTag("machine_lid_note").assertTextEquals("Not available for this account's home folder.")
        onNodeWithTag("machine_lid_uninstall").assertDoesNotExist()
    }

    @Test fun the_auto_login_hint_shows_only_with_file_vault_off_and_no_power_hides_the_rows() = runComposeUiTest {
        var power by mutableStateOf<HostingPowerUi?>(HostingPowerUi(keepAwake = KeepAwakeUi(enabled = true, onBattery = false), autoLoginHint = true))
        setPlatformContent(platform = FakePlatform(), widthClass = WindowWidthClass.Expanded, pointer = true) {
            Machines(laptop) {
                MachinePage(
                    SystemSettingsActions(updateStatus = { status(mode = "managed") }), chats = chats,
                    hosting = ThisComputerHosting(hostingState(power = power), Hosting()),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("machine_auto_login_hint").assertTextContains("Automatic Login", substring = true)
        power = null
        waitForIdle()
        onNodeWithTag("machine_auto_login_hint").assertDoesNotExist()
        onNodeWithTag("machine_keep_awake").assertDoesNotExist()
        onNodeWithTag("machine_keep_running").assertExists()
    }

    @Test fun a_moved_checkout_and_a_background_error_are_shown() = runComposeUiTest {
        page(
            laptop, SystemSettingsActions(updateStatus = { status(mode = "managed") }),
            ThisComputerHosting(hostingState(checkoutMoved = "Restart it to run the new code.", backgroundError = "launchctl refused"), Hosting()),
        )
        waitForIdle()
        onNodeWithTag("machine_checkout_moved").assertTextEquals("Restart it to run the new code.")
        onNodeWithTag("machine_background_error").assertTextEquals("launchctl refused")
    }

    @Test fun the_relay_address_copies_and_reads_off_without_one() = runComposeUiTest {
        val platform = FakePlatform()
        var state by mutableStateOf(hostingState())
        setPlatformContent(platform = platform, widthClass = WindowWidthClass.Expanded, pointer = true) {
            Machines(laptop) {
                MachinePage(SystemSettingsActions(updateStatus = { status(mode = "managed") }), chats = chats, hosting = ThisComputerHosting(state, Hosting()))
            }
        }
        waitForIdle()
        onNodeWithTag("machine_address_relay_text", useUnmergedTree = true).assertTextEquals("https://h-abc.relay.supermux.dev")
        onNodeWithTag("machine_copy_relay").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("https://h-abc.relay.supermux.dev"), platform.copied)
        state = hostingState(relay = false)
        waitForIdle()
        onNodeWithTag("machine_address_relay_text", useUnmergedTree = true).assertTextEquals("Off")
        onNodeWithTag("machine_copy_relay").assertDoesNotExist()
        state = hostingState(relayUrl = null)
        waitForIdle()
        onNodeWithTag("machine_address_relay_text", useUnmergedTree = true).assertTextEquals("Off")
    }

    @Test fun background_switch_calls_its_action_and_git_present_says_nothing() = runComposeUiTest {
        val h = Hosting()
        page(laptop, SystemSettingsActions(updateStatus = { status(mode = "managed") }), ThisComputerHosting(hostingState(git = GitRequirement(ok = true)), h))
        waitForIdle()
        onNodeWithTag("machine_git").assertDoesNotExist()
        onNodeWithTag("machine_keep_running").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("background false"), h.calls)
    }

    @Test fun an_update_in_flight_is_running() {
        for (st in listOf("starting", "checking", "downloading", "swapping")) assertTrue(dev.supermux.ui.settings.isRunningState(st), st)
        for (st in listOf("idle", "restart-required", "failed")) kotlin.test.assertFalse(dev.supermux.ui.settings.isRunningState(st), st)
    }
}
