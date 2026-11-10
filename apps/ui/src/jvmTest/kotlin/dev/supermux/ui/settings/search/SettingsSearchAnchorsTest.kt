package dev.supermux.ui.settings.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AppConfigDto
import dev.supermux.net.CuratorConfig
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.ForgeAccount
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.net.LspServer
import dev.supermux.net.ModelInfo
import dev.supermux.net.TelemetryState
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.AssistantOverlayHost
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.FakeLiveTranscript
import dev.supermux.ui.platform.FakeMic
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.LocalUiPrefsOrNull
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.CodeIntelligenceActions
import dev.supermux.ui.settings.GitHostingActions
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.SystemSettingsActions
import dev.supermux.ui.settings.kit.KitAnchorKey
import dev.supermux.ui.settings.pages.CodingPage
import dev.supermux.ui.settings.pages.CuratorSettingsActions
import dev.supermux.ui.settings.pages.GeneralPage
import dev.supermux.ui.settings.pages.MachinePage
import dev.supermux.ui.settings.pages.ThisComputerHosting
import dev.supermux.ui.settings.HostingActions
import dev.supermux.ui.settings.HostingPowerUi
import dev.supermux.ui.settings.HostingUiState
import dev.supermux.ui.settings.KeepAwakeUi
import dev.supermux.net.UpdateStatus
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.settings.pages.KeyboardShortcutsPage
import dev.supermux.ui.settings.pages.MemoryPage
import dev.supermux.ui.settings.pages.PrivacyLabsPage
import dev.supermux.ui.settings.pages.ProvidersListHarness
import dev.supermux.ui.settings.pages.SizedStore
import dev.supermux.ui.settings.pages.VoicePage
import dev.supermux.ui.settings.pages.VoiceSettingsActions
import dev.supermux.ui.settings.pages.cacheOf
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every search result lands (#97 Task 18): for each device below, every entry with an anchor that the device shows
 * opens its page, composed over fakes with representative data, and the page carries that anchor.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSearchAnchorsTest {

    private class Overlay : AssistantOverlayHost {
        override fun canDrawOverOtherApps() = true
        override fun openPermissionPage() {}
        override suspend fun requestMic() = true
    }

    private class Device(
        val name: String,
        val caps: Caps,
        val phone: Boolean,
        val liveTranscript: Boolean,
        val hardwareKeyboard: Boolean,
    ) {
        val ctx = SettingsSearchContext(caps, phone = phone, liveTranscript = liveTranscript, hardwareKeyboard = hardwareKeyboard, mediaCache = true)

        fun platform(): FakePlatform = object : FakePlatform(caps = caps) {
            override val assistantOverlay: AssistantOverlayHost = Overlay()
        }.also { if (liveTranscript) it.mic = FakeMic(liveTranscript = FakeLiveTranscript()) }
    }

    /** Desktop: a pointer, an Expanded window, a keyboard, hosting and the overlay. */
    private val desktop = Device(
        "desktop", NO_CAPS.copy(localBroker = true, assistantOverlay = true, appearanceControls = true),
        phone = false, liveTranscript = false, hardwareKeyboard = true,
    )

    /** An Android phone: touch, Compact, a live transcriber, no keyboard. */
    private val android = Device(
        "android", NO_CAPS.copy(assistantOverlay = true, appearanceControls = true),
        phone = true, liveTranscript = true, hardwareKeyboard = false,
    )

    // ── fakes with representative data ────────────────────────────────────────────────────────

    private val agents = AgentSettingsActions(
        agentStatuses = {
            listOf("claude", "codex", "opencode", "cursor", "grok", "antigravity").map { AgentInstallStatus(kind = it, installed = true, authed = true) }
        },
    )
    private val voice = VoiceSettingsActions(
        loadConfig = { AppConfigDto(voiceSttEngine = "whisper", voiceTtsEngine = "codex", voiceCleanupEngine = "codex", voiceCleanupModel = "") },
        loadModels = { listOf(ModelInfo("gpt-5", "GPT-5")) },
        glossaryLoad = { listOf("Supermux") },
    )
    private val forges = GitHostingActions(
        forgesLoad = {
            ForgeConnectionsResponse(
                connections = listOf(ForgeConnection(id = "c1", kind = "github", host = "github.com", account = ForgeAccount(login = "alice"))),
            )
        },
    )
    private val lsp = CodeIntelligenceActions(
        lspLoad = {
            listOf(
                LspServer(id = "typescript", label = "TypeScript", extensions = listOf(".ts"), enabled = true, state = "ready"),
                LspServer(id = "kotlin", label = "Kotlin", extensions = listOf(".kt"), enabled = true, state = "ready"),
            )
        },
    )
    private val curator = CuratorSettingsActions(
        curatorLoad = { CuratorSettingsResponse(CuratorConfig(true, 2, 30, "claude", null, null), "2026-08-05T02:30:00Z") },
    )
    private val system = SystemSettingsActions(getTelemetry = { TelemetryState(enabled = true) })

    private val hosting = ThisComputerHosting(
        HostingUiState(
            hosting = true, statusDot = "", statusText = "", readOnly = false, localUrl = "http://192.168.1.24:9898",
            relayUrl = "https://h.relay.supermux.dev", relay = true, background = true, sessions = 0, logTail = emptyList(),
            power = HostingPowerUi(keepAwake = KeepAwakeUi(enabled = true, onBattery = false)),
        ),
        object : HostingActions {
            override fun setHosting(on: Boolean) {}
            override fun setBackground(on: Boolean) {}
            override fun setRelay(on: Boolean) {}
            override fun restart() {}
            override fun retry() {}
            override fun showLog() {}
            override fun pairDevice() {}
            override fun manageIt() {}
            override suspend fun installGit() = false
        },
    )

    /** The page for [place], as the hub's slot would draw it. */
    @Composable
    private fun Page(place: SettingsPlace) {
        val a = place.anchor
        when (place.section) {
            SettingsSection.General -> GeneralPage(anchor = a, cache = cacheOf(SizedStore(1)))
            SettingsSection.KeyboardShortcuts -> KeyboardShortcutsPage(anchor = a)
            SettingsSection.Providers -> ProvidersListHarness(agents, anchor = a)
            SettingsSection.Voice -> VoicePage(voice, anchor = a)
            SettingsSection.Coding -> CodingPage(forges = forges, lsp = lsp, anchor = a)
            SettingsSection.Memory -> MemoryPage(curator, anchor = a)
            SettingsSection.PrivacyLabs -> PrivacyLabsPage(system, anchor = a)
            SettingsSection.Machine -> MachinePage(
                SystemSettingsActions(updateStatus = { UpdateStatus(current = "0.17.5", mode = "binary") }),
                hosting = hosting.takeIf { LocalPlatform.current.caps.localBroker },
                anchor = a,
            )
        }
    }

    private fun ComposeUiTest.everyAnchorLands(d: Device) {
        val entries = SettingsSearchIndex.filter { it.anchor != null && it.shownWhen(d.ctx) }
        assertTrue(entries.isNotEmpty())
        var current by mutableStateOf(entries.first())
        setPlatformContent(
            platform = d.platform(),
            pointer = !d.phone,
            widthClass = if (d.phone) WindowWidthClass.Compact else WindowWidthClass.Expanded,
            inputMode = if (d.phone) InputMode.Touch else InputMode.Pointer,
        ) {
            // The device's prefs, as the host's theme provides them (Voice keeps where speech becomes text there).
            CompositionLocalProvider(LocalHardwareKeyboard provides d.hardwareKeyboard, LocalUiPrefsOrNull provides LocalUiPrefs.current) {
                key(current) { Page(SettingsPlace(current.section, anchor = current.anchor)) }
            }
        }
        val missing = mutableListOf<String>()
        for (e in entries) {
            current = e
            waitForIdle()
            val matcher = SemanticsMatcher.expectValue(KitAnchorKey, e.anchor!!)
            val found = runCatching {
                waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
            }.isSuccess
            if (!found) missing += "${e.section}/${e.anchor}"
        }
        if (missing.isNotEmpty()) fail("${d.name}: these search results land on no row: $missing")
    }

    @Test fun every_anchor_lands_on_the_desktop() = runComposeUiTest { everyAnchorLands(desktop) }

    @Test fun every_anchor_lands_on_an_android_phone() = runComposeUiTest { everyAnchorLands(android) }
}
