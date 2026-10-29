package dev.supermux.webview.testing

import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.pageSetup
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeWebEngineTest {
    private val demo = WebContent.Html("<p/>", WebOrigin("demo"))

    /** A lease's setup that gives [demo]'s documents the shim. */
    private val setup = WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOf(demo.origin.origin))

    private val hello = BridgeCodec.encode(BridgeMessage.Event("\$hello"))

    private val anyClient = object : EngineClient {
        override fun onNavigation(request: NavigationRequest) = NavigationDecision.Block
        override suspend fun onDialog(dialog: WebDialog): DialogResult = DialogResult.Dismissed
        override suspend fun onFileChooser(request: FileChooserRequest): List<String>? = null
        override suspend fun onPermission(request: PermissionRequest) = false
        override suspend fun onDownload(request: DownloadRequest) = false
        override fun onNewWindow(request: NewWindowRequest) = NewWindowDecision.Block
    }

    private fun isReady(event: EngineEvent) =
        event is EngineEvent.PageMessage && (BridgeCodec.decode(event.json) as? BridgeMessage.Event)?.name == "\$ready"

    private suspend fun FakeWebEngineFactory.engine(config: EngineConfig = EngineConfig(WebProfile.Default)): FakeWebEngine =
        (create(config) as WebEngineResult.Created).engine as FakeWebEngine

    @Test fun loadingReportsTheDocumentAndPaints() = runTest {
        val engine = FakeWebEngine(1)
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.configure(setup)
        engine.load(demo)
        runCurrent()
        assertEquals(
            listOf(
                EngineEvent.UrlChanged("https://demo.invalid/"),
                EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false),
                EngineEvent.Progress(0.5f),
                EngineEvent.PageMessage(
                    "https://demo.invalid",
                    BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "fake-1-1") })),
                ),
                EngineEvent.Progress(null),
                EngineEvent.FirstPaint,
            ),
            events,
        )
        assertEquals(listOf("configure", "load:${demo.key}"), engine.calls)
        assertEquals(emptyList(), engine.violations)
        job.cancel()
    }

    @Test fun aPageWithSaveStateAnnouncesItAndAnswersSaveState() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(pageState = JsonPrimitive(5)))
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.configure(setup)
        engine.load(demo)
        engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(3, "\$saveState")), demo.origin.origin)
        runCurrent()
        val messages = events.filterIsInstance<EngineEvent.PageMessage>().map { BridgeCodec.decode(it.json) }
        assertTrue(messages.contains(BridgeMessage.Event("\$saveStateProvider", JsonPrimitive(true))))
        assertTrue(messages.contains(BridgeMessage.Reply(3, JsonPrimitive(5))))
        job.cancel()
    }

    @Test fun resetForgetsTheDocumentAndTheClient() = runTest {
        val engine = FakeWebEngine(1)
        engine.client = anyClient
        engine.configure(setup)
        engine.load(demo)
        assertTrue(engine.reset())
        assertEquals(listOf("configure", "load:${demo.key}", "reset"), engine.calls)
        assertNull(engine.client)
        assertNull(engine.lastSetup)
        engine.postMessage(hello, demo.origin.origin)
        assertTrue(engine.calls.last().startsWith("post-dropped:"))
    }

    @Test fun aResetThatCannotGuaranteeIsolationSaysSo() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(resetSucceeds = false))
        engine.configure(setup)
        engine.load(demo)
        assertFalse(engine.reset())
    }

    @Test fun aResetCanBeHeldOpen() = runTest {
        val engine = FakeWebEngine(1)
        val gate = CompletableDeferred<Unit>()
        engine.resetGate = gate
        val r = async { engine.reset() }
        runCurrent()
        assertFalse(r.isCompleted)
        gate.complete(Unit)
        assertTrue(r.await())
    }

    @Test fun aCrashOrAReleaseMakesTheEngineUnusable() = runTest {
        val crashed = FakeWebEngine(1)
        assertTrue(crashed.isUsable)
        crashed.emit(EngineEvent.Crashed)
        assertFalse(crashed.isUsable)
        val released = FakeWebEngine(2)
        assertTrue(released.isUsable)
        released.release()
        assertFalse(released.isUsable)
    }

    @Test fun aLoadPaintsBeforeItReportsItsSize() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(contentHeight = { 300f }))
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.configure(setup)
        engine.load(demo)
        runCurrent()
        val ready = events.indexOfFirst(::isReady)
        val paint = events.indexOf(EngineEvent.FirstPaint)
        val size = events.indexOfFirst { it is EngineEvent.ContentSize }
        assertTrue(ready >= 0 && paint > ready, "\$ready ($ready) comes before FirstPaint ($paint): $events")
        assertTrue(size > paint, "FirstPaint ($paint) comes before ContentSize ($size): $events")
        job.cancel()
    }

    @Test fun restoringUnknownStateSaysSo() = runTest {
        val engine = FakeWebEngine(1)
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.configure(setup)
        assertFalse(engine.restoreState("fake:nope".encodeToByteArray(), demo))
        runCurrent()
        assertTrue(events.isEmpty())
        job.cancel()
    }

    @Test fun aDetachedEngineTakesNoSnapshot() = runTest {
        val engine = FakeWebEngine(1)
        engine.configure(setup)
        engine.load(demo)
        assertNull(engine.snapshot())
    }

    @Test fun theFactoryNumbersEnginesAndCanBeMadeUnavailable() = runTest {
        val factory = FakeWebEngineFactory()
        val config = EngineConfig(WebProfile.Default)
        val first = factory.create(config) as WebEngineResult.Created
        assertEquals(1, (first.engine as FakeWebEngine).id)
        factory.unavailable = UnavailableReason.NotImplemented
        assertEquals(WebEngineResult.Unavailable(UnavailableReason.NotImplemented, "fake"), factory.create(config))
    }

    @Test fun theFactoryCanBeHeldOpenAndCanFail() = runTest {
        val factory = FakeWebEngineFactory()
        val config = EngineConfig(WebProfile.Default)
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val held = async { factory.create(config) }
        runCurrent()
        assertFalse(held.isCompleted)
        gate.complete(Unit)
        assertTrue(held.await() is WebEngineResult.Created)
        factory.failCreate = true
        assertFailsWith<IllegalStateException> { factory.create(config) }
        factory.failCreate = false
        factory.unavailable = UnavailableReason.NoWebViewProvider
        assertEquals(WebEngineResult.Unavailable(UnavailableReason.NoWebViewProvider, "fake"), factory.create(config))
    }

    @Test fun aFactoryCanBeWrittenAsALambda() = runTest {
        val seen = mutableListOf<EngineConfig>()
        val factory = WebEngineFactory { config ->
            seen += config
            WebEngineResult.Unavailable(UnavailableReason.NotImplemented, "lambda")
        }
        val config = EngineConfig(WebProfile.Named("x"), mediaPlaybackRequiresGesture = false)
        assertEquals(WebEngineResult.Unavailable(UnavailableReason.NotImplemented, "lambda"), factory.create(config))
        assertEquals(listOf(config), seen)
    }

    @Test fun callsAreBounded() = runTest {
        val engine = FakeWebEngine(1)
        repeat(2_500) { engine.applyOverlays(OverlayCutouts.None) }
        assertEquals(2_000, engine.calls.size)
        assertTrue(engine.calls.last().startsWith("overlays:"))
    }

    @Test fun configureAndTheHooksAreRecorded() = runTest {
        val engine = FakeWebEngine(1)
        val dark = WebSettings.Default.pageSetup(dark = true, bridgeOrigins = setOf(demo.origin.origin))
        engine.configure(dark)
        engine.setVisible(false)
        engine.requestFocus()
        assertEquals(dark, engine.lastSetup)
        assertEquals(listOf("configure", "visible:false", "focus"), engine.calls)
    }

    @Test fun messagesForAnotherOriginAreDropped() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(pageState = JsonPrimitive(5)))
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.configure(setup)
        engine.load(demo)
        engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(3, "\$saveState")), "https://elsewhere.invalid")
        runCurrent()
        assertTrue(events.filterIsInstance<EngineEvent.PageMessage>().none { BridgeCodec.decode(it.json) is BridgeMessage.Reply })
        assertTrue(engine.calls.last().startsWith("post-dropped:"))
        job.cancel()
    }

    @Test fun anEngineWithNoDocumentSavesNothing() = runTest {
        assertEquals(null, FakeWebEngine(1).saveState())
    }

    @Test fun aHelloGetsTheCurrentNonceBack() = runTest {
        val engine = FakeWebEngine(1)
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.configure(setup)
        engine.load(demo)
        engine.postMessage(hello, demo.origin.origin)
        runCurrent()
        val readies = events.filterIsInstance<EngineEvent.PageMessage>().map { BridgeCodec.decode(it.json) }
            .filter { it is BridgeMessage.Event && it.name == "\$ready" }
        assertEquals(2, readies.size)
        assertEquals(readies[0], readies[1])
        job.cancel()
    }

    // SPI v2.1: the shim only where a real engine would install it.

    @Test fun noShimWithoutConfigure() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(pageState = JsonPrimitive(5)))
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.load(demo)
        engine.postMessage(hello, demo.origin.origin)
        runCurrent()
        assertTrue(events.none { it is EngineEvent.PageMessage }, "$events")
        assertTrue(EngineEvent.FirstPaint in events)
        assertEquals(listOf("load without configure"), engine.violations)
        job.cancel()
    }

    @Test fun noShimForAnOriginOutsideTheBridgeOrigins() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(pageState = JsonPrimitive(5)))
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOf("https://other.invalid")))
        engine.load(demo)
        engine.postMessage(hello, demo.origin.origin)
        engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(3, "\$saveState")), demo.origin.origin)
        runCurrent()
        assertTrue(events.none { it is EngineEvent.PageMessage }, "$events")
        assertEquals(emptyList(), engine.violations)
        job.cancel()
    }

    @Test fun noShimWithJavaScriptOff() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(pageState = JsonPrimitive(5)))
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.configure(WebSettings(javaScriptEnabled = false).pageSetup(dark = false, bridgeOrigins = setOf(demo.origin.origin)))
        engine.load(demo)
        engine.postMessage(hello, demo.origin.origin)
        engine.postMessage(BridgeCodec.encode(BridgeMessage.Call(3, "\$saveState")), demo.origin.origin)
        runCurrent()
        assertTrue(events.none { it is EngineEvent.PageMessage }, "$events")
        assertEquals(emptyList(), engine.violations)
        job.cancel()
    }

    @Test fun aConfiguredLoadHasTheShimAndNoViolations() = runTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(pageState = JsonPrimitive(5)))
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.configure(setup)
        engine.load(demo)
        runCurrent()
        assertTrue(events.any(::isReady), "$events")
        val messages = events.filterIsInstance<EngineEvent.PageMessage>().map { BridgeCodec.decode(it.json) }
        assertTrue(BridgeMessage.Event("\$saveStateProvider", JsonPrimitive(true)) in messages)
        assertEquals(emptyList(), engine.violations)
        job.cancel()
    }

    @Test fun aRestoreWithoutConfigureIsAViolation() = runTest {
        val first = FakeWebEngine(1)
        first.configure(setup)
        first.load(demo)
        val saved = assertNotNull(first.saveState())
        val second = FakeWebEngine(2)
        second.restoreState(saved, demo)
        assertEquals(listOf("load without configure"), second.violations)
    }

    @Test fun aResetAsksForAnotherConfigure() = runTest {
        val engine = FakeWebEngine(1)
        engine.configure(setup)
        engine.load(demo)
        assertTrue(engine.reset())
        engine.load(demo)
        assertEquals(listOf("load without configure"), engine.violations)
    }

    @Test fun restoringNeedsTheSameContent() = runTest {
        val factory = FakeWebEngineFactory()
        val first = factory.engine()
        first.configure(setup)
        first.load(demo)
        val saved = assertNotNull(first.saveState())
        val second = factory.engine()
        second.configure(setup)
        val events = mutableListOf<EngineEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { second.events.collect { events += it } }

        assertFalse(second.restoreState(saved, WebContent.Html("<p>another</p>", demo.origin)))
        runCurrent()
        assertTrue(events.isEmpty(), "$events")

        assertTrue(second.restoreState(saved, demo))
        runCurrent()
        assertEquals(EngineEvent.UrlChanged(demo.origin.url), events.first())
        assertTrue(events.any(::isReady), "$events")
        assertTrue(EngineEvent.FirstPaint in events)
        assertEquals(emptyList(), second.violations)
        job.cancel()
    }

    @Test fun aReleasedEngineCannotBeReset() = runTest {
        val engine = FakeWebEngine(1)
        engine.configure(setup)
        engine.load(demo)
        engine.release()
        assertFalse(engine.reset())
    }

    @Test fun anEngineReleasedDuringAResetCannotBeReset() = runTest {
        val engine = FakeWebEngine(1)
        val gate = CompletableDeferred<Unit>()
        engine.resetGate = gate
        val r = async { engine.reset() }
        runCurrent()
        engine.release()
        gate.complete(Unit)
        assertFalse(r.await())
    }

    @Test fun payloadsAreCut() = runTest {
        val engine = FakeWebEngine(1)
        engine.configure(setup)
        engine.load(demo)
        val big = BridgeCodec.encode(BridgeMessage.Event("big", JsonPrimitive("x".repeat(10_000))))
        engine.postMessage(big, demo.origin.origin)
        engine.postMessage(big, "https://elsewhere.invalid")
        engine.evaluate(big)
        assertEquals(listOf("post:", "post-dropped:", "eval:").map { it + big.take(200) }, engine.calls.takeLast(3))
    }

    @Test fun theFactoryGivesEachEngineItsConfig() = runTest {
        val factory = FakeWebEngineFactory()
        val ephemeral = EngineConfig(WebProfile.Ephemeral)
        val named = EngineConfig(WebProfile.Named("x"), mediaPlaybackRequiresGesture = false)
        assertEquals(ephemeral, factory.engine(ephemeral).config)
        assertEquals(named, factory.engine(named).config)
    }

    @Test fun resetClearsThePaintAndTheCutouts() = runTest {
        val engine = FakeWebEngine(1)
        engine.configure(setup)
        engine.load(demo)
        engine.applyOverlays(OverlayCutouts.None)
        assertTrue(engine.painted)
        assertNotNull(engine.cutouts)
        assertTrue(engine.reset())
        assertFalse(engine.painted)
        assertNull(engine.cutouts)
    }
}
