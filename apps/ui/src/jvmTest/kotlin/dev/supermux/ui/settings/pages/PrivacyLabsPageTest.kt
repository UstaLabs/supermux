package dev.supermux.ui.settings.pages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.TelemetryRecent
import dev.supermux.net.TelemetryState
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.AssistantOverlayHost
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.settings.SystemSettingsActions
import dev.supermux.ui.settings.kit.KitAnchorKey
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Privacy & labs (#97 spec §2.2, §10.5): the machine's usage-statistics switch with its lock reason, recent events
 * and the "what we send" document; and, where the platform has one, the assistant overlay with its permission walk.
 * Absorbs the old `TelemetrySettingsSectionTest` and Appearance's overlay row.
 */
@OptIn(ExperimentalTestApi::class)
class PrivacyLabsPageTest {

    private val overlayCaps = NO_CAPS.copy(assistantOverlay = true)

    /** A recording overlay host: [canDraw] is what the system answers. */
    private class FakeOverlayHost(var canDraw: Boolean) : AssistantOverlayHost {
        var permissionPages = 0
        var micAsks = 0
        override fun canDrawOverOtherApps() = canDraw
        override fun openPermissionPage() { permissionPages++ }
        override suspend fun requestMic(): Boolean { micAsks++; return true }
        override val screenNote = "Double-tap it to put its glasses on."
    }

    private fun overlayPlatform(host: AssistantOverlayHost) = object : FakePlatform(caps = overlayCaps) {
        override val assistantOverlay: AssistantOverlayHost = host
    }

    private fun anchored(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    @Test fun whats_sent_opens_the_document_through_the_platform() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform = platform) {
            PrivacyLabsPage(SystemSettingsActions(getTelemetry = { TelemetryState(enabled = true) }))
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("system_telemetry_doc").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("What we send").assertExists()
        onNodeWithTag("system_telemetry_doc").performClick()
        waitForIdle()
        assertEquals(listOf(TELEMETRY_DOC_URL), platform.openedUrls)
    }

    @Test fun the_switch_saves_through_the_machine_and_carries_its_anchor() = runComposeUiTest {
        val sent = mutableListOf<Boolean>()
        setPlatformContent {
            PrivacyLabsPage(
                SystemSettingsActions(
                    getTelemetry = { TelemetryState(enabled = true) },
                    setTelemetry = { sent += it; TelemetryState(enabled = it) },
                ),
            )
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("system_telemetry_switch").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Send anonymous usage statistics").assertExists()
        onNodeWithText("Which agents, models and features you use. Never prompts, code, paths or names.").assertExists()
        onNode(anchored("telemetry")).assertExists()
        onNodeWithTag("system_telemetry_switch").performClick()
        waitForIdle()
        assertEquals(listOf(false), sent)
    }

    @Test fun a_failed_save_says_so() = runComposeUiTest {
        setPlatformContent {
            PrivacyLabsPage(
                SystemSettingsActions(getTelemetry = { TelemetryState(enabled = true) }, setTelemetry = { null }),
            )
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("system_telemetry_switch").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("system_telemetry_switch").performClick()
        waitForIdle()
        onNodeWithText("Couldn't change the setting.").assertExists()
    }

    @Test fun a_locked_switch_says_where_it_was_turned_off_without_env_names() = runComposeUiTest {
        setPlatformContent {
            PrivacyLabsPage(SystemSettingsActions(getTelemetry = { TelemetryState(enabled = false, source = "env") }))
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("system_telemetry_switch").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Turned off on this machine.").assertExists()
        onAllNodes(hasText("SUPERMUX_", substring = true)).assertCountEquals(0)
        onAllNodes(hasText("MUX_TELEMETRY", substring = true)).assertCountEquals(0)
        onAllNodes(hasText("DO_NOT_TRACK", substring = true)).assertCountEquals(0)
        onNodeWithTag("system_telemetry_switch").assertIsNotEnabled()
    }

    @Test fun a_locked_switch_names_the_machine_it_was_turned_off_on() = runComposeUiTest {
        val mini = KitMachine("m2", "studio-mini", local = false, online = true, status = "Running")
        setPlatformContent {
            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(mini), "m2") {}) {
                PrivacyLabsPage(SystemSettingsActions(getTelemetry = { TelemetryState(enabled = false, source = "env") }))
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("system_telemetry_switch").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Turned off on studio-mini.").assertExists()
        onNodeWithText("on studio-mini").assertExists()
    }

    @Test fun a_test_build_says_nothing_is_sent_without_naming_the_broker() = runComposeUiTest {
        setPlatformContent {
            PrivacyLabsPage(SystemSettingsActions(getTelemetry = { TelemetryState(enabled = false, source = "build") }))
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("system_telemetry_switch").fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("broker", substring = true, ignoreCase = true)).assertCountEquals(0)
        onNodeWithTag("system_telemetry_switch").assertIsNotEnabled()
    }

    @Test fun recent_events_open_in_a_sheet() = runComposeUiTest {
        val event = JsonObject(mapOf("event" to JsonPrimitive("app_open"), "sent" to JsonPrimitive(true)))
        setPlatformContent {
            PrivacyLabsPage(
                SystemSettingsActions(
                    getTelemetry = { TelemetryState(enabled = true) },
                    getTelemetryRecent = { TelemetryRecent(listOf(event)) },
                ),
            )
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("system_telemetry_recent").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Recent events").assertExists()
        onNodeWithTag("system_telemetry_recent").performClick()
        waitForIdle()
        onNodeWithTag("system_telemetry_recent_dialog").assertExists()
        onNodeWithText("sent").assertExists()
        onNode(hasText("app_open", substring = true)).assertExists()
        onNodeWithText("Close").performClick()
        waitForIdle()
        onNodeWithTag("system_telemetry_recent_dialog").assertDoesNotExist()
    }

    @Test fun a_failed_load_offers_to_try_again() = runComposeUiTest {
        var calls = 0
        setPlatformContent {
            PrivacyLabsPage(SystemSettingsActions(getTelemetry = { calls++; if (calls == 1) null else TelemetryState(enabled = true) }))
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Couldn't reach this machine.")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Try again").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("system_telemetry_switch").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, calls)
    }

    @Test fun without_lab_features_there_is_no_labs_section() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(caps = NO_CAPS)) { PrivacyLabsPage(SystemSettingsActions()) }
        onNodeWithText("LABS").assertDoesNotExist()
        onNodeWithTag("appearance_overlay_switch").assertDoesNotExist()
    }

    @Test fun an_offline_machine_shows_the_offline_line_instead_of_privacy_rows() = runComposeUiTest {
        val off = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline", lastSeen = "2h ago")
        setPlatformContent {
            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(off), "m2") {}) {
                PrivacyLabsPage(SystemSettingsActions(getTelemetry = { error("must not load for an offline machine") }))
            }
        }
        onNodeWithText("studio-mini is offline · last seen 2h ago.").assertExists()
        onNodeWithTag("system_telemetry_switch").assertDoesNotExist()
    }

    @Test fun the_overlay_turns_on_at_once_when_it_may_draw_and_asks_for_the_mic() = runComposeUiTest {
        val host = FakeOverlayHost(canDraw = true)
        val prefs = UiPrefs(FakeSettingsStore())
        setPlatformContent(platform = overlayPlatform(host), uiPrefs = prefs) { PrivacyLabsPage(SystemSettingsActions()) }
        waitForIdle()
        onNodeWithText("LABS").assertExists()
        onNodeWithText("on this device").assertExists()
        onNodeWithText("Experimental").assertExists()
        onNodeWithText("Your default Agent floats over other apps: tap it to chat, hold it to talk.").assertExists()
        onNode(anchored("assistant-overlay")).assertExists()
        onNodeWithTag("appearance_overlay_screen_switch").assertDoesNotExist()
        onNodeWithTag("appearance_overlay_switch").performScrollTo().performClick()
        waitForIdle()
        assertEquals(1, host.micAsks)
        assertTrue(runBlocking { prefs.assistantOverlay.first() })
        onNodeWithTag("appearance_overlay_screen_switch").assertExists()
        onNodeWithText("Let it see your screen").assertExists()
        onNodeWithText("Double-tap it to put its glasses on.").assertExists()
    }

    @Test fun the_overlay_walks_through_the_system_permission_first() = runComposeUiTest {
        val host = FakeOverlayHost(canDraw = false)
        val prefs = UiPrefs(FakeSettingsStore())
        setPlatformContent(platform = overlayPlatform(host), uiPrefs = prefs) { PrivacyLabsPage(SystemSettingsActions()) }
        waitForIdle()
        onNodeWithTag("appearance_overlay_switch").performScrollTo().performClick()
        waitForIdle()
        assertEquals(1, host.permissionPages)
        assertEquals(0, host.micAsks)
        onNodeWithText("Allow “Display over other apps” for supermux, then come back here.").assertExists()
        onNodeWithTag("appearance_overlay_allow").performClick()
        waitForIdle()
        assertEquals(2, host.permissionPages)
        // Back from the system page with it allowed: the poll turns the overlay on and asks for the mic.
        host.canDraw = true
        mainClock.advanceTimeBy(700)
        waitForIdle()
        assertEquals(1, host.micAsks)
        assertTrue(runBlocking { prefs.assistantOverlay.first() })
    }

    @Test fun the_platforms_own_overlay_settings_sit_under_the_switch_when_on() = runComposeUiTest {
        val host = object : AssistantOverlayHost {
            override fun canDrawOverOtherApps() = true
            override fun openPermissionPage() {}
            override suspend fun requestMic() = true
            override val moreSettings: (@androidx.compose.runtime.Composable () -> Unit) = {
                androidx.compose.material3.Text("Hold to talk from any app")
            }
        }
        val prefs = UiPrefs(FakeSettingsStore())
        runBlocking { prefs.putAssistantOverlay(true) }
        setPlatformContent(platform = overlayPlatform(host), uiPrefs = prefs) { PrivacyLabsPage(SystemSettingsActions()) }
        waitForIdle()
        onNodeWithText("Hold to talk from any app").assertExists()
    }
}
