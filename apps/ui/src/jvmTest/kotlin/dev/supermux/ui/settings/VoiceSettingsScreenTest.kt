package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.backhandler.LocalCompatNavigationEventDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import dev.supermux.net.AppConfigDto
import dev.supermux.net.BrokerApi
import dev.supermux.net.ModelInfo
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The shared [VoiceSettingsScreen] (cluster E5) — desktop's suite, moved by name.
 *
 * Covers the engine rows, the load Error+Retry, the save failures that revert their chip, and a
 * glossary whose failed load is never an empty list — against fakes and against a real `HostStore`
 * over a mocked `BrokerApi`. New here: the Compact branch Android contributed, where the glossary
 * is a pushed sub-page with its own Back (button AND system gesture, the latter driven through the
 * hub so the ordering against the hub's own `BackHandler` is real). The `AppShell` hub wiring stays
 * in `:desktop` (`VoiceSettingsHubTest`).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoiceSettingsScreenTest {

    private fun ComposeUiTest.voiceContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(
        platform = FakePlatform(),
        pointer = pointer,
        widthClass = widthClass,
        inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
    ) {
        content()
    }

    private fun uiTestDeps(client: HttpClient) = HostStoreDeps(
        httpFactory = { client },
        settings = FakeSettingsStore(),
        clock = FixedClock(),
    )

    private fun sampleConfig() = AppConfigDto(
        voiceSttEngine = "whisper",
        voiceTtsEngine = "codex",
        voiceCleanupEngine = "codex",
        voiceCleanupModel = "",
    )

    private fun actions(
        loadConfig: suspend () -> AppConfigDto? = { sampleConfig() },
        loadModels: suspend (String) -> List<ModelInfo> = {
            listOf(ModelInfo("gpt-5", "GPT-5"), ModelInfo("o3", "o3"))
        },
        saveVoiceStt: suspend (String?) -> Boolean = { true },
        saveVoiceTts: suspend (String?) -> Boolean = { true },
        saveVoiceCleanup: suspend (String?, String?) -> Boolean = { _, _ -> true },
        glossaryLoad: suspend () -> List<String>? = { listOf("Supermux", "BrokerApi") },
        glossarySave: suspend (List<String>) -> List<String>? = { it },
    ) = VoiceSettingsActions(
        loadConfig = loadConfig,
        loadModels = loadModels,
        saveVoiceStt = saveVoiceStt,
        saveVoiceTts = saveVoiceTts,
        saveVoiceCleanup = saveVoiceCleanup,
        glossaryLoad = glossaryLoad,
        glossarySave = glossarySave,
    )

    private fun screen(
        loadConfig: suspend () -> AppConfigDto? = { sampleConfig() },
        loadModels: suspend (String) -> List<ModelInfo> = {
            listOf(ModelInfo("gpt-5", "GPT-5"), ModelInfo("o3", "o3"))
        },
        saveVoiceStt: suspend (String?) -> Boolean = { true },
        saveVoiceTts: suspend (String?) -> Boolean = { true },
        saveVoiceCleanup: suspend (String?, String?) -> Boolean = { _, _ -> true },
        glossaryLoad: suspend () -> List<String>? = { listOf("Supermux", "BrokerApi") },
        glossarySave: suspend (List<String>) -> List<String>? = { it },
    ) = @Composable {
        VoiceSettingsScreen(
            actions = actions(
                loadConfig, loadModels, saveVoiceStt, saveVoiceTts, saveVoiceCleanup,
                glossaryLoad, glossarySave,
            ),
        )
    }

    @Test fun voice_renders_engine_rows_from_config() = runComposeUiTest {
        voiceContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_settings_content").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_settings_screen").assertIsDisplayed()
        onNodeWithTag("voice_stt_row").assertIsDisplayed()
        onNodeWithTag("voice_tts_row").assertIsDisplayed()
        onNodeWithTag("voice_cleanup_engine_row").assertIsDisplayed()
        onNodeWithTag("voice_cleanup_model_row").assertIsDisplayed()
        onNodeWithTag("voice_glossary_link").assertIsDisplayed()
        onNodeWithText("Whisper (local)").assertIsDisplayed()
        onNodeWithText("ChatGPT (Codex login)").assertIsDisplayed()
    }

    @Test fun load_failure_shows_error_with_retry() = runComposeUiTest {
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(loadConfig = { null })()
            }
        }
        waitForIdle()
        onNodeWithTag("voice_settings_error").assertIsDisplayed()
        onNodeWithTag("voice_settings_retry").assertIsDisplayed()
        onNodeWithText("Couldn't load voice settings.").assertIsDisplayed()
        onNodeWithTag("voice_settings_content").assertDoesNotExist()
    }

    @Test fun picking_stt_engine_persists() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(saveVoiceStt = {
                    saved.set(it)
                    true
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_stt_chip").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_stt_chip").performClick()
        waitForIdle()
        onNodeWithTag("voice_stt_chip_option_claude-voice").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get() == "claude-voice" }
        assertEquals("claude-voice", saved.get())
    }

    @Test fun picking_stt_engine_failure_reverts_and_shows_error() = runComposeUiTest {
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(saveVoiceStt = { false })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_stt_chip").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_stt_chip").performClick()
        waitForIdle()
        onNodeWithTag("voice_stt_chip_option_claude-voice").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_save_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // Reverted to original whisper from sampleConfig.
        onNodeWithText("Whisper (local)").assertIsDisplayed()
    }

    @Test fun picking_tts_engine_persists() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(saveVoiceTts = {
                    saved.set(it)
                    true
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_tts_chip").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_tts_chip").performClick()
        waitForIdle()
        onNodeWithTag("voice_tts_chip_option_platform").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get() == "platform" }
        assertEquals("platform", saved.get())
    }

    @Test fun picking_tts_engine_failure_reverts() = runComposeUiTest {
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(saveVoiceTts = { false })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_tts_chip").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_tts_chip").performClick()
        waitForIdle()
        onNodeWithTag("voice_tts_chip_option_platform").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_save_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("ChatGPT (Codex login)").assertIsDisplayed()
    }

    @Test fun cleanup_engine_switch_resets_model_and_reloads() = runComposeUiTest {
        val saved = AtomicReference<Pair<String?, String?>?>(null)
        val families = CopyOnWriteArrayList<String>()
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    loadModels = { family ->
                        families.add(family)
                        listOf(ModelInfo("m1", "Model 1"))
                    },
                    saveVoiceCleanup = { engine, model ->
                        saved.set(engine to model)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_cleanup_engine_chip").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_cleanup_engine_chip").performClick()
        waitForIdle()
        onNodeWithTag("voice_cleanup_engine_chip_option_cursor").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get()?.first == "cursor" }
        assertEquals("cursor" to "", saved.get())
        assertTrue(families.contains("cursor"))
    }

    @Test fun glossary_add_and_remove_persist() = runComposeUiTest {
        val terms = AtomicReference(listOf("Supermux"))
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    glossaryLoad = { terms.get() },
                    glossarySave = {
                        terms.set(it)
                        it
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_link").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        onNodeWithTag("voice_glossary_screen").assertIsDisplayed()
        onNodeWithTag("voice_glossary_term_Supermux").assertIsDisplayed()
        onNodeWithTag("voice_glossary_input").performTextInput("BrokerApi")
        onNodeWithTag("voice_glossary_add").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_term_BrokerApi").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(terms.get().contains("BrokerApi"))
        onNodeWithTag("voice_glossary_remove_Supermux").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_term_Supermux").assertDoesNotExist()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(!terms.get().contains("Supermux"))
    }

    @Test fun glossary_save_failure_reverts() = runComposeUiTest {
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    glossaryLoad = { listOf("Keep") },
                    glossarySave = { null },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        onNodeWithTag("voice_glossary_term_Keep").assertIsDisplayed()
        onNodeWithTag("voice_glossary_input").performTextInput("Temp")
        onNodeWithTag("voice_glossary_add").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_error").assertIsDisplayed()
                onNodeWithTag("voice_glossary_term_Keep").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Couldn't save — reverted").assertIsDisplayed()
        onNodeWithTag("voice_glossary_term_Temp").assertDoesNotExist()
    }

    @Test fun glossary_load_failure_shows_error_not_empty() = runComposeUiTest {
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(glossaryLoad = { null })()
            }
        }
        waitForIdle()
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_load_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_glossary_retry").assertIsDisplayed()
        onNodeWithTag("voice_glossary_empty").assertDoesNotExist()
    }

    @Test fun glossary_empty_shows_empty_not_error() = runComposeUiTest {
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(glossaryLoad = { emptyList() })()
            }
        }
        waitForIdle()
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_empty").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_glossary_load_error").assertDoesNotExist()
    }

    @Test fun engine_label_helpers() {
        assertEquals("Whisper (local)", sttEngineLabel("whisper"))
        assertEquals("Device (system voice)", ttsEngineLabel("platform"))
        assertEquals("opencode", voiceEngineFamily("opencode-zen"))
        assertEquals("OpenCode Go", voiceEngineLabel("opencode-go"))
    }

    // ── HostStore + BrokerApi ─────────────────────────────────────────────────────────────

    private fun appForVoice(
        configJson: String? = """{"voiceSttEngine":"whisper","voiceTtsEngine":"platform","voiceCleanupEngine":"codex"}""",
        glossaryJson: String? = """{"glossary":["Supermux"]}""",
        configPutOk: Boolean = true,
        glossaryGetStatus: HttpStatusCode = HttpStatusCode.OK,
        glossaryPutStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<HostStore, CopyOnWriteArrayList<Pair<HttpMethod, String>>> {
        val methods = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            methods.add(req.method to path)
            when {
                path == "/settings/config" && req.method == HttpMethod.Get -> {
                    if (configJson == null) {
                        respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    } else {
                        respond(configJson, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/settings/config" && req.method == HttpMethod.Put ->
                    respond(
                        "{}",
                        if (configPutOk) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                        jsonHeaders,
                    )
                path == "/config/voice-glossary" && req.method == HttpMethod.Get -> {
                    if (glossaryJson == null) {
                        respond("{}", glossaryGetStatus, jsonHeaders)
                    } else {
                        respond(glossaryJson, glossaryGetStatus, jsonHeaders)
                    }
                }
                path == "/config/voice-glossary" && req.method == HttpMethod.Put ->
                    respond(glossaryJson ?: """{"glossary":[]}""", glossaryPutStatus, jsonHeaders)
                path.startsWith("/models") ->
                    respond("""{"models":[{"id":"m1","displayName":"Model 1"}]}""", HttpStatusCode.OK, jsonHeaders)
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = uiTestDeps(client),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return app to methods
    }

    @Test fun desktop_app_state_voice_config_and_glossary() = runComposeUiTest {
        val (app, methods) = appForVoice()
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(actions = rememberVoiceSettingsActions(app))
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_settings_content").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(methods.any { it.first == HttpMethod.Get && it.second == "/settings/config" })
        onNodeWithTag("voice_glossary_link").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_term_Supermux").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(methods.any { it.second == "/config/voice-glossary" })
    }

    /** B2: glossary GET 500 → Error, never empty that would overwrite. */
    @Test fun desktop_app_state_glossary_null_on_broker_error() = runComposeUiTest {
        val (app, _) = appForVoice(
            glossaryJson = null,
            glossaryGetStatus = HttpStatusCode.InternalServerError,
        )
        var result: List<String>? = emptyList()
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(
                    actions = actions(
                        loadConfig = { app.appConfig() },
                        loadModels = { app.launcherModels(it) },
                        saveVoiceStt = { app.saveVoiceStt(it) },
                        saveVoiceTts = { app.saveVoiceTts(it) },
                        saveVoiceCleanup = { e, m -> app.saveVoiceCleanup(e, m) },
                        glossaryLoad = {
                            result = app.fetchGlossary()
                            result
                        },
                        glossarySave = { app.updateGlossary(it) },
                    ),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("voice_glossary_link").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_load_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertNull(result)
        onNodeWithTag("voice_glossary_empty").assertDoesNotExist()
    }

    @Test fun desktop_app_state_voice_stt_save_false_on_http_500() = runComposeUiTest {
        val (app, methods) = appForVoice(configPutOk = false)
        var ok = true
        voiceContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(
                    actions = actions(
                        loadConfig = { app.appConfig() },
                        loadModels = { app.launcherModels(it) },
                        saveVoiceStt = {
                            ok = app.saveVoiceStt(it)
                            ok
                        },
                        saveVoiceTts = { app.saveVoiceTts(it) },
                        saveVoiceCleanup = { e, m -> app.saveVoiceCleanup(e, m) },
                        glossaryLoad = { app.fetchGlossary() },
                        glossarySave = { app.updateGlossary(it) },
                    ),
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_stt_chip").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_stt_chip").performClick()
        waitForIdle()
        onNodeWithTag("voice_stt_chip_option_claude-voice").performClick()
        waitUntil(timeoutMillis = 5_000) {
            methods.any { it.first == HttpMethod.Put && it.second == "/settings/config" } && !ok
        }
        onNodeWithTag("voice_save_error").assertIsDisplayed()
    }

    // ── Compact / touch (Android's branch) ──────────────────────────────────────────────────────

    /** A roomy window keeps desktop's inline expand: the glossary appears BESIDE the rows. */
    @Test fun a_wide_window_expands_the_glossary_in_place() = runComposeUiTest {
        voiceContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_link").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        onNodeWithTag("voice_glossary_screen").assertIsDisplayed()
        // Still the same page — the rows did not go anywhere, and there is no sub-page Back.
        onNodeWithTag("voice_settings_content").assertIsDisplayed()
        onNodeWithTag("voice_stt_row").assertIsDisplayed()
        onNodeWithTag("voice_glossary_back").assertDoesNotExist()
        // Clicking again collapses it.
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        onNodeWithTag("voice_glossary_screen").assertDoesNotExist()
    }

    /** The phone pushes the glossary as a sub-page, and its own Back returns to the voice page. */
    @Test fun compact_pushes_the_glossary_and_its_back_returns() = runComposeUiTest {
        var backs = 0
        voiceContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(
                    actions = actions(),
                    onBack = { backs++ },
                    topBarShown = false,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_link").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_settings_back").assertIsDisplayed()
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        // A real push: the voice rows are gone and the bar is the glossary's.
        onNodeWithTag("voice_glossary_screen").assertIsDisplayed()
        onNodeWithTag("voice_settings_content").assertDoesNotExist()
        onNodeWithTag("voice_settings_back").assertDoesNotExist()
        onNodeWithTag("voice_glossary_back").performClick()
        waitForIdle()
        onNodeWithTag("voice_settings_content").assertIsDisplayed()
        onNodeWithTag("voice_glossary_screen").assertDoesNotExist()
        // Back on the voice page itself leaves the screen — the sub-page never swallowed it.
        assertEquals(0, backs)
        onNodeWithTag("voice_settings_back").performClick()
        assertEquals(1, backs)
    }

    /**
     * The system back GESTURE, inside the hub: the sub-page's `BackHandler` composes below the
     * hub's, so the first gesture pops the glossary, the second pops the hub's detail — the hub
     * needs no hook for a section with a stack of its own.
     */
    @OptIn(InternalComposeUiApi::class)
    @Test fun the_back_gesture_pops_the_glossary_before_the_hub_detail() = runComposeUiTest {
        val input = VoiceTestBackInput()
        val dispatcher = NavigationEventDispatcher()
        dispatcher.addInput(input)
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        var closed = 0
        voiceContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            CompositionLocalProvider(LocalCompatNavigationEventDispatcherOwner provides owner) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    SettingsHub(
                        section = SettingsSection.Voice,
                        onSectionChange = {},
                        onBack = { closed++ },
                    ) { _, scope ->
                        VoiceSettingsScreen(
                            actions = actions(),
                            onBack = scope.onClose,
                            topBarShown = scope.topBarShown,
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("settings_row_voice").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_link").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        onNodeWithTag("voice_glossary_screen").assertIsDisplayed()

        // 1st gesture: the glossary sub-page, NOT the hub's detail.
        runOnIdle { input.back() }
        waitForIdle()
        onNodeWithTag("voice_settings_content").assertIsDisplayed()
        onNodeWithTag("voice_glossary_screen").assertDoesNotExist()
        onNodeWithTag("settings_index").assertDoesNotExist()
        assertEquals(0, closed)

        // 2nd: the hub's detail → the index. 3rd: the hub closes.
        runOnIdle { input.back() }
        waitForIdle()
        onNodeWithTag("settings_index").assertIsDisplayed()
        assertEquals(0, closed)
        runOnIdle { input.back() }
        waitForIdle()
        assertEquals(1, closed)
    }

    /**
     * `standalone` is the E4 rule: a screen that is its own destination needs a title and Back at
     * EVERY width — a landscape phone is Medium, not Compact — and its glossary still expands in
     * place there, because only Compact pushes.
     */
    @Test fun a_standalone_screen_keeps_its_chrome_above_compact() = runComposeUiTest {
        var backs = 0
        voiceContent(pointer = false, widthClass = WindowWidthClass.Medium) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(
                    actions = actions(),
                    onBack = { backs++ },
                    topBarShown = false,
                    standalone = true,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_link").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        onNodeWithTag("voice_settings_content").assertIsDisplayed()
        onNodeWithTag("voice_glossary_screen").assertIsDisplayed()
        onNodeWithTag("voice_settings_back").performClick()
        assertEquals(1, backs)
    }

    /** Inside the hub on a wide window there is exactly one chrome, and it is not the screen's. */
    @Test fun a_hub_section_on_a_wide_window_paints_no_bar_of_its_own() = runComposeUiTest {
        voiceContent(widthClass = WindowWidthClass.Expanded) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(actions = actions(), topBarShown = false)
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_settings_content").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_settings_back").assertDoesNotExist()
    }

    /** Hit targets key on `LocalPointerAvailable`: the value chips reach Material's 48dp. */
    @Test fun touch_chips_reach_the_minimum_target() {
        fun chipHeight(pointer: Boolean): Pair<Int, Int> {
            var measured = 0 to 0
            runComposeUiTest {
                voiceContent(pointer = pointer, widthClass = WindowWidthClass.Compact) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) {
                        VoiceSettingsScreen(actions = actions(), topBarShown = true)
                    }
                }
                waitForIdle()
                waitUntil(timeoutMillis = 5_000) {
                    try {
                        onNodeWithTag("voice_stt_chip").assertIsDisplayed()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                val h = onNodeWithTag("voice_stt_chip").fetchSemanticsNode().size.height
                measured = h to with(density) { 48.dp.roundToPx() }
            }
            return measured
        }
        val (touch, minTarget) = chipHeight(pointer = false)
        val (mouse, _) = chipHeight(pointer = true)
        assertTrue(touch >= minTarget, "touch chip $touch should reach the ${minTarget}px minimum")
        assertTrue(touch > mouse, "touch chip $touch should exceed pointer chip $mouse")
    }

    /** Swipe-to-delete is Android's, and it is a finger affordance: no pointer, no swipe box. */
    @Test fun glossary_rows_swipe_to_delete_only_without_a_pointer() = runComposeUiTest {
        val saved = AtomicReference(listOf("Supermux", "BrokerApi"))
        voiceContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(
                    actions = actions(
                        glossaryLoad = { saved.get() },
                        glossarySave = {
                            saved.set(it)
                            it
                        },
                    ),
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_link").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        onNodeWithTag("voice_glossary_swipe_Supermux").assertIsDisplayed()
        // Someone else owns the chrome here, so the sub-page carries Back in its own body.
        onNodeWithTag("voice_glossary_back").assertIsDisplayed()

        // A real swipe, not just the presence of the box: the gesture itself has to delete.
        onNodeWithTag("voice_glossary_swipe_Supermux").performTouchInput { swipeLeft() }
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_term_Supermux").assertDoesNotExist()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(listOf("BrokerApi"), saved.get())

        // The Remove button survives for TalkBack, and still removes.
        onNodeWithTag("voice_glossary_remove_BrokerApi").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_empty").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(emptyList<String>(), saved.get())
    }

    /**
     * The pushed sub-page is the only scroll container on a phone: a glossary longer than the
     * screen must still reach its last row (Android's page was a `LazyColumn`).
     */
    @Test fun a_long_pushed_glossary_scrolls_to_its_last_row() = runComposeUiTest {
        val many = (1..40).map { "Term$it" }
        val saved = AtomicReference(many)
        voiceContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(
                    actions = actions(
                        glossaryLoad = { saved.get() },
                        glossarySave = {
                            saved.set(it)
                            it
                        },
                    ),
                    topBarShown = false,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_link").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        onNodeWithTag("voice_glossary_page").assertIsDisplayed()
        // Off-screen before the scroll, reachable after it — and still operable once there.
        onNodeWithTag("voice_glossary_term_Term40").assertIsNotDisplayed()
        onNodeWithTag("voice_glossary_term_Term40").performScrollTo().assertIsDisplayed()
        onNodeWithTag("voice_glossary_remove_Term40").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_term_Term40").assertDoesNotExist()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(!saved.get().contains("Term40"))
    }

    @Test fun glossary_rows_have_no_swipe_box_with_a_pointer() = runComposeUiTest {
        voiceContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_glossary_link").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("voice_glossary_link").performClick()
        waitForIdle()
        onNodeWithTag("voice_glossary_term_Supermux").assertIsDisplayed()
        onNodeWithTag("voice_glossary_swipe_Supermux").assertDoesNotExist()
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
