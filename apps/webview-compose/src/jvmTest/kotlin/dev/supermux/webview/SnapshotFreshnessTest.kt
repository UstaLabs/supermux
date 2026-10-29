package dev.supermux.webview

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** A page that hasn't changed since its last snapshot isn't snapshotted again on its way out (Task 5). */
@OptIn(ExperimentalTestApi::class)
class SnapshotFreshnessTest {
    private val url = WebContent.Url("https://example.test/")

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = TIMEOUT, condition = condition)

    private fun ComposeUiTest.snapshots(engine: FakeWebEngine): Int = runOnUiThread { engine.calls.count { it == "snapshot" } }

    @Test fun aPageSnapshottedAfterItSettledIsNotSnapshottedAgainWhenItGoesCold() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(if (shown) 100.dp else 0.dp), activation = Activation.WhenVisible(settleMillis = 0))
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = runOnUiThread { factory.created.single() }
        mainClock.advanceTimeBy(600)
        waitFor("the settle snapshot") { snapshots(engine) == 1 }
        runOnUiThread { shown = false }
        mainClock.advanceTimeBy(2_500)
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertEquals(1, snapshots(engine))
    }

    @Test fun aPageNotSnapshottedSinceItPaintedIsSnapshottedOnItsWayOut() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(if (shown) 100.dp else 0.dp), activation = Activation.WhenVisible(settleMillis = 0))
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = runOnUiThread { factory.created.single() }
        // Hidden before its settle snapshot: that one finds a 0×0 surface and gets nothing, so the way out tries again.
        runOnUiThread { shown = false }
        mainClock.advanceTimeBy(2_500)
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertEquals(2, snapshots(engine))
    }
}
