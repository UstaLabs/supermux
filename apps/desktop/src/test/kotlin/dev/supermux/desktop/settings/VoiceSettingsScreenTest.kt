// Desktop-parity Task 5: Voice settings (STT/TTS/cleanup) + dictation glossary.
package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.shell.SettingsSection
import dev.supermux.desktop.shell.AppShell
import dev.supermux.desktop.shell.ShellStateStore
import dev.supermux.desktop.shell.ShellUiState
import dev.supermux.net.AppConfigDto
import dev.supermux.net.BrokerApi
import dev.supermux.net.ModelInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoiceSettingsScreenTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { p -> runCatching { Files.deleteIfExists(p) } }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("voice_settings_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    private fun sampleConfig() = AppConfigDto(
        voiceSttEngine = "whisper",
        voiceTtsEngine = "codex",
        voiceCleanupEngine = "codex",
        voiceCleanupModel = "",
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
            loadConfig = loadConfig,
            loadModels = loadModels,
            saveVoiceStt = saveVoiceStt,
            saveVoiceTts = saveVoiceTts,
            saveVoiceCleanup = saveVoiceCleanup,
            glossaryLoad = glossaryLoad,
            glossarySave = glossarySave,
        )
    }

    @Test fun voice_renders_engine_rows_from_config() = runComposeUiTest {
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
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
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(saveVoiceTts = { false })()
            }
        }
        waitForIdle()
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
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
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

    // ── DesktopAppState + BrokerApi ─────────────────────────────────────────────────────────────

    private fun appForVoice(
        configJson: String? = """{"voiceSttEngine":"whisper","voiceTtsEngine":"platform","voiceCleanupEngine":"codex"}""",
        glossaryJson: String? = """{"glossary":["Supermux"]}""",
        configPutOk: Boolean = true,
        glossaryGetStatus: HttpStatusCode = HttpStatusCode.OK,
        glossaryPutStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<DesktopAppState, CopyOnWriteArrayList<Pair<HttpMethod, String>>> {
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
        val app = DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return app to methods
    }

    @Test fun desktop_app_state_voice_config_and_glossary() = runComposeUiTest {
        val (app, methods) = appForVoice()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(
                    loadConfig = { app.appConfig() },
                    loadModels = { app.launcherModels(it) },
                    saveVoiceStt = { app.saveVoiceStt(it) },
                    saveVoiceTts = { app.saveVoiceTts(it) },
                    saveVoiceCleanup = { e, m -> app.saveVoiceCleanup(e, m) },
                    glossaryLoad = { app.fetchGlossary() },
                    glossarySave = { app.updateGlossary(it) },
                )
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
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(
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
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                VoiceSettingsScreen(
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

    @Test fun settings_hub_opens_voice_section() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Voice) }
        val (app, _) = appForVoice()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_overlay").assertIsDisplayed()
        onNodeWithTag("settings_section_voice").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_settings_screen").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
