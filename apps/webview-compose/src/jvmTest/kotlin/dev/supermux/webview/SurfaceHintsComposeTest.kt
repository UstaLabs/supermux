package dev.supermux.webview

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
}
