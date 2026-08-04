// Desktop-parity Task 5: Assistant identity (PA name + soul) + curator.
package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.workspace.SettingsSection
import dev.supermux.desktop.workspace.WorkspaceRoot
import dev.supermux.desktop.workspace.WorkspaceStateStore
import dev.supermux.desktop.workspace.WorkspaceUiState
import dev.supermux.net.BrokerApi
import dev.supermux.net.CuratorConfig
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningResponse
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
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AssistantSettingsScreenTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { p -> runCatching { Files.deleteIfExists(p) } }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("assistant_settings_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    private fun screen(
        assistantLoad: suspend () -> Pair<String, String>? = { "Mux" to "Be helpful." },
        assistantSave: suspend (String, String) -> Boolean = { _, _ -> true },
        curatorLoad: suspend () -> CuratorSettingsResponse? = {
            CuratorSettingsResponse(
                config = CuratorConfig(enabled = true, hour = 2, minute = 30, agent = "claude"),
                nextRun = "2026-08-05T02:30:00Z",
            )
        },
        curatorSave: suspend (Boolean, Int, Int, String, String?, String?) -> CuratorSettingsResponse? = {
                e, h, m, a, model, r ->
            CuratorSettingsResponse(config = CuratorConfig(e, h, m, a, model, r), nextRun = "2026-08-06T02:30:00Z")
        },
        curatorRunNow: suspend () -> Boolean = { true },
        loadModels: suspend (String) -> List<ModelInfo> = { emptyList() },
        loadReasoning: suspend (String, String?) -> ReasoningResponse? = { _, _ -> null },
    ) = @Composable {
        AssistantSettingsScreen(
            assistantLoad = assistantLoad,
            assistantSave = assistantSave,
            curatorLoad = curatorLoad,
            curatorSave = curatorSave,
            curatorRunNow = curatorRunNow,
            loadModels = loadModels,
            loadReasoning = loadReasoning,
        )
    }

    @Test fun assistant_renders_pa_name_and_soul() = runComposeUiTest {
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_settings_content").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_settings_screen").assertIsDisplayed()
        onNodeWithTag("assistant_pa_name").assertIsDisplayed()
        onNodeWithTag("assistant_soul").assertIsDisplayed()
        onNodeWithText("soul.md").assertIsDisplayed()
        onNodeWithTag("assistant_save").assertIsDisplayed()
        // Curator block is below the tall soul editor — scroll into view under Xvfb.
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_enabled").performScrollTo().assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_curator_run_now").performScrollTo().assertIsDisplayed()
    }

    @Test fun load_failure_shows_error() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(assistantLoad = { null })()
            }
        }
        waitForIdle()
        onNodeWithTag("assistant_settings_error").assertIsDisplayed()
        onNodeWithText("Couldn't load assistant settings.").assertIsDisplayed()
    }

    @Test fun save_soul_success_shows_saved_badge() = runComposeUiTest {
        val saved = AtomicReference<Pair<String, String>?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    assistantLoad = { "" to "" },
                    assistantSave = { name, soul ->
                        saved.set(name to soul)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_pa_name").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_pa_name").performTextInput("DeskPA")
        onNodeWithTag("assistant_soul").performTextInput("Stay concise.")
        onNodeWithTag("assistant_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("Saved").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals("DeskPA" to "Stay concise.", saved.get())
    }

    @Test fun save_soul_failure_shows_error() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    assistantLoad = { "x" to "y" },
                    assistantSave = { _, _ -> false },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_save").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_save_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Couldn't save soul.md — check connection and try again").assertIsDisplayed()
    }

    @Test fun curator_run_now_fires() = runComposeUiTest {
        val ran = AtomicReference(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(curatorRunNow = {
                    ran.set(true)
                    true
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_run_now").performScrollTo().assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_curator_run_now").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { ran.get() }
        assertTrue(ran.get())
    }

    @Test fun curator_save_updates_next_run() = runComposeUiTest {
        val saved = AtomicReference(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    curatorSave = { e, h, m, a, model, r ->
                        saved.set(true)
                        CuratorSettingsResponse(
                            config = CuratorConfig(e, h, m, a, model, r),
                            nextRun = "2099-01-01T00:00:00Z",
                        )
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_save").performScrollTo().assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_curator_save").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get() }
        assertTrue(saved.get())
    }

    @Test fun curator_next_run_label_disabled_when_off() {
        assertEquals("Disabled", curatorNextRunLabel(false, "2026-08-05T02:30:00Z"))
        assertEquals("—", curatorNextRunLabel(true, null))
        assertTrue(curatorNextRunLabel(true, "2026-08-05T02:30:00Z").isNotBlank())
    }

    // ── DesktopAppState + BrokerApi ─────────────────────────────────────────────────────────────

    private fun appForAssistant(
        configJson: String? = """{"paName":"Mux"}""",
        soulBody: String? = "Be helpful.",
        soulPutOk: Boolean = true,
        curatorJson: String? = """{"config":{"enabled":true,"hour":1,"minute":0,"agent":"claude"},"nextRun":null}""",
    ): Pair<DesktopAppState, CopyOnWriteArrayList<Pair<HttpMethod, String>>> {
        val methods = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val textHeaders = headersOf(HttpHeaders.ContentType, "text/plain")
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
                    respond("{}", HttpStatusCode.OK, jsonHeaders)
                path == "/settings/soul" && req.method == HttpMethod.Get -> {
                    if (soulBody == null) {
                        respond("", HttpStatusCode.InternalServerError, textHeaders)
                    } else {
                        respond(soulBody, HttpStatusCode.OK, textHeaders)
                    }
                }
                path == "/settings/soul" && req.method == HttpMethod.Put ->
                    respond(
                        "",
                        if (soulPutOk) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                        textHeaders,
                    )
                path == "/settings/curator" && req.method == HttpMethod.Get -> {
                    if (curatorJson == null) {
                        respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    } else {
                        respond(curatorJson, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/settings/curator" && req.method == HttpMethod.Put ->
                    respond(curatorJson ?: "{}", HttpStatusCode.OK, jsonHeaders)
                path == "/settings/curator/run-now" && req.method == HttpMethod.Post ->
                    respond("{}", HttpStatusCode.OK, jsonHeaders)
                path.startsWith("/models") ->
                    respond("""{"models":[]}""", HttpStatusCode.OK, jsonHeaders)
                path.startsWith("/reasoning-levels") ->
                    respond("""{"agent":"claude","levels":[],"visible":false}""", HttpStatusCode.OK, jsonHeaders)
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

    @Test fun desktop_app_state_assistant_load_and_save() = runComposeUiTest {
        val (app, methods) = appForAssistant()
        var loaded: Pair<String, String>? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AssistantSettingsScreen(
                    assistantLoad = {
                        loaded = app.assistantLoad()
                        loaded
                    },
                    assistantSave = { n, s -> app.assistantSave(n, s) },
                    curatorLoad = { app.curatorSettings() },
                    curatorSave = { e, h, m, a, model, r -> app.saveCurator(e, h, m, a, model, r) },
                    curatorRunNow = { app.runCuratorNow() },
                    loadModels = { app.launcherModels(it) },
                    loadReasoning = { a, model -> app.launcherReasoning(a, model) },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_settings_content").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals("Mux" to "Be helpful.", loaded)
        onNodeWithTag("assistant_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            methods.any { it.first == HttpMethod.Put && it.second == "/settings/soul" }
        }
        assertTrue(methods.any { it.second == "/settings/config" && it.first == HttpMethod.Put })
    }

    @Test fun settings_hub_opens_assistant_section() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { openSettings(SettingsSection.Assistant) }
        val (app, _) = appForAssistant()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_overlay").assertIsDisplayed()
        onNodeWithTag("settings_section_assistant").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_settings_screen").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
