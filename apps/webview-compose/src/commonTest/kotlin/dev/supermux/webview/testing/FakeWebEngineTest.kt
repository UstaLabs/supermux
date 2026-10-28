package dev.supermux.webview.testing

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeWebEngineTest {
    private val demo = WebContent.Html("<p/>", WebOrigin("demo"))

    @Test fun loadingReportsTheDocumentAndPaints() = runTest {
        val engine = FakeWebEngine(1)
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.load(demo)
        runCurrent()
        assertEquals(
            listOf(
                EngineEvent.UrlChanged("https://demo.invalid/"),
                EngineEvent.Progress(null),
                EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false),
                EngineEvent.PageMessage(
                    "https://demo.invalid",
                    BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "fake-1-1") })),
                ),
                EngineEvent.FirstPaint,
            ),
            events,
        )
        assertEquals(listOf("load:${demo.key}"), engine.calls)
        job.cancel()
    }

    @Test fun aPageWithSaveStateAnnouncesItAndAnswersSaveState() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(pageState = JsonPrimitive(5)))
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.load(demo)
        engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(3, "\$saveState")), demo.origin.origin)
        runCurrent()
        val messages = events.filterIsInstance<EngineEvent.PageMessage>().map { BridgeCodec.decode(it.json) }
        assertTrue(messages.contains(BridgeMessage.Event("\$saveStateProvider", JsonPrimitive(true))))
        assertTrue(messages.contains(BridgeMessage.Reply(3, JsonPrimitive(5))))
        job.cancel()
    }

    @Test fun resetForgetsTheDocumentAndTheClient() {
        val engine = FakeWebEngine(1)
        engine.load(demo)
        engine.reset()
        assertEquals(listOf("load:${demo.key}", "reset"), engine.calls)
        assertEquals(null, engine.client)
    }

    @Test fun theFactoryNumbersEnginesAndCanBeMadeUnavailable() {
        val factory = FakeWebEngineFactory()
        val config = EngineConfig(WebSettings.Default, WebProfile.Default)
        val first = factory.create(config) as WebEngineResult.Created
        assertEquals(1, (first.engine as FakeWebEngine).id)
        factory.unavailable = UnavailableReason.NotImplemented
        assertEquals(WebEngineResult.Unavailable(UnavailableReason.NotImplemented), factory.create(config))
    }

    @Test fun messagesForAnotherOriginAreDropped() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(pageState = JsonPrimitive(5)))
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.load(demo)
        engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(3, "\$saveState")), "https://elsewhere.invalid")
        runCurrent()
        assertTrue(events.filterIsInstance<EngineEvent.PageMessage>().none { BridgeCodec.decode(it.json) is BridgeMessage.Reply })
        assertTrue(engine.calls.last().startsWith("post-dropped:"))
        job.cancel()
    }

    @Test fun anotherEngineFromTheSameFactoryRestoresWhatOneSaved() = runTest {
        val factory = FakeWebEngineFactory()
        val config = EngineConfig(WebSettings.Default, WebProfile.Default)
        val first = (factory.create(config) as WebEngineResult.Created).engine as FakeWebEngine
        first.load(demo)
        val saved = first.saveState()!!
        val second = (factory.create(config) as WebEngineResult.Created).engine as FakeWebEngine
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { second.events.collect { events += it } }
        second.restoreState(saved)
        runCurrent()
        assertEquals(EngineEvent.UrlChanged(demo.origin.url), events.first())
        assertTrue(
            events.filterIsInstance<EngineEvent.PageMessage>()
                .any { (BridgeCodec.decode(it.json) as? BridgeMessage.Event)?.name == "\$ready" },
        )
        assertTrue(EngineEvent.FirstPaint in events)
        job.cancel()
    }

    @Test fun anEngineWithNoDocumentSavesNothing() = runTest {
        assertEquals(null, FakeWebEngine(1).saveState())
    }

    @Test fun aHelloGetsTheCurrentNonceBack() = runTest {
        val engine = FakeWebEngine(1)
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.load(demo)
        engine.postMessage(BridgeCodec.encode(BridgeMessage.Event("\$hello")), demo.origin.origin)
        runCurrent()
        val readies = events.filterIsInstance<EngineEvent.PageMessage>().map { BridgeCodec.decode(it.json) }
            .filter { it is BridgeMessage.Event && it.name == "\$ready" }
        assertEquals(2, readies.size)
        assertEquals(readies[0], readies[1])
        job.cancel()
    }
}
