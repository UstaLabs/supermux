package dev.supermux.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `WebView(keepRunningWhileHidden = true)`: a view composed but not placed (a Browser tab another tab is in front of,
 * moved with `movableContentOf` between a holder that never places it and its pane) keeps its page running, and shows it
 * live at once when placed again.
 */
@OptIn(ExperimentalTestApi::class)
class KeepRunningWhileHiddenTest {
    private val url = WebContent.Url("https://tab.test/")

    private fun <T> ComposeUiTest.ui(block: () -> T): T = runOnUiThread(block)

    private fun ComposeUiTest.calls(engine: FakeWebEngine): List<String> = ui { engine.calls.toList() }

    private fun ComposeUiTest.assertNoViolations(factory: FakeWebEngineFactory) = ui {
        factory.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}'s violations") }
    }

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = TIMEOUT, condition = condition)

    /** The engine that loaded [content]. */
    private fun ComposeUiTest.engineOf(factory: FakeWebEngineFactory, content: WebContent): FakeWebEngine =
        ui { factory.created.single { engine -> engine.calls.any { it == "load:${content.key}" } } }

    /**
     * A tab of the desktop app: its view composed once, in its pane while [front] says so, else in a holder that
     * composes it without placing it.
     */
    private class Tab(val state: WebViewState, val keepRunning: Boolean) {
        var front by mutableStateOf(true)

        @Composable
        fun Show() {
            val view = remember {
                movableContentOf {
                    WebView(
                        state,
                        Modifier.size(100.dp),
                        activation = Activation.Eager,
                        keepRunningWhileHidden = keepRunning,
                    )
                }
            }
            if (front) Box { view() } else Unplaced { view() }
        }
    }

    /** Hidden far longer than hiddenReleaseMillis, it keeps its engine and its document: nothing saved, reset or loaded. */
    @Test fun aKeepRunningViewKeepsItsEngineAndDocumentPastHiddenReleaseMillis() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50)
        val tab = Tab(WebViewState(url), keepRunning = true)
        setContent { ProvideWebViewRuntime(runtime) { tab.Show() } }
        waitFor("live") { tab.state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        tab.front = false
        waitForIdle()
        mainClock.advanceTimeBy(10_000)
        waitForIdle()
        assertEquals(WebViewPhase.Live, tab.state.phase, "hidden 10 s, 200 times hiddenReleaseMillis")
        assertEquals(1, ui { runtime.pool.liveCount })
        assertTrue(ui { tab.state.engine === engine }, "the same engine")
        tab.front = true
        waitForIdle()
        assertEquals(WebViewPhase.Live, tab.state.phase)
        val calls = calls(engine)
        assertEquals(1, ui { factory.created.size })
        assertEquals(listOf("load:${url.key}"), calls.filter { it.startsWith("load") || it.startsWith("restore") }, "$calls")
        assertFalse("save" in calls || "reset" in calls, "$calls")
        assertNoViolations(factory)
    }

    /** Its engine is never told it is hidden: on macOS and iOS that suspends the page's media. */
    @Test fun aKeepRunningViewsEngineIsNeverToldItIsHidden() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val tab = Tab(WebViewState(url), keepRunning = true)
        setContent { ProvideWebViewRuntime(runtime) { tab.Show() } }
        waitFor("live") { tab.state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        repeat(2) {
            tab.front = false
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            tab.front = true
            waitForIdle()
        }
        val visibility = calls(engine).filter { it.startsWith("visible:") }
        assertFalse("visible:false" in visibility, "$visibility")
        assertNoViolations(factory)
    }

    /**
     * Shown again with its engine holding the painted page, it shows the live page at once: no cover is drawn, nor even
     * read back from the snapshot cache, and it stays Live throughout.
     */
    @Test fun aKeepRunningViewShownAgainHasNoCover() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val tab = Tab(WebViewState(url), keepRunning = true)
        setContent { ProvideWebViewRuntime(runtime) { tab.Show() } }
        waitFor("live") { tab.state.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { tab.state.snapshotKey != null } }
        tab.front = false
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        tab.front = true
        waitForIdle()
        val host = assertNotNull(ui { tab.state.owner as? WebViewHost })
        assertEquals(WebViewPhase.Live, tab.state.phase)
        assertNull(ui { host.coverToDraw(dark = false) }, "no cover drawn")
        assertNull(ui { host.cover }, "no cover held: the live page shows")
        assertEquals(0, ui { runtime.coverCount })
        assertNoViolations(factory)
    }

    /**
     * Over budget, a visible view takes a plain hidden view's engine before a keep-running one's, even when the
     * keep-running one was used longer ago: it ranks with visible views, not hidden ones.
     */
    @Test fun aVisibleViewTakesAPlainHiddenViewsEngineBeforeAKeepRunningOnes() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 2)
        val running = Tab(WebViewState(WebContent.Url("https://running.test/")), keepRunning = true)
        val plain = Tab(WebViewState(WebContent.Url("https://plain.test/")), keepRunning = false)
        val newcomer = WebViewState(WebContent.Url("https://newcomer.test/"))
        var showNewcomer by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    running.Show()
                    plain.Show()
                    if (showNewcomer) WebView(newcomer, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("both live") { running.state.phase == WebViewPhase.Live && plain.state.phase == WebViewPhase.Live }
        // The keep-running tab is hidden first, and so used least recently.
        running.front = false
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        plain.front = false
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        showNewcomer = true
        waitFor("the newcomer live") { newcomer.phase == WebViewPhase.Live }
        waitForIdle()
        assertEquals(WebViewPhase.Live, running.state.phase, "the keep-running page kept its engine")
        assertEquals(WebViewPhase.Cold, plain.state.phase, "the plain hidden page gave its engine up")
        assertTrue("save" in calls(engineOf(factory, plain.state.content)))
        assertFalse("reset" in calls(engineOf(factory, running.state.content)))
        assertNoViolations(factory)
    }
}

/** Measures its content and never places it. */
@Composable
private fun Unplaced(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        measurables.forEach { it.measure(constraints) }
        layout(0, 0) {}
    }
}
