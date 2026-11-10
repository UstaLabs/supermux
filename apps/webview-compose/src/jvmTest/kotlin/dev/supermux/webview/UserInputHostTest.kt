package dev.supermux.webview

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.bridge.InputStamp
import dev.supermux.webview.bridge.UserInputSource
import dev.supermux.webview.bridge.inputClock
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The host's wiring of a click-to-send: [WebView] gives its bridge the engine's own [UserInputSource], so a page's call
 * to a [dev.supermux.webview.bridge.WebBridge.handleWithUserInput] method is the user's only if that engine's view had
 * input just before it, and once. The rules themselves are the stamp's and the bridge's tests; these run the whole path
 * on the library's fake engine.
 */
@OptIn(ExperimentalTestApi::class)
class UserInputHostTest {
    /** A fake engine that tells of input the way every real engine does: it has a stamp the test marks as the view's input. */
    private class StampedFake(val fake: FakeWebEngine) : WebEngine by fake, UserInputSource {
        val stamp = InputStamp()

        override fun consumeUserInput(): Boolean = stamp.consume(inputClock())
    }

    private class StampedFactory(val inner: FakeWebEngineFactory = FakeWebEngineFactory()) : WebEngineFactory by inner {
        val engines = mutableListOf<StampedFake>()

        override suspend fun create(config: EngineConfig): WebEngineResult {
            val result = inner.create(config)
            if (result !is WebEngineResult.Created) return result
            return WebEngineResult.Created(StampedFake(result.engine as FakeWebEngine).also { engines += it })
        }
    }

    private val card = WebContent.Html("<p>card</p>", WebOrigin("card"))

    private fun <T> ComposeUiTest.ui(block: () -> T): T = runOnUiThread(block)

    /** The page's call, as the shim of the engine's current document makes it. */
    private fun StampedFake.call(id: Long) {
        val nonce = "fake-${fake.id}-${fake.documentsStarted}"
        fake.emit(EngineEvent.PageMessage(card.origin.origin, BridgeCodec.encode(BridgeMessage.Call(id, "send", JsonPrimitive("hi")), from = nonce)))
    }

    private class Shown(val state: WebViewState, val seen: MutableList<Boolean>)

    private suspend fun ComposeUiTest.show(factory: StampedFactory): Shown {
        val runtime = testRuntime(factory)
        val state = WebViewState(card)
        val seen = mutableListOf<Boolean>()
        state.bridge.handleWithUserInput("send") { _, fromUser ->
            seen += fromUser
            JsonPrimitive(fromUser)
        }
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitUntil("live and ready", TIMEOUT) { state.phase == WebViewPhase.Live && ui { state.bridge.isReady } }
        return Shown(state, seen)
    }

    @Test fun aCallAfterTheEnginesInputIsTheUsersAndOnlyOnce() = runComposeUiTest {
        val factory = StampedFactory()
        val shown = show(factory)
        val engine = factory.engines.single()
        ui {
            engine.stamp.mark(inputClock())
            engine.call(1)
            engine.call(2)
        }
        waitUntil("both calls served", TIMEOUT) { ui { shown.seen.size } == 2 }
        assertEquals(listOf(true, false), ui { shown.seen.toList() }, "one input allows one send")
    }

    @Test fun aCallWithNoInputIsNotTheUsers() = runComposeUiTest {
        val factory = StampedFactory()
        val shown = show(factory)
        ui { factory.engines.single().call(1) }
        waitUntil("the call served", TIMEOUT) { ui { shown.seen.size } == 1 }
        assertEquals(listOf(false), ui { shown.seen.toList() })
    }

    @Test fun anEngineThatDoesNotTellOfInputNeverAllowsASend() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(card)
        val seen = mutableListOf<Boolean>()
        state.bridge.handleWithUserInput("send") { _, fromUser ->
            seen += fromUser
            JsonPrimitive(fromUser)
        }
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitUntil("live and ready", TIMEOUT) { state.phase == WebViewPhase.Live && ui { state.bridge.isReady } }
        val engine = factory.created.single()
        ui {
            val nonce = "fake-${engine.id}-${engine.documentsStarted}"
            engine.emit(EngineEvent.PageMessage(card.origin.origin, BridgeCodec.encode(BridgeMessage.Call(1, "send", JsonPrimitive("hi")), from = nonce)))
        }
        waitUntil("the call served", TIMEOUT) { ui { seen.size } == 1 }
        assertEquals(listOf(false), ui { seen.toList() })
    }
}
