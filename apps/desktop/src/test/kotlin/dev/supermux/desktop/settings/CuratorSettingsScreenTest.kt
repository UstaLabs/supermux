// Curator settings — the tests that stayed behind when the Assistant screen moved to `:ui` (E3).
package dev.supermux.desktop.settings

import dev.supermux.desktop.testDeps

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.desktop.theme.DesktopTheme
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * [CuratorSettingsScreen] — its own hub section, still a desktop screen (cluster E4 moves it).
 *
 * These tests lived in `AssistantSettingsScreenTest` while both screens shared a file; the
 * Assistant half moved to `:ui` with the screen in E3, so what is left is named for what it
 * actually covers.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class CuratorSettingsScreenTest {

    private fun curatorScreen(
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
        CuratorSettingsScreen(
            curatorLoad = curatorLoad,
            curatorSave = curatorSave,
            curatorRunNow = curatorRunNow,
            loadModels = loadModels,
            loadReasoning = loadReasoning,
        )
    }

    private fun appForCurator(
        curatorJson: String? = """{"config":{"enabled":true,"hour":1,"minute":0,"agent":"claude"},"nextRun":null}""",
        curatorRunStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<HostStore, CopyOnWriteArrayList<Pair<HttpMethod, String>>> {
        val methods = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            methods.add(req.method to path)
            when {
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
                    respond("{}", curatorRunStatus, jsonHeaders)
                path.startsWith("/models") ->
                    respond("""{"models":[]}""", HttpStatusCode.OK, jsonHeaders)
                path.startsWith("/reasoning-levels") ->
                    respond("""{"agent":"claude","levels":[],"visible":false}""", HttpStatusCode.OK, jsonHeaders)
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
        return app to methods
    }

    @Test fun curator_section_renders_controls() = runComposeUiTest {
        setContent { DesktopTheme(appearance = AppearanceMode.DARK) { curatorScreen()() } }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_enabled").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("curator_settings_screen").assertIsDisplayed()
        onNodeWithTag("assistant_curator_run_now").assertIsDisplayed()
    }

    @Test fun curator_run_now_fires() = runComposeUiTest {
        val ran = AtomicReference(false)
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                curatorScreen(curatorRunNow = {
                    ran.set(true)
                    true
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_run_now").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_curator_run_now").performClick()
        waitUntil(timeoutMillis = 5_000) { ran.get() }
        assertTrue(ran.get())
    }

    @Test fun curator_run_now_failure_shows_error() = runComposeUiTest {
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                curatorScreen(curatorRunNow = { false })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_run_now").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_curator_run_now").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_run_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Test fun curator_save_updates_next_run() = runComposeUiTest {
        val saved = AtomicReference(false)
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                curatorScreen(
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
                onNodeWithTag("assistant_curator_save").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_curator_save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get() }
        assertTrue(saved.get())
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_next_run").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        val expected = curatorNextRunLabel(true, "2099-01-01T00:00:00Z")
        onNodeWithText(expected).assertIsDisplayed()
    }

    @Test fun curator_save_failure_shows_error() = runComposeUiTest {
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                curatorScreen(curatorSave = { _, _, _, _, _, _ -> null })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_save").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_curator_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_save_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Test fun curator_next_run_label_disabled_when_off() {
        assertEquals("Disabled", curatorNextRunLabel(false, "2026-08-05T02:30:00Z"))
        assertEquals("—", curatorNextRunLabel(true, null))
        // Valid ISO → formatted local datetime (not the raw string, not blank).
        val formatted = curatorNextRunLabel(true, "2026-08-05T02:30:00Z")
        assertTrue(formatted.isNotBlank())
        assertFalse(formatted == "2026-08-05T02:30:00Z", "valid ISO should be formatted, not raw")
        // Unparseable falls back to raw.
        assertEquals("not-a-date", curatorNextRunLabel(true, "not-a-date"))
    }

    /** B4: runCuratorNow HTTP 500 → false (ensureMutationSuccess). */
    @Test fun desktop_app_state_run_curator_false_on_http_500() = runComposeUiTest {
        val (app, methods) = appForCurator(curatorRunStatus = HttpStatusCode.InternalServerError)
        var ok = true
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                CuratorSettingsScreen(
                    curatorLoad = { app.curatorSettings() },
                    curatorSave = { e, h, m, a, model, r -> app.saveCurator(e, h, m, a, model, r) },
                    curatorRunNow = {
                        ok = app.runCuratorNow()
                        ok
                    },
                    loadModels = { emptyList() },
                    loadReasoning = { _, _ -> null },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("assistant_curator_run_now").performClick()
        waitUntil(timeoutMillis = 5_000) {
            methods.any { it.second == "/settings/curator/run-now" } && !ok
        }
        assertFalse(ok)
        onNodeWithTag("assistant_curator_run_error").assertIsDisplayed()
    }
}
