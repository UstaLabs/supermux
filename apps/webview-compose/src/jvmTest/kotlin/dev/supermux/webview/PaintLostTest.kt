package dev.supermux.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An engine that lets its painted page go on its own says so ([EngineEvent.PaintLost]): the web gives a page of another
 * site's slot to one on screen, and its view, when it comes back, has nothing to show until the page loads again. The
 * view covers it again, as before its first paint, and the next first paint lifts the cover.
 */
@OptIn(ExperimentalTestApi::class)
class PaintLostTest {
    private fun <T> ComposeUiTest.ui(block: () -> T): T = runOnUiThread(block)

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = TIMEOUT, condition = condition)

    @Test fun aLostPaintBringsThePlaceholderBackUntilTheNextFirstPaint() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(paintsOnLoad = false))
        val runtime = testRuntime(factory)
        val state = WebViewState(WebContent.Url("https://example.test/"))
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(200.dp), placeholder = { Box(Modifier.fillMaxSize().testTag("placeholder")) })
            }
        }
        waitFor("an engine") { ui { factory.created.isNotEmpty() } }
        ui { factory.created.single().emit(EngineEvent.FirstPaint) }
        waitFor("live") { state.phase == WebViewPhase.Live }
        onNodeWithTag("placeholder").assertDoesNotExist()

        ui { factory.created.single().emit(EngineEvent.PaintLost) }
        waitFor("covered again") { state.phase == WebViewPhase.Activating }
        waitForIdle()
        onNodeWithTag("placeholder").assertExists()

        ui { factory.created.single().emit(EngineEvent.FirstPaint) }
        waitFor("live again") { state.phase == WebViewPhase.Live }
        waitForIdle()
        onNodeWithTag("placeholder").assertDoesNotExist()
        assertEquals(1, ui { factory.created.size }, "the same engine throughout")
    }

    /** An engine that, once [lost], has nothing to replay: its page went while parked, and loads afresh when shown. */
    private class LostPage(val fake: FakeWebEngine) : WebEngine by fake {
        var lost = false

        override fun replayState() {
            if (!lost) fake.replayState()
        }
    }

    /**
     * A page its engine let go while parked is a new page when it paints again, not the replay of the one its snapshot
     * shows: the adopting view snapshots it once it settles, as after any first paint. Taken for the replay, a page
     * whose snapshot was fresh when it was parked would keep that snapshot, of a page that is gone, as its cover.
     */
    @Test fun aPageLostWhileParkedIsSnapshottedAgainOnceItPaints() = runComposeUiTest {
        val fakes = FakeWebEngineFactory()
        val engines = mutableListOf<LostPage>()
        val runtime = testRuntime(
            WebEngineFactory { config ->
                val fake = (fakes.create(config) as WebEngineResult.Created).engine as FakeWebEngine
                WebEngineResult.Created(LostPage(fake).also { engines += it })
            },
        )
        val page = WebContent.Html("<p>demo</p>", WebOrigin("demo"))
        var shown by mutableStateOf(true)
        var state: WebViewState? = null
        setContent {
            ProvideWebViewRuntime(runtime) {
                if (shown) {
                    val shownState = rememberWebViewState(page).also { state = it }
                    WebView(shownState, Modifier.size(100.dp), settings = WebSettings(darkMode = DarkMode.Light))
                }
            }
        }
        waitFor("live") { ui { state?.phase } == WebViewPhase.Live }
        val engine = ui { engines.single() }
        fun snapshots() = ui { engine.fake.calls.count { it == "snapshot" } }
        mainClock.advanceTimeBy(600)
        waitFor("its settle snapshot") { snapshots() == 1 && ui { state?.snapshotKey } != null }

        ui { shown = false }
        waitFor("parked") { ui { runtime.pool.parkedCount } == 1 }
        ui {
            engine.lost = true
            engine.fake.emit(EngineEvent.PaintLost)
        }
        ui { shown = true }
        waitFor("adopted") { ui { state?.engine } === engine }
        // The page loads afresh in its new place, and paints.
        ui {
            engine.fake.emit(EngineEvent.UrlChanged(page.origin.url))
            engine.fake.emit(EngineEvent.FirstPaint)
        }
        waitFor("live again") { ui { state?.phase } == WebViewPhase.Live }
        mainClock.advanceTimeBy(1_000)
        waitFor("snapshotted again") { snapshots() == 2 }
        assertEquals(1, ui { engines.size }, "the same engine throughout")
        ui { fakes.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}'s violations") } }
    }
}
