package dev.supermux.webview

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.runtime.SnapshotKey
import dev.supermux.webview.testing.FakeWebEngine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebViewStateTest {
    private val origin = WebOrigin("demo")
    private fun state() = WebViewState(WebContent.Html("<p/>", origin))

    @Test fun engineEventsUpdateTheState() {
        val s = state()
        s.apply(EngineEvent.UrlChanged("https://demo.invalid/"))
        s.apply(EngineEvent.TitleChanged("Demo"))
        s.apply(EngineEvent.Progress(0.4f))
        s.apply(EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false))
        s.apply(EngineEvent.Error(WebError.Http("https://demo.invalid/", 404)))
        s.apply(EngineEvent.ContentSize(300f, 120.5f))
        assertEquals("https://demo.invalid/", s.url)
        assertEquals("Demo", s.title)
        assertEquals(LoadingState.Loading(0.4f), s.loading)
        assertTrue(s.canGoBack)
        assertFalse(s.canGoForward)
        assertEquals(WebError.Http("https://demo.invalid/", 404), s.error)
        assertEquals(DpSize(300.dp, 120.5.dp), s.contentSize)
        s.apply(EngineEvent.Progress(null))
        assertEquals(LoadingState.Idle, s.loading)
    }

    @Test fun progressIsClampedToZeroToOne() {
        val s = state()
        s.apply(EngineEvent.Progress(3f))
        assertEquals(LoadingState.Loading(1f), s.loading)
    }

    @Test fun nativeSizesAreValidatedAndClamped() {
        val s = state()
        s.apply(EngineEvent.ContentSize(Float.NaN, 10f))
        assertNull(s.contentSize)
        s.apply(EngineEvent.ContentSize(300f, -1f))
        assertNull(s.contentSize)
        s.apply(EngineEvent.ContentSize(300f, 1e9f))
        assertEquals(DpSize(300.dp, 100_000.dp), s.contentSize)
    }

    @Test fun aNativeViewportHeightFeedsTheLoopDetector() {
        val s = state()
        s.apply(EngineEvent.ContentSize(300f, 176f, viewportHeightCss = 160f))
        s.apply(EngineEvent.ContentSize(300f, 192f, viewportHeightCss = 176f))
        s.apply(EngineEvent.ContentSize(300f, 208f, viewportHeightCss = 192f))
        assertEquals(192.dp, s.contentSize?.height)
    }

    // Pages sign everything they send with the nonce they announced; the bridge drops anything else.
    private fun WebViewState.page(event: BridgeMessage.Event) =
        apply(EngineEvent.PageMessage(origin.origin, BridgeCodec.encode(event, from = "n1")))

    // backgroundScope: the bridge's connection job never completes on its own, and runTest waits for children.
    private fun kotlinx.coroutines.test.TestScope.readyState(): WebViewState {
        val s = state()
        s.bridge.connect(sink = { _, _ -> }, originAllowed = { it == origin.origin }, scope = backgroundScope)
        s.page(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") }))
        return s
    }

    private fun WebViewState.size(h: Number, vh: Number?) = page(
        BridgeMessage.Event("\$size", buildJsonObject { put("w", 300); put("h", h); if (vh != null) put("vh", vh) }),
    )

    @Test fun reportedSizesAreValidatedAndClamped() = runTest {
        val s = readyState()
        s.size(-5, 100)
        assertNull(s.contentSize)
        s.size(1e9, 100)
        assertEquals(100_000.dp, s.contentSize?.height)
        s.bridge.disconnect()
    }

    @Test fun aPageThatGrowsWithItsViewportStopsGrowing() = runTest {
        val s = readyState()
        s.size(176, 160)
        s.size(192, 176)
        s.size(208, 192)
        s.size(224, 208)
        assertEquals(192.dp, s.contentSize?.height)
        s.bridge.disconnect()
    }

    @Test fun aPageThatGrowsFasterThanItsViewportStopsGrowing() = runTest {
        val s = readyState()
        s.size(240, 160)
        s.size(360, 240)
        s.size(540, 360)
        assertEquals(360.dp, s.contentSize?.height)
        s.bridge.disconnect()
    }

    @Test fun animatedGrowthIsFollowedEvenAfterLookingLikeALoop() = runTest {
        val s = readyState()
        s.size(300, 160)
        s.size(500, 200)
        s.size(700, 260)
        s.size(900, 260)
        assertEquals(900.dp, s.contentSize?.height)
        s.bridge.disconnect()
    }

    @Test fun contentThatFinishesGrowingWhileTheViewCatchesUpIsFollowed() = runTest {
        val s = readyState()
        s.size(300, 160)
        s.size(500, 200)
        s.size(700, 260)
        s.size(900, 300)
        s.size(900, 340)
        assertEquals(900.dp, s.contentSize?.height)
        s.bridge.disconnect()
    }

    @Test fun contentTallerThanTheViewportIsNotMistakenForALoop() = runTest {
        val s = readyState()
        s.size(500, 160)
        s.size(500, 360)
        assertEquals(500.dp, s.contentSize?.height)
        s.bridge.disconnect()
    }

    @Test fun theShimsConsoleSizeAndSaveProviderEventsReachTheState() = runTest {
        val s = state()
        s.bridge.connect(sink = { _, _ -> }, originAllowed = { it == origin.origin }, scope = backgroundScope)
        s.apply(EngineEvent.PageMessage(origin.origin, BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") }), from = "n1")))
        val messages = mutableListOf<ConsoleMessage>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { s.console.collect { messages += it } }
        fun page(event: BridgeMessage.Event) = s.apply(EngineEvent.PageMessage(origin.origin, BridgeCodec.encode(event, from = "n1")))
        page(BridgeMessage.Event("\$console", buildJsonObject { put("level", "warn"); put("text", "careful"); put("line", 3) }))
        page(BridgeMessage.Event("\$size", buildJsonObject { put("w", 320); put("h", 480.5) }))
        page(BridgeMessage.Event("\$saveStateProvider", JsonPrimitive(true)))
        runCurrent()
        assertEquals(listOf(ConsoleMessage(ConsoleLevel.Warn, "careful", line = 3)), messages)
        assertEquals(DpSize(320.dp, 480.5.dp), s.contentSize)
        assertTrue(s.pageSavesState)
        job.cancel()
        s.bridge.disconnect()
    }

    @Test fun nativeConsoleEventsAreForwarded() = runTest {
        val s = state()
        val messages = mutableListOf<ConsoleMessage>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { s.console.collect { messages += it } }
        s.apply(EngineEvent.Console(ConsoleMessage(ConsoleLevel.Error, "boom", uncaught = true)))
        runCurrent()
        assertEquals(listOf(ConsoleMessage(ConsoleLevel.Error, "boom", uncaught = true)), messages)
        job.cancel()
    }

    @Test fun loadingNewContentForgetsEverythingSavedForTheOld() {
        val s = state()
        s.savedEngineState = byteArrayOf(1)
        s.pageState = "1"
        s.pageStateOrigin = origin.origin
        s.pageSavesState = true
        s.snapshotKey = SnapshotKey("html:demo", 1, 1, false, 1f)
        s.error = WebError.Http(null, 500)
        s.load(WebContent.Url("https://a.test/"))
        assertNull(s.savedEngineState)
        assertNull(s.pageState)
        assertNull(s.pageStateOrigin)
        assertFalse(s.pageSavesState)
        assertNull(s.snapshotKey)
        assertNull(s.error)
        assertEquals(WebContent.Url("https://a.test/"), s.content)
    }

    @Test fun loadingWhileLiveLeavesTheEngineToTheWebView() {
        val s = state()
        val engine = FakeWebEngine(1)
        s.engine = engine
        var asked = 0
        s.onNeedsEvaluation = { asked++ }
        s.navigator.load("https://a.test/")
        assertEquals(WebContent.Url("https://a.test/"), s.content)
        assertTrue(engine.calls.none { it.startsWith("load:") })
        assertEquals(1, asked)
    }

    @Test fun navigatorCommandsReachALiveEngine() {
        val s = state()
        val engine = FakeWebEngine(1)
        s.engine = engine
        s.navigator.back()
        s.navigator.forward()
        s.navigator.stop()
        assertEquals(listOf("back", "forward", "stop"), engine.calls)
    }

    @Test fun reloadingACrashedViewMakesItColdAndAsksForActivation() {
        val s = state()
        var asked = 0
        s.onNeedsEvaluation = { asked++ }
        s.phase = WebViewPhase.Crashed
        s.navigator.reload()
        assertEquals(WebViewPhase.Cold, s.phase)
        assertEquals(1, asked)
    }

    @Test fun pageMemoryRoundTrips() {
        val s = state()
        s.snapshotKey = SnapshotKey("html:demo", 2, 3, true, 2f)
        s.contentSize = DpSize(10.dp, 20.dp)
        val bytes = byteArrayOf(7)
        s.savedEngineState = bytes
        s.pageState = "\"x\""
        s.pageStateOrigin = origin.origin
        s.pageSavesState = true
        val fresh = state()
        fresh.restoreFrom(s.memory())
        assertEquals(SnapshotKey("html:demo", 2, 3, true, 2f), fresh.snapshotKey)
        assertEquals(DpSize(10.dp, 20.dp), fresh.contentSize)
        assertSame(bytes, fresh.savedEngineState)
        assertEquals("\"x\"", fresh.pageState)
        assertEquals(origin.origin, fresh.pageStateOrigin)
        assertTrue(fresh.pageSavesState)
    }
}
