package dev.supermux.desktop.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI spec for the web-parity terminal [TerminalTabs] strip: hydration from the broker list, add,
 * close, the bounded keep-alive policy, and `key(tabId)` panel isolation.
 *
 * SEAM (plan Task 6): `runComposeUiTest` cannot host a SwingPanel (its scene has no real AWT
 * window), so the real [DesktopTerminalPanel] can't render here. TerminalTabs takes an injectable
 * `panelContent` slot; these tests inject [recordingPanel], a pure-Compose fake that records the
 * tabId of every panel INSTANCE that mounts / disposes. A distinct mount per tabId is exactly the
 * proof that each tab got its own panel (its own remembered TerminalClient) — the `key(tabId)`
 * guarantee — and disposals prove the bounded (active + last-active) live-set policy.
 *
 * TIMING: the tab set hydrates in a `LaunchedEffect` that suspends on a real HTTP call (the ktor
 * MockEngine, on its OWN dispatcher — not the compose test clock), so `waitForIdle()` alone can
 * return before hydration lands. Every test therefore polls with [waitForTag] / `waitUntil`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class TerminalTabsTest {

    /** BrokerApi whose /api/term/list returns exactly [terminalListJson] (deterministic ids). */
    private fun appWithTerminals(terminalListJson: String): DesktopAppState {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(terminalListJson),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            apiOverride = api,
        )
    }

    /** Panel-slot fake: appends its tabId on mount and on dispose so tests can count instances. */
    private fun recordingPanel(
        mounts: MutableList<String>,
        disposals: MutableList<String>,
    ): @Composable (String, () -> dev.supermux.net.TerminalClient, Boolean) -> Unit =
        { tabId, _, _ ->
            LaunchedEffect(Unit) { mounts.add(tabId) }
            DisposableEffect(Unit) { onDispose { disposals.add(tabId) } }
            Box(Modifier.fillMaxSize().testTag("fake-panel-$tabId"))
        }

    @Composable
    private fun host(app: DesktopAppState, mounts: MutableList<String>, disposals: MutableList<String>) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            TerminalTabs(
                app = app,
                sessionId = "s1",
                panelContent = recordingPanel(mounts, disposals),
            )
        }
    }

    private fun ComposeUiTest.tagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** Block until a node with [tag] exists (hydration / async HTTP settle). */
    private fun ComposeUiTest.waitForTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { tagCount(tag) == 1 }

    @Test
    fun empty_list_hydrates_a_single_main_tab_with_add_button() = runComposeUiTest {
        val mounts = mutableListOf<String>()
        val app = appWithTerminals("""{"terminals":[]}""")
        setContent { host(app, mounts, mutableListOf()) }

        waitForTag("term-tab-main")
        onNodeWithTag("term-tab-main").assertIsDisplayed()
        onNodeWithTag("term-tab-close-main").assertIsDisplayed()
        onNodeWithTag("term-tab-add").assertIsDisplayed()
        // Only the active tab's panel is mounted.
        assertEquals(listOf("main"), mounts)
    }

    @Test
    fun hydrates_all_tabs_from_broker_list_but_mounts_only_the_active() = runComposeUiTest {
        val mounts = mutableListOf<String>()
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app, mounts, mutableListOf()) }

        waitForTag("term-tab-main")
        onNodeWithTag("term-tab-main").assertIsDisplayed()
        onNodeWithTag("term-tab-t2").assertIsDisplayed()
        // Both tabs exist, but only the active (first) panel is composed → connected.
        assertEquals(listOf("main"), mounts)
    }

    @Test
    fun add_button_creates_a_new_active_tab_as_its_own_panel_instance() = runComposeUiTest {
        val mounts = mutableListOf<String>()
        val app = appWithTerminals("""{"terminals":[]}""")
        setContent { host(app, mounts, mutableListOf()) }

        waitForTag("term-tab-main")
        assertEquals(listOf("main"), mounts)

        onNodeWithTag("term-tab-add").performClick()
        waitUntil(timeoutMillis = 5_000) { mounts.size == 2 }

        // A second, distinct panel instance mounted (the new tab) — main is kept alive (last-active),
        // so it is NOT remounted. Two distinct mounts prove per-tab panel isolation (key(tabId)).
        assertEquals("main", mounts[0])
        assertTrue(mounts[1] != "main", "new tab must be a fresh id, was ${mounts[1]}")
        onNodeWithTag("term-tab-main").assertIsDisplayed()
    }

    @Test
    fun close_removes_the_tab_from_the_strip() = runComposeUiTest {
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app, mutableListOf(), mutableListOf()) }

        waitForTag("term-tab-t2")
        onNodeWithTag("term-tab-close-t2").performClick()
        waitUntil(timeoutMillis = 5_000) { tagCount("term-tab-t2") == 0 }

        onNodeWithTag("term-tab-t2").assertDoesNotExist()
        onNodeWithTag("term-tab-main").assertIsDisplayed()
    }

    @Test
    fun switching_tabs_mounts_a_distinct_panel_per_tab_id() = runComposeUiTest {
        val mounts = mutableListOf<String>()
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2}]}""")
        setContent { host(app, mounts, mutableListOf()) }

        waitForTag("term-tab-main")
        assertEquals(listOf("main"), mounts)

        // Selecting t2 composes t2's OWN panel (a fresh LaunchedEffect(Unit) mount). If key(tabId)
        // were missing, Compose could reuse main's remembered client for t2 and never remount.
        onNodeWithTag("term-tab-t2").performClick()
        waitUntil(timeoutMillis = 5_000) { mounts.size == 2 }
        assertEquals(listOf("main", "t2"), mounts)
    }

    @Test
    fun bounded_keepalive_disposes_all_but_active_and_last_active() = runComposeUiTest {
        val mounts = mutableListOf<String>()
        val disposals = mutableListOf<String>()
        val app = appWithTerminals(
            """{"terminals":[{"id":"main","createdAt":1},{"id":"t2","createdAt":2},{"id":"t3","createdAt":3}]}""")
        setContent { host(app, mounts, disposals) }

        waitForTag("term-tab-main")
        onNodeWithTag("term-tab-t2").performClick() // active=t2, last-active=main
        waitUntil(timeoutMillis = 5_000) { mounts.size == 2 }
        onNodeWithTag("term-tab-t3").performClick() // active=t3, last-active=t2 → main leaves the live set
        waitUntil(timeoutMillis = 5_000) { mounts.size == 3 && disposals.isNotEmpty() }

        assertEquals(listOf("main", "t2", "t3"), mounts)
        // Bounded policy: only main (the oldest) was disposed; t2 (last-active) + t3 (active) survive.
        assertEquals(listOf("main"), disposals)
    }
}
