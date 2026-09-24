package dev.supermux.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BrokerApi
import dev.supermux.state.HostStore
import dev.supermux.net.Mods
import dev.supermux.net.SpecialKey
import dev.supermux.net.printableSequence
import dev.supermux.net.specialKeySequence
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * UI spec for the web-parity terminal [TerminalTabs] strip — cluster G3's SHARED strip (desktop's
 * tab UI + Android's broker reconciliation): hydration from the broker list, add, close, the
 * bounded keep-alive policy, `key(tabId)` surface isolation, and the accessory key bar.
 *
 * SEAM: `runComposeUiTest` cannot host either engine (a SwingPanel has no real AWT window here, an
 * AndroidView needs Android), so [TerminalTabs] takes an injectable `surfaceFor` slot. These tests
 * inject [RecordingSurface], a pure-Compose fake [TerminalSurface] that records the tabId of every
 * grid that mounts / disposes and the bytes its own [TerminalKeySink] wrote. A distinct mount per
 * tabId is exactly the proof that each tab got its own surface (its own remembered TerminalClient)
 * — the `key(tabId)` guarantee — and disposals prove the bounded (active + last-active) live set.
 *
 * TIMING: the tab set hydrates in a `LaunchedEffect` that suspends on a real HTTP call (the ktor
 * MockEngine, on its OWN dispatcher — not the compose test clock), so `waitForIdle()` alone can
 * return before hydration lands. Every test therefore polls with [waitForTag] / `waitUntil`.
 *
 * RESOURCE: each test owns an [HttpClient]; [AfterTest] closes them so a full-suite run does not
 * wedge a worker on leaked MockEngine dispatchers (historical hang at setContent / waitForTag).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class TerminalTabsTest {

    private val clients = mutableListOf<HttpClient>()

    @AfterTest
    fun closeClients() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    /** HostStore over a given MockEngine (no WS / no real broker). */
    private fun appWithEngine(engine: MockEngine): HostStore {
        val client = HttpClient(engine)
        clients.add(client)
        val api = BrokerApi("ws://test:9898", "t", client)
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = HostStoreDeps(httpFactory = { client }, settings = FakeSettingsStore(), clock = FixedClock()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = api,
        )
    }

    /** BrokerApi whose /api/term/list returns exactly [terminalListJson] (deterministic ids). */
    private fun appWithTerminals(terminalListJson: String): HostStore =
        appWithEngine(MockEngine { req ->
            // Only the list endpoint is needed; answer everything else with empty JSON so a stray
            // call cannot hang on a mismatched body decode.
            val body = if (req.url.encodedPath.contains("/api/term/list")) {
                terminalListJson
            } else {
                "{}"
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

    /** A pure-Compose stand-in for one terminal surface, recording its own lifecycle + key bytes. */
    private class RecordingSurface(
        private val tabId: String,
        private val mounts: MutableList<String>,
        private val disposals: MutableList<String>,
        private val sent: MutableList<String>,
        private val hidden: MutableList<String>,
        private val activeOf: MutableMap<String, Boolean>,
    ) : TerminalSurface {
        override val keys = TerminalKeySink(onHideKeyboard = { hidden.add(tabId) }) { bytes ->
            sent.add("$tabId:${bytes.decodeToString()}")
        }

        @Composable
        override fun Content(modifier: Modifier, active: Boolean, onExit: (() -> Unit)?) {
            activeOf[tabId] = active
            LaunchedEffect(Unit) { mounts.add(tabId) }
            DisposableEffect(Unit) { onDispose { disposals.add(tabId) } }
            Box(modifier.fillMaxSize().testTag("fake-panel-$tabId"))
        }
    }

    private val mounts = mutableListOf<String>()
    private val disposals = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    private val hidden = mutableListOf<String>()
    private val activeOf = mutableMapOf<String, Boolean>()
    private val surfaces = mutableMapOf<String, RecordingSurface>()

    /** The surface slot: one recording surface per tab id, remembered like the real one. */
    private val recordingSurfaces: @Composable (String, () -> dev.supermux.net.TerminalClient) -> TerminalSurface =
        { tabId, _ ->
            remember(tabId) {
                RecordingSurface(tabId, mounts, disposals, sent, hidden, activeOf).also { surfaces[tabId] = it }
            }
        }

    @Composable
    private fun host(
        app: HostStore,
        input: InputMode = InputMode.Pointer,
        width: WindowWidthClass = WindowWidthClass.Expanded,
    ) {
        CompositionLocalProvider(
            LocalInputMode provides input,
            LocalPointerAvailable provides (input == InputMode.Pointer),
            LocalWindowWidthClass provides width,
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                TerminalTabs(app = app, sessionId = "s1", surfaceFor = recordingSurfaces)
            }
        }
    }

    private fun ComposeUiTest.tagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** Block until a node with [tag] exists (hydration / async HTTP settle). */
    private fun ComposeUiTest.waitForTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { tagCount(tag) == 1 }

    // ── the strip (desktop's suite, unchanged names) ────────────────────────────────────────────

    @Test
    fun empty_list_hydrates_a_single_main_tab_with_add_button() = runComposeUiTest {
        val app = appWithTerminals("""{"terminals":[]}""")
        setContent { host(app) }

        waitForTag("term-tab-main")
        onNodeWithTag("term-tab-main").assertIsDisplayed()
        onNodeWithTag("term-tab-close-main").assertIsDisplayed()
        onNodeWithTag("term-tab-add").assertIsDisplayed()
        // Only the active tab's surface is mounted.
        assertEquals(listOf("main"), mounts)
    }

    @Test
    fun hydrates_all_tabs_from_broker_list_but_mounts_only_the_active() = runComposeUiTest {
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app) }

        waitForTag("term-tab-main")
        onNodeWithTag("term-tab-main").assertIsDisplayed()
        onNodeWithTag("term-tab-t2").assertIsDisplayed()
        // Both tabs exist, but only the active (first) surface is composed → connected.
        assertEquals(listOf("main"), mounts)
    }

    @Test
    fun add_button_creates_a_new_active_tab_as_its_own_panel_instance() = runComposeUiTest {
        val app = appWithTerminals("""{"terminals":[]}""")
        setContent { host(app) }

        waitForTag("term-tab-main")
        assertEquals(listOf("main"), mounts)

        onNodeWithTag("term-tab-add").performClick()
        waitUntil(timeoutMillis = 5_000) { mounts.size == 2 }

        // A second, distinct surface mounted (the new tab) — main is kept alive (last-active),
        // so it is NOT remounted. Two distinct mounts prove per-tab isolation (key(tabId)).
        assertEquals("main", mounts[0])
        assertTrue(mounts[1] != "main", "new tab must be a fresh id, was ${mounts[1]}")
        onNodeWithTag("term-tab-main").assertIsDisplayed()
    }

    @Test
    fun close_removes_the_tab_from_the_strip() = runComposeUiTest {
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app) }

        waitForTag("term-tab-t2")
        onNodeWithTag("term-tab-close-t2").performClick()
        waitUntil(timeoutMillis = 5_000) { tagCount("term-tab-t2") == 0 }

        onNodeWithTag("term-tab-t2").assertDoesNotExist()
        onNodeWithTag("term-tab-main").assertIsDisplayed()
    }

    @Test
    fun switching_tabs_mounts_a_distinct_panel_per_tab_id() = runComposeUiTest {
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app) }

        waitForTag("term-tab-main")
        assertEquals(listOf("main"), mounts)

        // Selecting t2 composes t2's OWN surface (a fresh LaunchedEffect(Unit) mount). If key(tabId)
        // were missing, Compose could reuse main's remembered client for t2 and never remount.
        onNodeWithTag("term-tab-t2").performClick()
        waitUntil(timeoutMillis = 5_000) { mounts.size == 2 }
        assertEquals(listOf("main", "t2"), mounts)
    }

    @Test
    fun tab_added_during_hydration_survives_the_merge() = runComposeUiTest {
        // Gate the /api/term/list response with a non-blocking poll so the MockEngine coroutine
        // never permanently occupies a worker thread (CompletableDeferred.await can wedge under
        // full-suite load when compose waitForIdle races the suspended handler).
        val release = AtomicBoolean(false)
        val app = appWithEngine(MockEngine { _ ->
            var spins = 0
            while (!release.get() && spins < 500) {
                delay(10)
                spins++
            }
            respond(
                content = ByteReadChannel("""{"terminals":[{"id":"main","createdAt":1}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        setContent { host(app) }

        // Pre-hydration the strip is just the live `+` — click it while the fetch is suspended.
        onNodeWithTag("term-tab-add").performClick()
        waitUntil(timeoutMillis = 5_000) { mounts.size == 1 }
        val localId = mounts[0]

        release.set(true)
        waitForTag("term-tab-main")

        // The locally-added tab survived hydration (MERGED, not clobbered — the create is inside
        // its grace window): still in the strip, still the active tab, and its surface was never
        // disposed. `main` (fetched, inactive) is listed but not mounted.
        onNodeWithTag("term-tab-$localId").assertIsDisplayed()
        assertEquals(emptyList(), disposals, "local tab's surface must survive hydration")
        assertEquals(listOf(localId), mounts, "only the (still-active) local tab is mounted")
    }

    @Test
    fun bounded_keepalive_disposes_all_but_active_and_last_active() = runComposeUiTest {
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2},{"id":"t3","createdAt":3}]}""")
        setContent { host(app) }

        waitForTag("term-tab-main")
        onNodeWithTag("term-tab-t2").performClick() // active=t2, last-active=main
        waitUntil(timeoutMillis = 5_000) { mounts.size == 2 }
        onNodeWithTag("term-tab-t3").performClick() // active=t3, last-active=t2 → main leaves the live set
        waitUntil(timeoutMillis = 5_000) { mounts.size == 3 && disposals.isNotEmpty() }

        assertEquals(listOf("main", "t2", "t3"), mounts)
        // Bounded policy: only main (the oldest) was disposed; t2 (last-active) + t3 (active) survive.
        assertEquals(listOf("main"), disposals)
    }

    // ── the switch keeps the previous pane warm (G3) ────────────────────────────────────────────

    @Test
    fun switching_tabs_keeps_the_previous_surface_alive_and_inactive() = runComposeUiTest {
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app) }

        waitForTag("term-tab-main")
        onNodeWithTag("term-tab-t2").performClick()
        waitUntil(timeoutMillis = 5_000) { mounts.size == 2 }

        // main stays composed (kept alive, its shell + scrollback survive) but is told it is NOT
        // the foreground pane, so it cannot hold the pty geometry or the soft keyboard.
        assertEquals(emptyList(), disposals)
        assertEquals(false, activeOf["main"])
        assertEquals(true, activeOf["t2"])

        // Flipping back does not remount either surface.
        onNodeWithTag("term-tab-main").performClick()
        waitForTag("term-tab-main")
        waitUntil(timeoutMillis = 5_000) { activeOf["main"] == true }
        assertEquals(listOf("main", "t2"), mounts)
        assertEquals(emptyList(), disposals)
    }

    // ── the key bar (Android's, now shared and gated on Touch) ──────────────────────────────────

    @Test
    fun the_key_bar_is_drawn_only_under_touch() = runComposeUiTest {
        val app = appWithTerminals("""{"terminals":[]}""")
        setContent { host(app, input = InputMode.Pointer) }
        waitForTag("term-tab-main")
        // A mouse/keyboard client has the real keys — no accessory bar.
        onNodeWithTag("terminal_key_bar").assertDoesNotExist()
    }

    @Test
    fun the_key_bar_types_into_the_active_tab_and_a_modifier_arms_the_next_press() = runComposeUiTest {
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app, input = InputMode.Touch, width = WindowWidthClass.Compact) }

        waitForTag("term-tab-main")
        onNodeWithTag("terminal_key_bar").assertIsDisplayed()

        // Arming a modifier sends nothing; the NEXT key carries it and consumes the `once`.
        onNodeWithTag("terminal_key_Ctrl").performClick()
        waitForIdle()
        assertEquals(emptyList(), sent, "a modifier press sends nothing on its own")
        assertEquals(TerminalModState.ONCE, surfaces.getValue("main").keys.ctrl)

        onNodeWithTag("terminal_key_|").performClick()
        waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
        assertEquals(
            listOf("main:" + printableSequence('|', Mods(ctrl = true, alt = false))),
            sent,
            "Ctrl+| into the ACTIVE tab's pty",
        )
        assertEquals(TerminalModState.OFF, surfaces.getValue("main").keys.ctrl)

        // Switch tabs: the bar now drives t2's sink, and main's armed state cannot leak into it.
        // Tap the chip's LEADING edge: under Touch the trailing × is a 36dp finger target, which
        // on a two-character id sits under the chip's geometric centre (where performClick lands).
        onNodeWithTag("term-tab-t2").performTouchInput { click(Offset(2f, centerY)) }
        waitUntil(timeoutMillis = 5_000) { activeOf["t2"] == true }
        onNodeWithTag("terminal_key_Esc").performClick()
        waitUntil(timeoutMillis = 5_000) { sent.size == 2 }
        assertEquals(
            "t2:" + specialKeySequence(SpecialKey.Escape, Mods(ctrl = false, alt = false), appCursor = false),
            sent[1],
        )
    }

    @Test
    fun the_key_bar_hide_keyboard_button_dismisses_the_active_tabs_ime() = runComposeUiTest {
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app, input = InputMode.Touch, width = WindowWidthClass.Compact) }

        waitForTag("term-tab-main")
        onNodeWithTag("terminal_key_hide_keyboard").assertIsDisplayed()

        onNodeWithTag("terminal_key_hide_keyboard").performClick()
        waitUntil(timeoutMillis = 5_000) { hidden.isNotEmpty() }
        assertEquals(listOf("main"), hidden, "the button did not reach the ACTIVE tab's sink")

        // Switching tabs re-targets it, exactly like every other accessory-bar button.
        onNodeWithTag("term-tab-t2").performTouchInput { click(Offset(2f, centerY)) }
        waitUntil(timeoutMillis = 5_000) { activeOf["t2"] == true }
        onNodeWithTag("terminal_key_hide_keyboard").performClick()
        waitUntil(timeoutMillis = 5_000) { hidden.size == 2 }
        assertEquals("t2", hidden[1])
    }

    // ── add/close reconcile at both widths (a phone strip and a desktop strip) ──────────────────

    @Test
    fun add_and_close_reconcile_at_both_widths() = runComposeUiTest {
        val app = appWithTerminals("""{"terminals":[{"id":"main","createdAt":1}]}""")
        setContent { host(app, input = InputMode.Touch, width = WindowWidthClass.Compact) }

        waitForTag("term-tab-main")
        onNodeWithTag("term-tab-add").performClick()
        waitUntil(timeoutMillis = 5_000) { mounts.size == 2 }
        val added = mounts[1]
        onNodeWithTag("term-tab-$added").assertIsDisplayed()

        // Closing the locally-added tab hides it even though the broker never listed it, and the
        // active tab falls back to the survivor.
        onNodeWithTag("term-tab-close-$added").performClick()
        waitUntil(timeoutMillis = 5_000) { tagCount("term-tab-$added") == 0 }
        onNodeWithTag("term-tab-main").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) { activeOf["main"] == true }
    }

    @Test
    fun add_and_close_reconcile_at_expanded_width() = runComposeUiTest {
        val app = appWithTerminals("""{"terminals":[{"id":"main","createdAt":1}]}""")
        setContent { host(app, input = InputMode.Pointer, width = WindowWidthClass.Expanded) }

        waitForTag("term-tab-main")
        onNodeWithTag("term-tab-add").performClick()
        waitUntil(timeoutMillis = 5_000) { mounts.size == 2 }
        val added = mounts[1]

        onNodeWithTag("term-tab-close-$added").performClick()
        waitUntil(timeoutMillis = 5_000) { tagCount("term-tab-$added") == 0 }
        onNodeWithTag("term-tab-main").assertIsDisplayed()
        // The desktop strip has no key bar at any width.
        onNodeWithTag("terminal_key_bar").assertDoesNotExist()
    }

    // ── the pure reconciliation (Android's suite, unchanged names) ──────────────────────────────

    @Test fun broker_list_is_the_cross_device_source_of_truth() {
        assertEquals(
            listOf("ios-terminal", "android-terminal"),
            reconcileTerminalTabs(
                remoteIds = listOf("ios-terminal", "android-terminal"),
                localIds = listOf("main"),
                pendingCreates = emptyMap(),
                pendingCloses = emptySet(),
                nowMs = 20_000L,
            ),
        )
    }

    @Test fun pending_local_create_survives_until_websocket_creates_it() {
        assertEquals(
            listOf("existing", "new-terminal"),
            reconcileTerminalTabs(
                remoteIds = listOf("existing"),
                localIds = listOf("existing", "new-terminal"),
                pendingCreates = mapOf("new-terminal" to 10_000L),
                pendingCloses = emptySet(),
                nowMs = 12_000L,
            ),
        )
    }

    @Test fun expired_unconfirmed_create_is_removed() {
        assertEquals(
            listOf("existing"),
            reconcileTerminalTabs(
                remoteIds = listOf("existing"),
                localIds = listOf("existing", "never-created"),
                pendingCreates = mapOf("never-created" to 1_000L),
                pendingCloses = emptySet(),
                nowMs = 20_000L,
            ),
        )
    }

    @Test fun pending_close_stays_hidden_while_broker_removal_finishes() {
        assertEquals(
            listOf("other"),
            reconcileTerminalTabs(
                remoteIds = listOf("closing", "other"),
                localIds = listOf("other"),
                pendingCreates = emptyMap(),
                pendingCloses = setOf("closing"),
                nowMs = 0L,
            ),
        )
    }

    @Test fun active_terminal_moves_to_nearest_survivor() {
        assertEquals("third", activeTerminalAfterSync(listOf("first", "third"), "second", preferredIndex = 1))
        assertEquals("first", activeTerminalAfterSync(listOf("first"), "second", preferredIndex = 1))
        assertEquals("", activeTerminalAfterSync(emptyList(), "second"))
    }
    // ── cluster-G4 follow-ups from the G3 review ───────────────────────────────────────────────

    @Test
    fun terminal_tab_label_is_the_id_under_a_pointer_and_a_friendly_name_under_touch() {
        assertEquals("t4f0a91b2", terminalTabLabel("t4f0a91b2", 0, pointer = true))
        assertEquals("Terminal 1", terminalTabLabel("t4f0a91b2", 0, pointer = false))
        assertEquals("Terminal 3", terminalTabLabel("main", 2, pointer = false))
    }

    @Test
    fun a_touch_client_sees_friendly_labels_and_per_tab_close_descriptions() = runComposeUiTest {
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app, input = InputMode.Touch) }

        waitForTag("term-tab-main")
        // The raw tmux ids are gone from the chips; the position-based names took their place.
        onNodeWithText("Terminal 1").assertIsDisplayed()
        onNodeWithText("Terminal 2").assertIsDisplayed()
        // …and each × announces WHICH terminal it closes (they were all "Close terminal").
        onNodeWithContentDescription("Close Terminal 1").assertIsDisplayed()
        onNodeWithContentDescription("Close Terminal 2").assertIsDisplayed()
    }

    @Test
    fun a_pointer_client_still_sees_the_tmux_id_on_the_chip() = runComposeUiTest {
        val app = appWithTerminals("""{"terminals":[{"id":"main","createdAt":1}]}""")
        setContent { host(app) }

        waitForTag("term-tab-main")
        onNodeWithText("main").assertIsDisplayed()
        onNodeWithContentDescription("Close main").assertIsDisplayed()
    }

    @Test
    fun closing_the_last_tab_clears_the_selection_and_re_syncs_immediately() = runComposeUiTest {
        val lists = java.util.concurrent.atomic.AtomicInteger(0)
        val app = appWithEngine(
            MockEngine { req ->
                val body = if (req.url.encodedPath.contains("/api/term/list")) {
                    lists.incrementAndGet()
                    """{"terminals":[]}"""
                } else {
                    "{}"
                }
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        setContent { host(app) }

        waitForTag("term-tab-main")
        val before = lists.get()

        onNodeWithTag("term-tab-close-main").performClick()

        // A close used to wait out the whole 3s poll before the broker was asked again; the strip
        // now re-syncs at once (well inside that window).
        waitUntil(timeoutMillis = 2_000) { lists.get() > before }

        // …and with the merged list empty, the selection is cleared rather than left naming a tab
        // that no longer exists (the key bar would have typed into a dead sink).
        waitForTag("terminal_empty_add")
        assertEquals(0, tagCount("term-tab-main"))
        assertTrue(disposals.contains("main"))
    }
}
