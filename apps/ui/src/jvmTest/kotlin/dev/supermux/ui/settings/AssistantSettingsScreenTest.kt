package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BrokerApi
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The shared [AssistantSettingsScreen] (cluster E3) — desktop's suite, moved by name.
 *
 * Covers the Loading/Ready/Error load model (a failed soul fetch is never an editable blank), the
 * overwrite confirm, the typed save error, and the real `HostStore` + mocked `BrokerApi` paths —
 * plus the Compact branch Android contributed. Curator is its own hub section and keeps its tests
 * in `:desktop` (`CuratorSettingsScreenTest`) until cluster E4 moves that screen; the `AppShell`
 * hub wiring stays there too (`AssistantSettingsHubTest`).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AssistantSettingsScreenTest {

    private fun ComposeUiTest.assistantContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform = FakePlatform(), pointer = pointer, widthClass = widthClass) {
        content()
    }

    /**
     * The screen under its pre-E3 argument shape — the two suspend lambdas now travel as one
     * [AssistantSettingsActions], so every moved test reads as it did on desktop.
     */
    @Composable
    private fun AssistantSettingsScreenUnderTest(
        assistantLoad: suspend () -> Pair<String, String>?,
        assistantSave: suspend (paName: String, soul: String) -> String?,
        onDirtyChange: (Boolean) -> Unit = {},
        /** The Compact suite flips this to false to prove the screen brings Android's own bar. */
        topBarShown: Boolean = true,
        onBack: () -> Unit = {},
    ) = AssistantSettingsScreen(
        actions = AssistantSettingsActions(
            assistantLoad = assistantLoad,
            assistantSave = assistantSave,
        ),
        onDirtyChange = onDirtyChange,
        onBack = onBack,
        topBarShown = topBarShown,
    )

    private fun screen(
        assistantLoad: suspend () -> Pair<String, String>? = { "Mux" to "Be helpful." },
        assistantSave: suspend (String, String) -> String? = { _, _ -> null },
    ) = @Composable {
        AssistantSettingsScreenUnderTest(
            assistantLoad = assistantLoad,
            assistantSave = assistantSave,
        )
    }

    @Test fun assistant_renders_pa_name_and_soul() = runComposeUiTest {
        assistantContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
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
        // Curator is its own hub section now — not on the Identity screen.
        onNodeWithTag("assistant_curator_enabled").assertDoesNotExist()
    }

    @Test fun load_failure_shows_error_with_retry() = runComposeUiTest {
        assistantContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(assistantLoad = { null })()
            }
        }
        waitForIdle()
        onNodeWithTag("assistant_settings_error").assertIsDisplayed()
        onNodeWithTag("assistant_settings_retry").assertIsDisplayed()
        onNodeWithText("Couldn't load assistant settings.").assertIsDisplayed()
        onNodeWithTag("assistant_settings_content").assertDoesNotExist()
        onNodeWithTag("assistant_save").assertDoesNotExist()
    }

    @Test fun empty_soul_is_ready_not_error() = runComposeUiTest {
        assistantContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(assistantLoad = { "" to "" })()
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
        onNodeWithTag("assistant_settings_error").assertDoesNotExist()
        onNodeWithTag("assistant_save").assertIsDisplayed()
    }

    @Test fun save_soul_success_shows_saved_badge() = runComposeUiTest {
        val saved = AtomicReference<Pair<String, String>?>(null)
        assistantContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    assistantLoad = { "" to "" },
                    assistantSave = { name, soul ->
                        saved.set(name to soul)
                        null
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
        waitForIdle()
        onNodeWithTag("assistant_save_confirm").performClick()
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
        assistantContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    assistantLoad = { "x" to "y" },
                    assistantSave = { _, _ -> "Couldn't save soul.md — check connection and try again" },
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
        waitForIdle()
        onNodeWithTag("assistant_save_confirm").performClick()
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

    // ── HostStore + BrokerApi ─────────────────────────────────────────────────────────────

    private fun appForAssistant(
        configJson: String? = """{"paName":"Mux"}""",
        soulBody: String? = "Be helpful.",
        soulPutOk: Boolean = true,
        configPutOk: Boolean = true,
    ): Pair<HostStore, CopyOnWriteArrayList<Pair<HttpMethod, String>>> {
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
                    respond(
                        "{}",
                        if (configPutOk) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                        jsonHeaders,
                    )
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
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = HostStoreDeps(
                httpFactory = { HttpClient(engine) },
                settings = FakeSettingsStore(),
                clock = FixedClock(),
            ),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return app to methods
    }

    @Test fun desktop_app_state_assistant_load_and_save() = runComposeUiTest {
        val (app, methods) = appForAssistant()
        var loaded: Pair<String, String>? = null
        assistantContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AssistantSettingsScreenUnderTest(
                    assistantLoad = {
                        loaded = app.assistantLoad()
                        loaded
                    },
                    assistantSave = { n, s -> app.assistantSave(n, s) },
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
        waitForIdle()
        onNodeWithTag("assistant_save_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            methods.any { it.first == HttpMethod.Put && it.second == "/settings/soul" }
        }
        assertTrue(methods.any { it.second == "/settings/config" && it.first == HttpMethod.Put })
    }

    /** B1: soul GET 500 → Error, never Ready with blank soul. */
    @Test fun desktop_app_state_soul_fetch_failure_shows_error_not_empty() = runComposeUiTest {
        val (app, _) = appForAssistant(soulBody = null)
        var loaded: Pair<String, String>? = Pair("x", "y") // non-null until proven
        assistantContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AssistantSettingsScreenUnderTest(
                    assistantLoad = {
                        loaded = app.assistantLoad()
                        loaded
                    },
                    assistantSave = { n, s -> app.assistantSave(n, s) },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_settings_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertNull(loaded)
        onNodeWithTag("assistant_save").assertDoesNotExist()
        onNodeWithTag("assistant_settings_content").assertDoesNotExist()
    }

    /** B3: config PUT 500 must not report success even if soul would succeed. */
    @Test fun desktop_app_state_config_put_failure_reports_error() = runComposeUiTest {
        val (app, methods) = appForAssistant(configPutOk = false, soulPutOk = true)
        var saveErr: String? = "unset"
        assistantContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AssistantSettingsScreenUnderTest(
                    assistantLoad = { app.assistantLoad() },
                    assistantSave = { n, s ->
                        saveErr = app.assistantSave(n, s)
                        saveErr
                    },
                )
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
        waitForIdle()
        onNodeWithTag("assistant_save_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            saveErr != "unset" && saveErr != null
        }
        assertNotNull(saveErr)
        assertTrue(saveErr!!.contains("PA name"), "err=$saveErr")
        // Soul PUT must not run after config failure.
        assertFalse(methods.any { it.first == HttpMethod.Put && it.second == "/settings/soul" })
        onNodeWithTag("assistant_save_error").assertIsDisplayed()
    }

    // ── Compact branch (Android's phone shape) ──────────────────────────────────────────────────

    /**
     * On a phone the hub pushes the detail without a bar, so the screen brings Android's — and the
     * save still goes behind the overwrite confirm Android never had.
     */
    @Test fun compact_screen_paints_its_own_top_bar_and_back() = runComposeUiTest {
        var backs = 0
        assistantContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AssistantSettingsScreenUnderTest(
                    assistantLoad = { "Mux" to "Be helpful." },
                    assistantSave = { _, _ -> null },
                    topBarShown = false,
                    onBack = { backs++ },
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
        onNodeWithText("Assistant").assertIsDisplayed()
        onNodeWithTag("assistant_settings_back").assertIsDisplayed()
        onNodeWithTag("assistant_save").performClick()
        waitForIdle()
        onNodeWithTag("assistant_save_dialog").assertIsDisplayed()
        onNodeWithTag("assistant_save_cancel").performClick()
        waitForIdle()
        onNodeWithTag("assistant_settings_back").performClick()
        waitForIdle()
        assertEquals(1, backs)
    }

    /** The hub's dirty guard: editing reports dirty, saving clears it. */
    @Test fun dirty_flag_tracks_unsaved_edits() = runComposeUiTest {
        val dirty = AtomicReference(false)
        assistantContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AssistantSettingsScreenUnderTest(
                    assistantLoad = { "" to "" },
                    assistantSave = { _, _ -> null },
                    onDirtyChange = { dirty.set(it) },
                )
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
        assertFalse(dirty.get())
        onNodeWithTag("assistant_pa_name").performTextInput("Edited")
        waitUntil(timeoutMillis = 5_000) { dirty.get() }
        onNodeWithTag("assistant_save").performClick()
        waitForIdle()
        onNodeWithTag("assistant_save_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { !dirty.get() }
        assertFalse(dirty.get())
    }
}
