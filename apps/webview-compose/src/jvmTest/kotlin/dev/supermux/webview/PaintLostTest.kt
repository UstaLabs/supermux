package dev.supermux.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
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
}
