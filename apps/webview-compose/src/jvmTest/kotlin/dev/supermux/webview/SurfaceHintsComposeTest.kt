package dev.supermux.webview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.LocalWebSurfaceHints
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.WebSurfaceHints
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeWebEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/** A fake engine whose surface records the hints it was composed with. */
private class HintRecordingEngine(private val inner: FakeWebEngine) : WebEngine by inner {
    var seen: WebSurfaceHints? = null

    @Composable
    override fun Surface(modifier: Modifier) {
        seen = LocalWebSurfaceHints.current
        inner.Surface(modifier)
    }
}

@OptIn(ExperimentalTestApi::class)
class SurfaceHintsComposeTest {
    private fun factory(made: MutableList<HintRecordingEngine>) = WebEngineFactory { config ->
        WebEngineResult.Created(HintRecordingEngine(FakeWebEngine(made.size + 1, config = config)).also { made += it })
    }

    @Test fun anEmbedThatSizesItselfToItsPageIsInAScrollingList() = runComposeUiTest {
        val made = mutableListOf<HintRecordingEngine>()
        val runtime = testRuntime(factory(made))
        val state = WebViewState(WebContent.Url("https://example.test/"))
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, activation = Activation.Eager, sizing = WebSizing.WrapContentHeight(max = 300.dp))
            }
        }
        waitUntil(timeoutMillis = TIMEOUT) { state.phase == WebViewPhase.Live }
        assertEquals(WebSurfaceHints(inScrollingList = true), runOnUiThread { made.single().seen })
    }

    @Test fun aPaneIsNot() = runComposeUiTest {
        val made = mutableListOf<HintRecordingEngine>()
        val runtime = testRuntime(factory(made))
        val state = WebViewState(WebContent.Url("https://example.test/"))
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp), activation = Activation.Eager) } }
        waitUntil(timeoutMillis = TIMEOUT) { state.phase == WebViewPhase.Live }
        assertEquals(WebSurfaceHints(inScrollingList = false), runOnUiThread { made.single().seen })
    }

    /** An embed of a fixed height in a lazy list is in a scrolling list too: a drag on it should scroll the list. */
    @Test fun aFixedHeightEmbedInALazyListIsInAScrollingList() = runComposeUiTest {
        val made = mutableListOf<HintRecordingEngine>()
        val runtime = testRuntime(factory(made))
        val state = WebViewState(WebContent.Url("https://example.test/"))
        setContent {
            ProvideWebViewRuntime(runtime) {
                LazyColumn(Modifier.size(300.dp)) {
                    item { WebView(state, Modifier.fillMaxWidth().height(200.dp), activation = Activation.Eager) }
                }
            }
        }
        waitUntil(timeoutMillis = TIMEOUT) { state.phase == WebViewPhase.Live }
        waitForIdle()
        assertEquals(WebSurfaceHints(inScrollingList = true), runOnUiThread { made.single().seen })
    }

    /** What the app says wins over what the view would guess, either way. */
    @Test fun aHintTheAppProvidesWins() = runComposeUiTest {
        val made = mutableListOf<HintRecordingEngine>()
        val runtime = testRuntime(factory(made))
        val pane = WebViewState(WebContent.Url("https://example.test/a"))
        val embed = WebViewState(WebContent.Url("https://example.test/b"))
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    CompositionLocalProvider(LocalWebSurfaceHints provides WebSurfaceHints(inScrollingList = true)) {
                        WebView(pane, Modifier.size(200.dp), activation = Activation.Eager)
                    }
                    CompositionLocalProvider(LocalWebSurfaceHints provides WebSurfaceHints(inScrollingList = false)) {
                        WebView(embed, activation = Activation.Eager, sizing = WebSizing.WrapContentHeight(max = 300.dp))
                    }
                }
            }
        }
        waitUntil(timeoutMillis = TIMEOUT) { pane.phase == WebViewPhase.Live && embed.phase == WebViewPhase.Live }
        waitForIdle()
        runOnUiThread {
            assertEquals(WebSurfaceHints(inScrollingList = true), made.single { pane.engine === it }.seen)
            assertEquals(WebSurfaceHints(inScrollingList = false), made.single { embed.engine === it }.seen)
        }
    }
}
