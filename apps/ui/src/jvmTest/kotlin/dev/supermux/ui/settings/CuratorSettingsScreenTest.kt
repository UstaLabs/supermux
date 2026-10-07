package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BrokerApi
import dev.supermux.net.CuratorConfig
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningResponse
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.util.curatorNextRunLabel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The shared [CuratorSettingsScreen] (cluster E4).
 *
 * The desktop half of this suite is the one E3 split out of `AssistantSettingsScreenTest`, moved
 * here by name; the rest is new — the load error/retry, the agent switch that clears the model, and
 * the Compact branch Android contributed (neither host tested any of it before).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class CuratorSettingsScreenTest {

    private fun ComposeUiTest.curatorContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform = FakePlatform(), pointer = pointer, widthClass = widthClass) {
        content()
    }

    /** The holder is an E4 detail; the tests keep naming the five calls they always named. */
    @Composable
    private fun CuratorScreenUnderTest(
        curatorLoad: suspend () -> CuratorSettingsResponse?,
        curatorSave: suspend (Boolean, Int, Int, String, String?, String?) -> CuratorSettingsResponse?,
        curatorRunNow: suspend () -> Boolean,
        loadModels: suspend (String) -> List<ModelInfo>,
        loadReasoning: suspend (String, String?) -> ReasoningResponse?,
        topBarShown: Boolean = true,
    ) = CuratorSettingsScreen(
        actions = CuratorSettingsActions(
            curatorLoad, curatorSave, curatorRunNow, loadModels, loadReasoning,
        ),
        topBarShown = topBarShown,
    )

    private fun uiTestDeps(client: HttpClient) = HostStoreDeps(
        httpFactory = { client },
        settings = FakeSettingsStore(),
        clock = FixedClock(),
    )

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
        CuratorScreenUnderTest(
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
            deps = uiTestDeps(HttpClient(engine)),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
        return app to methods
    }

    @Test fun curator_section_renders_controls() = runComposeUiTest {
        curatorContent { SupermuxTheme(appearance = AppearanceMode.DARK) { curatorScreen()() } }
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
        curatorContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
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
        curatorContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
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
        curatorContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
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
        curatorContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
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
        curatorContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                CuratorScreenUnderTest(
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
        // The controls only exist once the load lands; clicking before that raced.
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
            methods.any { it.second == "/settings/curator/run-now" } && !ok
        }
        assertFalse(ok)
        onNodeWithTag("assistant_curator_run_error").assertIsDisplayed()
    }

    // ── New in E4 (neither host covered these) ─────────────────────────────────────────────────

    /** A failed load is its own state with a Retry — Android silently showed an empty form. */
    @Test fun load_failure_shows_error_with_retry() = runComposeUiTest {
        val loads = AtomicInteger(0)
        curatorContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                curatorScreen(curatorLoad = {
                    if (loads.incrementAndGet() == 1) null else defaultCurator()
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_curator_enabled").assertDoesNotExist()
        onNodeWithTag("assistant_curator_retry").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_enabled").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /** Picking an agent clears a model that belonged to the previous one. */
    @Test fun switching_agent_clears_the_model_selection() = runComposeUiTest {
        curatorContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                curatorScreen(
                    curatorLoad = {
                        CuratorSettingsResponse(
                            config = CuratorConfig(
                                enabled = true, hour = 2, minute = 30, agent = "claude",
                                model = "opus-x",
                            ),
                            nextRun = null,
                        )
                    },
                    loadModels = { agent ->
                        if (agent == "claude") listOf(ModelInfo("opus-x", "Opus X")) else emptyList()
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("Opus X").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("assistant_curator_agent").performClick()
        waitForIdle()
        onNodeWithText("Codex").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("Default").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * The clock chips are zero-padded — `pad2` replacing the JVM-only `"%02d".format(n)` that
     * could not follow the screen into commonMain.
     */
    @Test fun clock_chips_render_zero_padded() = runComposeUiTest {
        curatorContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                curatorScreen(
                    curatorLoad = {
                        CuratorSettingsResponse(
                            config = CuratorConfig(enabled = true, hour = 2, minute = 5, agent = "claude"),
                            nextRun = null,
                        )
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_hour").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // The chip Row merges its label, so the merged tree is what carries the text.
        onNodeWithTag("assistant_curator_hour").assertTextContains("02")
        onNodeWithTag("assistant_curator_minute").assertTextContains("05")
    }

    // ── Compact / touch (Android's branch) ─────────────────────────────────────────────────────

    @Test fun compact_page_paints_its_own_top_bar() = runComposeUiTest {
        var backs = 0
        curatorContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                CuratorSettingsScreen(
                    actions = CuratorSettingsActions(curatorLoad = { defaultCurator() }),
                    onBack = { backs++ },
                    topBarShown = false,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_enabled").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("curator_settings_back").performClick()
        assertEquals(1, backs)
    }

    /** With the hub's chrome already up, the page adds no second top bar. */
    @Test fun compact_page_defers_to_the_hub_chrome() = runComposeUiTest {
        curatorContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { curatorScreen()() }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_curator_enabled").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("curator_settings_back").assertDoesNotExist()
    }

    /** Touch chips are taller than pointer chips (`LocalPointerAvailable`, not the input mode). */
    @Test fun touch_picker_chips_are_taller_than_pointer_chips() {
        fun chipHeight(pointer: Boolean): Float {
            var height = 0f
            runComposeUiTest {
                curatorContent(pointer = pointer, widthClass = WindowWidthClass.Compact) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) { curatorScreen()() }
                }
                waitForIdle()
                waitUntil(timeoutMillis = 5_000) {
                    try {
                        onNodeWithTag("assistant_curator_hour").assertIsDisplayed()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                val node = onNodeWithTag("assistant_curator_hour").fetchSemanticsNode()
                height = node.size.height.toFloat()
            }
            return height
        }
        val touch = chipHeight(pointer = false)
        val mouse = chipHeight(pointer = true)
        assertTrue(touch > mouse, "touch chip $touch should be taller than pointer chip $mouse")
    }

    private fun defaultCurator() = CuratorSettingsResponse(
        config = CuratorConfig(enabled = true, hour = 2, minute = 30, agent = "claude"),
        nextRun = "2026-08-05T02:30:00Z",
    )
}
