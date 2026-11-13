package dev.supermux.ui.settings.pages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BrokerApi
import dev.supermux.net.CuratorConfig
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.ModelInfo
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.settings.kit.KitAnchorKey
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import dev.supermux.util.curatorNextRunLabel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Memory (#97 spec §2.2, §10.4, §11): the nightly tidy-up, its time, the next one with Run now, and who does it —
 * saved on every change, with the stored values for everything that did not change. Absorbs the old Curator
 * screen's suite.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MemoryPageTest {

    private fun anchored(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    private fun response(
        enabled: Boolean = true,
        hour: Int = 2,
        minute: Int = 30,
        agent: String = "claude",
        model: String? = null,
        reasoningLevel: String? = null,
        nextRun: String? = "2026-08-05T02:30:00Z",
    ) = CuratorSettingsResponse(CuratorConfig(enabled, hour, minute, agent, model, reasoningLevel), nextRun)

    /** What a save hands back: what was sent, as a host would store it. */
    private val echo: suspend (Boolean, Int, Int, String, String?, String?) -> CuratorSettingsResponse? =
        { e, h, m, a, mo, r -> CuratorSettingsResponse(CuratorConfig(e, h, m, a, mo, r), "2099-01-01T00:00:00Z") }

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    // ── the rows ──────────────────────────────────────────────────────────────────────────────

    @Test fun the_page_shows_its_rows_with_their_anchors() = runComposeUiTest {
        setPlatformContent { MemoryPage(CuratorSettingsActions(curatorLoad = { response() })) }
        awaitTag("memory_memory-nightly")
        onNodeWithTag("curator_settings_screen").assertExists()
        onNodeWithText("Tidy up memory every night").assertExists()
        onNodeWithText("An agent reads what your sessions learned, merges it and keeps it short.").assertExists()
        onNodeWithText("At").assertExists()
        onNodeWithText("02:30").assertExists()
        onNodeWithText("This machine's local time.").assertExists()
        onNodeWithText("Next tidy-up").assertExists()
        onNodeWithTag("assistant_curator_run_now").assertExists()
        onNodeWithText("Memory is shared by every agent on this machine.").assertExists()
        onNode(anchored("memory-nightly")).assertExists()
        onNode(anchored("memory-time")).assertExists()
        onNode(anchored("memory-next")).assertExists()
        // "Done by" sits in the closed Advanced fold.
        onNodeWithText("Done by").assertDoesNotExist()
        onNodeWithTag("memory_advanced").performClick()
        waitForIdle()
        onNodeWithText("Done by").assertExists()
        onNode(anchored("memory-agent")).assertExists()
        onNodeWithText("The default Agent's provider unless you pick another.").assertExists()
    }

    @Test fun the_machine_is_named_in_the_copy() = runComposeUiTest {
        val mini = KitMachine("m2", "studio-mini", local = false, online = true, status = "Running")
        setPlatformContent {
            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(mini), "m2") {}) {
                MemoryPage(CuratorSettingsActions(curatorLoad = { response() }))
            }
        }
        awaitTag("memory_memory-nightly")
        onNodeWithText("studio-mini's local time.").assertExists()
        onNodeWithText("Memory is shared by every agent on studio-mini.").assertExists()
    }

    @Test fun opened_for_the_agent_row_the_advanced_fold_opens_itself() = runComposeUiTest {
        setPlatformContent { MemoryPage(CuratorSettingsActions(curatorLoad = { response() }), anchor = "memory-agent") }
        awaitTag("assistant_curator_agent")
    }

    @Test fun there_is_no_save_button() = runComposeUiTest {
        setPlatformContent { MemoryPage(CuratorSettingsActions(curatorLoad = { response(enabled = true, hour = 1, minute = 0) })) }
        awaitTag("memory_memory-nightly")
        onNodeWithText("Save").assertDoesNotExist()
        onNodeWithTag("assistant_curator_save").assertDoesNotExist()
    }

    @Test fun the_time_is_zero_padded() = runComposeUiTest {
        setPlatformContent { MemoryPage(CuratorSettingsActions(curatorLoad = { response(hour = 2, minute = 5) })) }
        awaitTag("memory_memory-time")
        onNodeWithText("02:05").assertExists()
    }

    @Test fun next_run_label_helper() {
        assertEquals("Disabled", curatorNextRunLabel(false, "2026-08-05T02:30:00Z"))
        assertEquals("—", curatorNextRunLabel(true, null))
        val formatted = curatorNextRunLabel(true, "2026-08-05T02:30:00Z")
        assertTrue(formatted.isNotBlank())
        assertFalse(formatted == "2026-08-05T02:30:00Z", "valid ISO should be formatted, not raw")
        assertEquals("not-a-date", curatorNextRunLabel(true, "not-a-date"))
    }

    // ── saved on change ───────────────────────────────────────────────────────────────────────

    @Test fun turning_it_on_sends_the_stored_values_for_everything_else() = runComposeUiTest {
        val saves = CopyOnWriteArrayList<List<Any?>>()
        val stored = response(enabled = false, hour = 3, minute = 30, agent = "codex", model = "gpt-6", reasoningLevel = "high")
        setPlatformContent {
            MemoryPage(
                CuratorSettingsActions(
                    curatorLoad = { stored },
                    curatorSave = { e, h, m, a, mo, r ->
                        saves += listOf(e, h, m, a, mo, r)
                        CuratorSettingsResponse(stored.config.copy(enabled = e), stored.nextRun)
                    },
                ),
            )
        }
        awaitTag("memory_memory-nightly")
        onNodeWithTag("memory_memory-nightly").performClick()
        waitUntil(timeoutMillis = 5_000) { saves.isNotEmpty() }
        assertEquals(listOf(true, 3, 30, "codex", "gpt-6", "high"), saves.single())
    }

    @Test fun a_save_updates_the_next_tidy_up() = runComposeUiTest {
        setPlatformContent {
            MemoryPage(CuratorSettingsActions(curatorLoad = { response(enabled = false) }, curatorSave = echo))
        }
        awaitTag("memory_memory-nightly")
        onNodeWithText("Disabled").assertExists()
        onNodeWithTag("memory_memory-nightly").performClick()
        awaitText(curatorNextRunLabel(true, "2099-01-01T00:00:00Z"))
        onNodeWithTag("assistant_curator_next_run").assertExists()
        onNodeWithTag("memory_memory-nightly").assertIsOn()
    }

    @Test fun a_failed_save_puts_the_switch_back() = runComposeUiTest {
        setPlatformContent {
            MemoryPage(
                CuratorSettingsActions(
                    curatorLoad = { response(enabled = false, hour = 1, minute = 0) },
                    curatorSave = { _, _, _, _, _, _ -> null },
                ),
            )
        }
        awaitTag("memory_memory-nightly")
        onNodeWithTag("memory_memory-nightly").performClick()
        awaitText("Couldn't save.")
        onNodeWithTag("assistant_curator_save_error").assertExists()
        onNodeWithTag("memory_memory-nightly").assertIsOff()
    }

    @Test fun nothing_saves_when_the_model_list_fails_to_load() = runComposeUiTest {
        var saves = 0
        setPlatformContent {
            MemoryPage(
                CuratorSettingsActions(
                    curatorLoad = { response(enabled = true, hour = 1, minute = 0, agent = "codex", model = "gpt-6") },
                    curatorSave = { _, _, _, _, _, _ ->
                        saves++
                        null
                    },
                    loadModels = { emptyList() },
                ),
                anchor = "memory-agent",
            )
        }
        awaitTag("assistant_curator_model")
        waitForIdle()
        assertEquals(0, saves)
        // The stored model stays selected (and shown) though the list did not name it.
        onNodeWithText("gpt-6").assertExists()
    }

    @Test fun picking_a_time_saves_hour_and_minute_together() = runComposeUiTest {
        val saves = CopyOnWriteArrayList<List<Any?>>()
        setPlatformContent {
            MemoryPage(
                CuratorSettingsActions(
                    curatorLoad = { response(enabled = true, hour = 2, minute = 30) },
                    curatorSave = { e, h, m, a, mo, r ->
                        saves += listOf(e, h, m, a, mo, r)
                        echo(e, h, m, a, mo, r)
                    },
                ),
            )
        }
        awaitTag("memory_memory-time")
        onNodeWithTag("memory_memory-time").performClick()
        waitForIdle()
        onNodeWithTag("memory_time_hour_4").performClick()
        waitForIdle()
        // Nothing is saved until the time is whole.
        assertTrue(saves.isEmpty())
        onNodeWithTag("assistant_curator_minute_item_15").performClick()
        waitUntil(timeoutMillis = 5_000) { saves.isNotEmpty() }
        assertEquals(listOf(true, 4, 15, "claude", null, null), saves.single())
        onNodeWithText("04:15").assertExists()
    }

    @Test fun switching_the_agent_clears_its_model_and_saves() = runComposeUiTest {
        val saves = CopyOnWriteArrayList<List<Any?>>()
        setPlatformContent {
            MemoryPage(
                CuratorSettingsActions(
                    curatorLoad = { response(agent = "claude", model = "opus-x") },
                    curatorSave = { e, h, m, a, mo, r ->
                        saves += listOf(e, h, m, a, mo, r)
                        echo(e, h, m, a, mo, r)
                    },
                    loadModels = { agent -> if (agent == "claude") listOf(ModelInfo("opus-x", "Opus X")) else emptyList() },
                ),
                anchor = "memory-agent",
            )
        }
        awaitText("Opus X")
        onNodeWithTag("assistant_curator_agent").performClick()
        waitForIdle()
        onNodeWithText("Codex").performClick()
        waitUntil(timeoutMillis = 5_000) { saves.isNotEmpty() }
        assertEquals(listOf(true, 2, 30, "codex", null, null), saves.single())
        awaitText("Default")
    }

    @Test fun a_newer_change_waits_for_the_running_save_then_sends_the_latest() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val saves = CopyOnWriteArrayList<Boolean>()
        setPlatformContent {
            MemoryPage(
                CuratorSettingsActions(
                    curatorLoad = { response(enabled = false) },
                    curatorSave = { e, h, m, a, mo, r ->
                        saves += e
                        if (saves.size == 1) gate.await()
                        echo(e, h, m, a, mo, r)
                    },
                ),
            )
        }
        awaitTag("memory_memory-nightly")
        onNodeWithTag("memory_memory-nightly").performClick()
        waitUntil(timeoutMillis = 5_000) { saves.size == 1 }
        // Off and on again while the first save runs: only the latest values go out after it.
        onNodeWithTag("memory_memory-nightly").performClick()
        waitForIdle()
        onNodeWithTag("memory_memory-nightly").performClick()
        waitForIdle()
        assertEquals(1, saves.size)
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { saves.size == 2 }
        waitForIdle()
        assertEquals(listOf(true, true), saves.toList())
        onNodeWithTag("memory_memory-nightly").assertIsOn()
    }

    // ── run now ───────────────────────────────────────────────────────────────────────────────

    @Test fun run_now_fires() = runComposeUiTest {
        var ran = false
        setPlatformContent {
            MemoryPage(
                CuratorSettingsActions(
                    curatorLoad = { response() },
                    curatorRunNow = {
                        ran = true
                        true
                    },
                ),
            )
        }
        awaitTag("assistant_curator_run_now")
        onNodeWithTag("assistant_curator_run_now").performClick()
        waitUntil(timeoutMillis = 5_000) { ran }
        onNodeWithTag("assistant_curator_run_error").assertDoesNotExist()
    }

    @Test fun run_now_says_running_while_it_runs() = runComposeUiTest {
        val gate = CompletableDeferred<Boolean>()
        setPlatformContent {
            MemoryPage(CuratorSettingsActions(curatorLoad = { response() }, curatorRunNow = { gate.await() }))
        }
        awaitTag("assistant_curator_run_now")
        onNodeWithTag("assistant_curator_run_now").performClick()
        awaitText("Running…")
        gate.complete(true)
        awaitText("Run now")
    }

    @Test fun run_now_failure_shows_error() = runComposeUiTest {
        setPlatformContent { MemoryPage(CuratorSettingsActions(curatorLoad = { response() }, curatorRunNow = { false })) }
        awaitTag("assistant_curator_run_now")
        onNodeWithTag("assistant_curator_run_now").performClick()
        awaitTag("assistant_curator_run_error")
    }

    // ── load, offline ─────────────────────────────────────────────────────────────────────────

    @Test fun a_failed_load_offers_to_try_again() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setPlatformContent {
            MemoryPage(CuratorSettingsActions(curatorLoad = { if (loads.incrementAndGet() == 1) null else response() }))
        }
        awaitTag("assistant_curator_error")
        onNodeWithText("Couldn't reach this machine.").assertExists()
        onNodeWithTag("memory_memory-nightly").assertDoesNotExist()
        onNodeWithTag("assistant_curator_retry").performClick()
        awaitTag("memory_memory-nightly")
        assertEquals(2, loads.get())
    }

    @Test fun an_offline_machine_shows_the_offline_line_and_loads_nothing() = runComposeUiTest {
        val off = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline", lastSeen = "2h ago")
        setPlatformContent {
            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(off), "m2") {}) {
                MemoryPage(CuratorSettingsActions(curatorLoad = { error("must not load for an offline machine") }))
            }
        }
        onNodeWithText("studio-mini is offline · last seen 2h ago.").assertExists()
        onNodeWithTag("memory_memory-nightly").assertDoesNotExist()
    }

    // ── against a real HostStore ──────────────────────────────────────────────────────────────

    /** B4: runCuratorNow HTTP 500 → false (ensureMutationSuccess). */
    @Test fun run_now_on_http_500_shows_the_error() = runComposeUiTest {
        val methods = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            methods.add(req.method to path)
            when {
                path == "/settings/curator" ->
                    respond("""{"config":{"enabled":true,"hour":1,"minute":0,"agent":"claude"},"nextRun":null}""", HttpStatusCode.OK, json)
                path == "/settings/curator/run-now" -> respond("{}", HttpStatusCode.InternalServerError, json)
                path.startsWith("/models") -> respond("""{"models":[]}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = HostStoreDeps(httpFactory = { HttpClient(engine) }, settings = FakeSettingsStore(), clock = FixedClock()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
        setPlatformContent { MemoryPage(rememberCuratorSettingsActions(app)) }
        awaitTag("assistant_curator_run_now")
        onNodeWithTag("assistant_curator_run_now").performClick()
        awaitTag("assistant_curator_run_error")
        assertTrue(methods.any { it.second == "/settings/curator/run-now" })
    }
}
