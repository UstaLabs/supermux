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
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** A shared renderer's death crashes every live view; those on screen come back by themselves (Task 4). */
@OptIn(ExperimentalTestApi::class)
class CrashRecoveryTest {
    private val url = WebContent.Url("https://example.test/")

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = TIMEOUT, condition = condition)

    private fun ComposeUiTest.created(factory: FakeWebEngineFactory): Int = runOnUiThread { factory.created.size }

    @Test fun aCrashedViewOnScreenGoesLiveAgainByItselfOnceAMinute() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp), activation = Activation.Eager) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        runOnUiThread { factory.created.single().emit(EngineEvent.Crashed) }
        waitFor("crashed") { state.phase == WebViewPhase.Crashed }
        mainClock.advanceTimeBy(1_100)
        waitFor("live again") { state.phase == WebViewPhase.Live }
        assertEquals(2, created(factory))
        // It crashes again within the minute: it stays Crashed until the app reloads it.
        runOnUiThread { factory.created.last().emit(EngineEvent.Crashed) }
        waitFor("crashed again") { state.phase == WebViewPhase.Crashed }
        mainClock.advanceTimeBy(5_000)
        waitForIdle()
        assertEquals(WebViewPhase.Crashed, state.phase)
        assertEquals(2, created(factory))
        runOnUiThread { state.navigator.reload() }
        waitFor("reloaded") { state.phase == WebViewPhase.Live }
        assertEquals(3, created(factory))
    }

    @Test fun aCrashedViewOffScreenWaitsUntilItIsShown() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(if (shown) 100.dp else 0.dp), activation = Activation.Eager)
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        runOnUiThread {
            factory.created.single().emit(EngineEvent.Crashed)
            shown = false
        }
        waitFor("crashed") { state.phase == WebViewPhase.Crashed }
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        assertEquals(WebViewPhase.Crashed, state.phase)
        assertEquals(1, created(factory))
        runOnUiThread { shown = true }
        waitFor("live again") { state.phase == WebViewPhase.Live }
        assertEquals(2, created(factory))
    }
}
