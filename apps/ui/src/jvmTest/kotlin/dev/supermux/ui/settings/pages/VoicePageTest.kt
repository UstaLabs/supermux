package dev.supermux.ui.settings.pages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.supermux.net.AppConfigDto
import dev.supermux.net.BrokerApi
import dev.supermux.net.ModelInfo
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.SettingsKeys
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.DictationMode
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.FakeLiveTranscript
import dev.supermux.ui.platform.FakeMic
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.prefs.LocalUiPrefsOrNull
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.settings.GLOSSARY_SUB
import dev.supermux.ui.settings.SettingsHub
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.kit.KitAnchorKey
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Voice (#97 spec §2.2, §10.7, §11): speech recognition, languages, the word count that opens the word list, read
 * aloud with Preview, the tidy-up fold and, where this device has a recogniser, where speech becomes text. Each
 * control saves at once and a failed save puts it back. The word list itself is [GlossaryPageTest]'s.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoicePageTest {

    private fun anchored(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    private fun sampleConfig() = AppConfigDto(
        voiceSttEngine = "whisper",
        voiceTtsEngine = "codex",
        voiceCleanupEngine = "codex",
        voiceCleanupModel = "",
    )

    private fun actions(
        loadConfig: suspend () -> AppConfigDto? = { sampleConfig() },
        loadModels: suspend (String) -> List<ModelInfo> = { listOf(ModelInfo("gpt-5", "GPT-5"), ModelInfo("o3", "o3")) },
        saveVoiceStt: suspend (String?) -> Boolean = { true },
        saveVoiceTts: suspend (String?) -> Boolean = { true },
        saveVoiceLanguages: suspend (List<String>) -> Boolean = { true },
        saveVoiceCleanup: suspend (String?, String?) -> Boolean = { _, _ -> true },
        glossaryLoad: suspend () -> List<String>? = { listOf("Supermux", "BrokerApi") },
        glossarySave: suspend (List<String>) -> List<String>? = { it },
    ) = VoiceSettingsActions(
        loadConfig = loadConfig,
        loadModels = loadModels,
        saveVoiceStt = saveVoiceStt,
        saveVoiceTts = saveVoiceTts,
        saveVoiceLanguages = saveVoiceLanguages,
        saveVoiceCleanup = saveVoiceCleanup,
        glossaryLoad = glossaryLoad,
        glossarySave = glossarySave,
    )

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    // ── the rows ──────────────────────────────────────────────────────────────────────────────

    @Test fun the_rows_come_from_the_config() = runComposeUiTest {
        setPlatformContent { VoicePage(actions()) }
        awaitTag("voice_stt_row")
        onNodeWithTag("voice_settings_screen").assertExists()
        onNodeWithText("Speech recognition").assertExists()
        onNodeWithTag("voice_stt_chip_option_whisper").assertIsSelected()
        onNodeWithTag("voice_stt_chip_option_codex-realtime").assertIsNotSelected()
        for (label in listOf("ChatGPT", "Claude", "Cursor", "Whisper")) {
            assertTrue(onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty(), label)
        }
        onNodeWithText("Detect automatically").assertExists()
        onNodeWithText("Read replies aloud").assertExists()
        onNodeWithTag("voice_tts_row").assertExists()
        for (a in listOf("speech-engine", "languages", "glossary", "read-aloud")) onNode(anchored(a)).assertExists()
        // The tidy-up rows sit in the closed Advanced fold.
        onNodeWithTag("voice_cleanup_engine_chip").assertDoesNotExist()
        onNodeWithTag("voice_advanced").performClick()
        waitForIdle()
        onNodeWithText("Only used when speech is recognised on a phone or by Whisper; ChatGPT and Claude already return clean text.").assertExists()
        onNodeWithTag("voice_cleanup_engine_chip").assertExists()
        onNodeWithTag("voice_cleanup_model_chip").assertExists()
        onNode(anchored("tidy-up-engine")).assertExists()
        onNode(anchored("tidy-up-model")).assertExists()
    }

    @Test fun opened_for_a_tidy_up_row_the_fold_opens_itself() = runComposeUiTest {
        setPlatformContent { VoicePage(actions(), anchor = "tidy-up-model") }
        awaitTag("voice_cleanup_model_chip")
    }

    @Test fun the_word_count_comes_from_the_word_list() = runComposeUiTest {
        setPlatformContent { VoicePage(actions(glossaryLoad = { listOf("Supermux", "BrokerApi", "Kotlin") })) }
        awaitText("3 words")
        onNodeWithText("Words to spell your way").assertExists()
    }

    @Test fun the_config_and_the_word_list_load_together() = runComposeUiTest {
        val calls = CopyOnWriteArrayList<String>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        setPlatformContent {
            VoicePage(
                actions(
                    loadConfig = {
                        calls += "config"
                        release.await()
                        sampleConfig()
                    },
                    glossaryLoad = {
                        calls += "glossary"
                        listOf("Supermux")
                    },
                ),
            )
        }
        // The word list is asked while the config is still on its way.
        waitUntil(timeoutMillis = 5_000) { calls.containsAll(listOf("config", "glossary")) }
        release.complete(Unit)
        awaitText("1 word")
    }

    @Test fun the_word_list_row_opens_the_sub_page() = runComposeUiTest {
        var opened: String? = null
        setPlatformContent { VoicePage(actions(), onOpenSub = { opened = it }) }
        awaitTag("voice_glossary_link")
        onNodeWithTag("voice_glossary_link").performClick()
        assertEquals(GLOSSARY_SUB, opened)
    }

    @Test fun read_aloud_has_preview() = runComposeUiTest {
        setPlatformContent { VoicePage(actions()) }
        awaitTag("voice_tts_preview")
        onNodeWithText("Preview").assertExists()
    }

    @Test fun engine_label_helpers() {
        assertEquals("Whisper", sttEngineLabel("whisper"))
        assertEquals("ChatGPT", sttEngineLabel("codex-realtime"))
        assertEquals("Device's own voice", ttsEngineLabel("platform"))
        assertEquals("opencode", voiceEngineFamily("opencode-zen"))
        assertEquals("OpenCode Go", voiceEngineLabel("opencode-go"))
        assertEquals("English, Turkish", voiceLanguagesSummary(listOf("en", "tr")))
        assertEquals("Detect automatically", voiceLanguagesSummary(emptyList()))
        assertEquals("1 word", wordCount(1))
        assertEquals("0 words", wordCount(0))
    }

    // ── saved per control ─────────────────────────────────────────────────────────────────────

    @Test fun picking_a_speech_engine_saves_it() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        setPlatformContent { VoicePage(actions(saveVoiceStt = { saved.set(it); true })) }
        awaitTag("voice_stt_chip_option_claude-voice")
        onNodeWithTag("voice_stt_chip_option_claude-voice").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get() == "claude-voice" }
        onNodeWithTag("voice_stt_chip_option_claude-voice").assertIsSelected()
    }

    @Test fun a_failed_speech_engine_save_puts_it_back_and_says_so() = runComposeUiTest {
        setPlatformContent { VoicePage(actions(saveVoiceStt = { false })) }
        awaitTag("voice_stt_chip_option_claude-voice")
        onNodeWithTag("voice_stt_chip_option_claude-voice").performClick()
        awaitTag("voice_save_error")
        onNodeWithText("Couldn't save speech recognition.").assertExists()
        onNodeWithTag("voice_stt_chip_option_whisper").assertIsSelected()
    }

    @Test fun languages_toggle_one_by_one_in_a_sheet_and_none_detects() = runComposeUiTest {
        val saves = CopyOnWriteArrayList<List<String>>()
        setPlatformContent {
            VoicePage(
                actions(
                    loadConfig = { sampleConfig().copy(voiceLanguages = listOf("tr")) },
                    saveVoiceLanguages = { saves.add(it); true },
                ),
            )
        }
        awaitText("Turkish")
        onNodeWithTag("voice_languages_chip").performClick()
        awaitTag("voice_languages_sheet")
        onNodeWithTag("voice_languages_chip_option_en").performClick()
        waitUntil(timeoutMillis = 5_000) { saves.lastOrNull() == listOf("tr", "en") }
        onNodeWithTag("voice_languages_chip_option_auto").performClick()
        waitUntil(timeoutMillis = 5_000) { saves.lastOrNull() == emptyList<String>() }
        onNodeWithTag("voice_languages_done").performClick()
        waitForIdle()
        onNodeWithTag("voice_languages_sheet").assertDoesNotExist()
        onNodeWithText("Detect automatically").assertExists()
    }

    @Test fun picking_a_read_aloud_voice_saves_it() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        setPlatformContent { VoicePage(actions(saveVoiceTts = { saved.set(it); true })) }
        awaitTag("voice_tts_chip")
        onNodeWithTag("voice_tts_chip").performClick()
        waitForIdle()
        onNodeWithTag("voice_tts_chip_option_platform").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get() == "platform" }
        awaitText("Device's own voice")
    }

    @Test fun a_failed_read_aloud_save_puts_it_back() = runComposeUiTest {
        setPlatformContent { VoicePage(actions(saveVoiceTts = { false })) }
        awaitTag("voice_tts_chip")
        onNodeWithTag("voice_tts_chip").performClick()
        waitForIdle()
        onNodeWithTag("voice_tts_chip_option_platform").performClick()
        awaitTag("voice_save_error")
        onNodeWithTag("voice_tts_chip").assertTextContains("ChatGPT")
    }

    @Test fun another_tidy_up_engine_resets_the_model_and_loads_its_models() = runComposeUiTest {
        val saved = AtomicReference<Pair<String?, String?>?>(null)
        val families = CopyOnWriteArrayList<String>()
        setPlatformContent {
            VoicePage(
                actions(
                    loadModels = { family ->
                        families.add(family)
                        listOf(ModelInfo("m1", "Model 1"))
                    },
                    saveVoiceCleanup = { engine, model ->
                        saved.set(engine to model)
                        true
                    },
                ),
                anchor = "tidy-up-engine",
            )
        }
        awaitTag("voice_cleanup_engine_chip")
        onNodeWithTag("voice_cleanup_engine_chip").performClick()
        waitForIdle()
        onNodeWithTag("voice_cleanup_engine_chip_option_cursor").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get()?.first == "cursor" }
        assertEquals("cursor" to "", saved.get())
        waitUntil(timeoutMillis = 5_000) { families.contains("cursor") }
    }

    // ── load, offline ─────────────────────────────────────────────────────────────────────────

    @Test fun a_failed_load_offers_to_try_again() = runComposeUiTest {
        setPlatformContent { VoicePage(actions(loadConfig = { null })) }
        awaitTag("voice_settings_error")
        onNodeWithText("Couldn't reach this machine.").assertExists()
        onNodeWithTag("voice_settings_retry").assertExists()
        onNodeWithTag("voice_stt_row").assertDoesNotExist()
    }

    @Test fun an_offline_machine_shows_the_offline_line_and_loads_nothing() = runComposeUiTest {
        val off = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline", lastSeen = "2h ago")
        setPlatformContent {
            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(off), "m2") {}) {
                VoicePage(
                    actions(
                        loadConfig = { error("must not load for an offline machine") },
                        glossaryLoad = { error("must not load for an offline machine") },
                    ),
                )
            }
        }
        waitForIdle()
        onNodeWithText("studio-mini is offline · last seen 2h ago.").assertExists()
        onNodeWithTag("voice_stt_row").assertDoesNotExist()
    }

    // ── this device ───────────────────────────────────────────────────────────────────────────

    private fun transcribing() = FakePlatform().apply { mic = FakeMic(liveTranscript = FakeLiveTranscript()) }

    /** The page as a host draws it: with the device's prefs provided (the shell's `HostTheme` does), where the choice is kept. */
    private fun ComposeUiTest.transcribingPage(
        prefs: UiPrefs = UiPrefs(FakeSettingsStore()),
        phone: Boolean = false,
    ) = setPlatformContent(
        platform = transcribing(),
        pointer = !phone,
        widthClass = if (phone) WindowWidthClass.Compact else WindowWidthClass.Expanded,
        inputMode = if (phone) InputMode.Touch else InputMode.Pointer,
        uiPrefs = prefs,
    ) {
        CompositionLocalProvider(LocalUiPrefsOrNull provides prefs) { VoicePage(actions()) }
    }

    @Test fun with_a_live_transcriber_a_wide_window_says_this_device() = runComposeUiTest {
        transcribingPage()
        awaitTag("voice_mode_row")
        onNodeWithText("THIS DEVICE").assertExists()
        onNodeWithText("Turn speech into text").assertExists()
        onNodeWithText("On this device").assertExists()
        onNodeWithText("On this machine").assertExists()
        onNodeWithText("On this device works offline and starts faster.").assertExists()
        onNode(anchored("dictation")).assertExists()
    }

    @Test fun on_a_phone_it_says_this_phone() = runComposeUiTest {
        transcribingPage(phone = true)
        awaitTag("voice_mode_row")
        onNodeWithText("THIS PHONE").assertExists()
        onNodeWithText("On this phone").assertExists()
    }

    @Test fun where_speech_becomes_text_is_this_devices_choice() = runComposeUiTest {
        val store = FakeSettingsStore()
        transcribingPage(UiPrefs(store))
        awaitTag("voice_mode_chip_option_device")
        // Today's default: nothing chosen and no ChatGPT login on the machine → on this device.
        onNodeWithTag("voice_mode_chip_option_device").assertIsSelected()
        onNodeWithTag("voice_mode_chip_option_broker").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { store.map.value[SettingsKeys.DICTATION_MODE] == DictationMode.Broker.wire }
        waitForIdle()
        onNodeWithTag("voice_mode_chip_option_broker").assertIsSelected()
    }

    @Test fun without_a_live_transcriber_there_is_no_device_section() = runComposeUiTest {
        setPlatformContent { VoicePage(actions()) }
        awaitTag("voice_stt_row")
        onNodeWithText("THIS DEVICE").assertDoesNotExist()
        onNodeWithTag("voice_mode_row").assertDoesNotExist()
    }

    // ── against a real HostStore ──────────────────────────────────────────────────────────────

    private fun appForVoice(configPutOk: Boolean = true): Pair<HostStore, CopyOnWriteArrayList<Pair<HttpMethod, String>>> {
        val methods = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            methods.add(req.method to path)
            when {
                path == "/settings/config" && req.method == HttpMethod.Get ->
                    respond("""{"voiceSttEngine":"whisper","voiceTtsEngine":"platform","voiceCleanupEngine":"codex"}""", HttpStatusCode.OK, json)
                path == "/settings/config" && req.method == HttpMethod.Put ->
                    respond("{}", if (configPutOk) HttpStatusCode.OK else HttpStatusCode.InternalServerError, json)
                path == "/config/voice-glossary" -> respond("""{"glossary":["Supermux"]}""", HttpStatusCode.OK, json)
                path.startsWith("/models") -> respond("""{"models":[{"id":"m1","displayName":"Model 1"}]}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }
        val client = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = HostStoreDeps(httpFactory = { client }, settings = FakeSettingsStore(), clock = FixedClock()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return app to methods
    }

    @Test fun a_host_store_loads_the_config_and_the_word_list() = runComposeUiTest {
        val (app, methods) = appForVoice()
        setPlatformContent { VoicePage(rememberVoiceSettingsActions(app)) }
        awaitText("1 word")
        assertTrue(methods.any { it.first == HttpMethod.Get && it.second == "/settings/config" })
        assertTrue(methods.any { it.first == HttpMethod.Get && it.second == "/config/voice-glossary" })
    }

    @Test fun a_host_store_save_failing_with_500_puts_the_engine_back() = runComposeUiTest {
        val (app, methods) = appForVoice(configPutOk = false)
        setPlatformContent { VoicePage(rememberVoiceSettingsActions(app)) }
        awaitTag("voice_stt_chip_option_claude-voice")
        onNodeWithTag("voice_stt_chip_option_claude-voice").performClick()
        waitUntil(timeoutMillis = 5_000) { methods.any { it.first == HttpMethod.Put && it.second == "/settings/config" } }
        awaitTag("voice_save_error")
        onNodeWithTag("voice_stt_chip_option_whisper").assertIsSelected()
    }

    // ── inside the hub ────────────────────────────────────────────────────────────────────────

    /**
     * The system back GESTURE on a phone, inside the hub: the word list is the hub's sub-page, so the first gesture
     * returns to Voice, the second to the index, the third closes Settings.
     */
    @OptIn(InternalComposeUiApi::class)
    @Test fun the_back_gesture_leaves_the_word_list_before_the_page() = runComposeUiTest {
        val input = VoiceTestBackInput()
        val dispatcher = NavigationEventDispatcher()
        dispatcher.addInput(input)
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        var closed = 0
        val voice = actions()
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                SettingsHub(section = SettingsSection.Voice, onSectionChange = {}, onBack = { closed++ }) { place, scope ->
                    if (place.sub == GLOSSARY_SUB) {
                        GlossaryPage(voice, onBack = scope.onClose)
                    } else {
                        VoicePage(voice, onOpenSub = { scope.onOpen(SettingsPlace(SettingsSection.Voice, it)) })
                    }
                }
            }
        }
        awaitTag("voice_glossary_link")
        onNodeWithTag("voice_glossary_link").performClick()
        awaitTag("voice_glossary_page")

        // 1st gesture: the word list, not the page.
        runOnIdle { input.back() }
        waitForIdle()
        onNodeWithTag("voice_glossary_page").assertDoesNotExist()
        onNodeWithTag("voice_settings_screen").assertExists()
        onNodeWithTag("settings_index").assertDoesNotExist()
        assertEquals(0, closed)

        // 2nd: the page → the index. 3rd: Settings closes.
        runOnIdle { input.back() }
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
        assertEquals(0, closed)
        runOnIdle { input.back() }
        waitForIdle()
        assertEquals(1, closed)
    }
}

/** A whole back gesture; a real predictive-back sequence ends with `completed`. */
private class VoiceTestBackInput : NavigationEventInput() {
    fun back() {
        dispatchOnBackStarted(NavigationEvent())
        dispatchOnBackProgressed(NavigationEvent(progress = 1f))
        dispatchOnBackCompleted()
    }
}
