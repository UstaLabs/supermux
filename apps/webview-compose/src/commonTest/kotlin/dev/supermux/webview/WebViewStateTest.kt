package dev.supermux.webview

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.bridge.BridgeShim
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.runtime.SavedPageState
import dev.supermux.webview.runtime.SizeReport
import dev.supermux.webview.runtime.SnapshotKey
import dev.supermux.webview.testing.FakeWebEngine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

    /** The view that owns a state, as the navigator sees it: it runs commands on [engine], if it has one. */
    private class Owner(override val engine: WebEngine?) : StateOwner {
        override fun onEngine(command: (WebEngine) -> Unit): Boolean {
            command(engine ?: return false)
            return true
        }
    }

    @Test fun engineEventsUpdateTheState() {
        val s = state()
        s.onEngineEvent(EngineEvent.UrlChanged("https://demo.invalid/"))
        s.onEngineEvent(EngineEvent.TitleChanged("Demo"))
        s.onEngineEvent(EngineEvent.Progress(0.4f))
        s.onEngineEvent(EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false))
        s.onEngineEvent(EngineEvent.Error(WebError.Http("https://demo.invalid/", 404)))
        s.onEngineEvent(EngineEvent.ContentSize(300f, 120.5f))
        assertEquals("https://demo.invalid/", s.url)
        assertEquals("Demo", s.title)
        assertEquals(LoadingState.Loading(0.4f), s.loading)
        assertTrue(s.canGoBack)
        assertFalse(s.canGoForward)
        assertEquals(WebError.Http("https://demo.invalid/", 404), s.error)
        assertEquals(DpSize(300.dp, 120.5.dp), s.contentSize)
        s.onEngineEvent(EngineEvent.Progress(null))
        assertEquals(LoadingState.Idle, s.loading)
    }

    @Test fun progressIsClampedToZeroToOne() {
        val s = state()
        s.onEngineEvent(EngineEvent.Progress(3f))
        assertEquals(LoadingState.Loading(1f), s.loading)
    }

    @Test fun progressIsClampedBelowToo() {
        val s = state()
        s.onEngineEvent(EngineEvent.Progress(-0.5f))
        assertEquals(LoadingState.Loading(0f), s.loading)
    }

    @Test fun aNaNProgressIsIgnored() {
        val s = state()
        s.onEngineEvent(EngineEvent.Progress(0.4f))
        s.onEngineEvent(EngineEvent.Progress(Float.NaN))
        assertEquals(LoadingState.Loading(0.4f), s.loading)
    }

    // Pages sign everything they send with the nonce they announced; the bridge drops anything else.
    private fun WebViewState.page(event: BridgeMessage.Event) =
        onEngineEvent(EngineEvent.PageMessage(origin.origin, BridgeCodec.encode(event, from = "n1")))

    // backgroundScope: the bridge's connection job never completes on its own, and runTest waits for children.
    private fun TestScope.readyState(): WebViewState {
        val s = state()
        s.bridge.connect(sink = { _, _ -> }, originAllowed = { it == origin.origin }, scope = backgroundScope)
        s.page(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") }))
        return s
    }

    @Test fun aCommitToAnotherOriginClosesTheBridge() = runTest {
        val s = readyState()
        assertTrue(s.bridge.isReady)
        s.onEngineEvent(EngineEvent.UrlChanged("https://demo.invalid/next"))
        assertTrue(s.bridge.isReady, "a same-origin commit keeps the gate open")
        s.onEngineEvent(EngineEvent.UrlChanged("https://elsewhere.test/"))
        assertFalse(s.bridge.isReady)
        s.bridge.disconnect()
    }

    @Test fun anOpaqueCommitClosesTheBridge() = runTest {
        val s = readyState()
        s.onEngineEvent(EngineEvent.UrlChanged(null))
        assertFalse(s.bridge.isReady)
        assertNull(s.url)
        s.bridge.disconnect()
    }

    @Test fun aCommitToAnotherUrlClearsTheError() {
        val s = state()
        val failed = WebError.Http("https://demo.invalid/missing", 404)
        s.onEngineEvent(EngineEvent.Error(failed))
        s.onEngineEvent(EngineEvent.UrlChanged("https://demo.invalid/missing"))
        assertEquals(failed, s.error, "the failed page's own commit keeps its error")
        s.onEngineEvent(EngineEvent.UrlChanged("https://demo.invalid/"))
        assertNull(s.error)
    }

    // --- Sizes ---

    @Test fun nativeSizesAreValidatedAndClamped() {
        val s = state()
        s.onEngineEvent(EngineEvent.ContentSize(Float.NaN, 10f))
        assertNull(s.contentSize)
        s.onEngineEvent(EngineEvent.ContentSize(300f, -1f))
        assertNull(s.contentSize)
        s.onEngineEvent(EngineEvent.ContentSize(300f, 1e9f))
        assertEquals(DpSize(300.dp, 32_000.dp), s.contentSize)
    }

    @Test fun nativeNonFiniteHeightsAndNegativeWidthsAreIgnored() {
        val s = state()
        s.onEngineEvent(EngineEvent.ContentSize(300f, Float.NaN))
        s.onEngineEvent(EngineEvent.ContentSize(300f, Float.POSITIVE_INFINITY))
        s.onEngineEvent(EngineEvent.ContentSize(-1f, 10f))
        assertNull(s.contentSize)
    }

    @Test fun aNativeSizeWithZeroWidthIsIgnored() {
        val s = state()
        s.onEngineEvent(EngineEvent.ContentSize(0f, 100f))
        s.onEngineEvent(EngineEvent.ContentSize(-0f, 100f))
        assertNull(s.contentSize)
    }

    /**
     * A zero height is ignored like a zero width: a view collapsed to 0 px could never report again, and page memory
     * would keep every later view of the content at 0 px.
     */
    @Test fun aZeroHeightIsIgnored() = runTest {
        val s = readyState()
        s.onEngineEvent(EngineEvent.ContentSize(300f, 0f))
        s.onEngineEvent(EngineEvent.ContentSize(300f, -0f))
        s.page(BridgeMessage.Event("\$size", buildJsonObject { put("w", 300); put("h", 0) }))
        assertNull(s.contentSize)
        s.onEngineEvent(EngineEvent.ContentSize(300f, 120f))
        s.onEngineEvent(EngineEvent.ContentSize(300f, 0f))
        assertEquals(120.dp, s.contentSize?.height)
    }

    @Test fun bothSidesAreClampedToWhatComposeCanLayOut() = runTest {
        val s = readyState()
        s.page(BridgeMessage.Event("\$size", buildJsonObject { put("w", 1e30); put("h", 1e9) }))
        assertEquals(DpSize(32_000.dp, 32_000.dp), s.contentSize)
        val native = state()
        native.onEngineEvent(EngineEvent.ContentSize(1e9f, 1e9f))
        assertEquals(DpSize(32_000.dp, 32_000.dp), native.contentSize)
        s.bridge.disconnect()
    }

    private fun WebViewState.size(h: Number, vh: Number?) = page(
        BridgeMessage.Event("\$size", buildJsonObject { put("w", 300); put("h", h); if (vh != null) put("vh", vh) }),
    )

    /** A size the engine measured natively: the page's height [h] and its viewport's [vh]. */
    private fun WebViewState.native(h: Float, vh: Float) = onEngineEvent(EngineEvent.ContentSize(300f, h, vh))

    private fun WebViewState.replay(reports: List<Pair<Float, Float>>) = reports.forEach { (h, vh) -> native(h, vh) }

    @Test fun reportedSizesAreValidatedAndClamped() = runTest {
        val s = readyState()
        s.size(-5, 100)
        assertNull(s.contentSize)
        s.size(1e9, 100)
        assertEquals(32_000.dp, s.contentSize?.height)
        s.bridge.disconnect()
    }

    // --- The loop detector ---

    @Test fun aNativeViewportHeightFeedsTheLoopDetector() {
        val s = state()
        s.onEngineEvent(EngineEvent.ContentSize(300f, 176f, viewportHeightCss = 160f))
        s.onEngineEvent(EngineEvent.ContentSize(300f, 192f, viewportHeightCss = 176f))
        s.onEngineEvent(EngineEvent.ContentSize(300f, 208f, viewportHeightCss = 192f))
        assertEquals(192.dp, s.contentSize?.height)
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

    @Test fun aPageExactlyAsTallAsItsViewportIsFollowed() {
        val s = state()
        s.native(160f, 160f)
        s.native(176f, 176f)
        s.native(192f, 192f)
        assertEquals(192.dp, s.contentSize?.height)
    }

    @Test fun growthInterruptedByAnotherKindOfChangeStartsOver() {
        val s = state()
        s.native(176f, 160f)
        s.native(192f, 176f)
        s.native(180f, 164f)
        s.native(196f, 180f)
        assertEquals(196.dp, s.contentSize?.height)
    }

    @Test fun aCaughtPageMayStillShrink() {
        val s = state()
        s.native(176f, 160f)
        s.native(192f, 176f)
        s.native(208f, 192f)
        assertEquals(192.dp, s.contentSize?.height)
        s.native(150f, 180f)
        assertEquals(150.dp, s.contentSize?.height)
    }

    @Test fun aLoopingPageThatAlsoChangesOnItsOwnDoesNotCreep() {
        val s = state()
        var tallest = 0f
        for ((h, vh) in creeping) {
            s.native(h, vh)
            tallest = maxOf(tallest, s.contentSize?.height?.value ?: 0f)
        }
        assertEquals(179f, tallest)
        assertEquals(179.dp, s.contentSize?.height)
    }

    @Test fun aLoopingPageWithAGrowingLogGrowsByTheLogOnly() {
        val s = state()
        s.replay(hero)
        // Caught at 178, then the log's own 400 px.
        assertEquals(578.dp, s.contentSize?.height)
    }

    @Test fun aNewViewOfACaughtPageStaysCaught() {
        val first = state()
        first.replay(firstView)
        var memory = first.memory()
        repeat(3) {
            val view = state()
            view.restoreFrom(memory)
            // Shown at the remembered height, the page reports what it reported last time.
            view.native(194f, 178f)
            assertEquals(178.dp, view.contentSize?.height)
            memory = view.memory()
        }
    }

    @Test fun aRememberedVerdictLetsAPageThatGrewGo() {
        val first = state()
        first.replay(firstView)
        val view = state()
        view.restoreFrom(first.memory())
        view.native(700f, 178f)
        // The cap moves by the page's change since the remembered report. Held at 178 instead, a static page would
        // never report again: the view wouldn't move.
        assertEquals(684.dp, view.contentSize?.height)
        view.replay(nowTaller.drop(1))
        assertEquals(700.dp, view.contentSize?.height)
    }

    @Test fun aFastGrowingPlainPageIsNotStranded() {
        for (reports in listOf(fastPlainBefore, fastPlain)) {
            val s = state()
            s.replay(reports)
            assertEquals(700.dp, s.contentSize?.height)
        }
    }

    @Test fun identicalReportsAreIgnored() {
        val s = state()
        s.replay(duplicates)
        assertEquals(178.dp, s.contentSize?.height)
    }

    @Test fun aReportThatOnlyChangesTheWidthKeepsTheStreak() {
        val s = state()
        s.onEngineEvent(EngineEvent.ContentSize(300f, 176f, 160f))
        s.onEngineEvent(EngineEvent.ContentSize(300f, 192f, 176f))
        s.onEngineEvent(EngineEvent.ContentSize(320f, 192f, 176f))
        assertEquals(DpSize(320.dp, 192.dp), s.contentSize)
        s.onEngineEvent(EngineEvent.ContentSize(320f, 208f, 192f))
        assertEquals(DpSize(320.dp, 192.dp), s.contentSize)
    }

    @Test fun aViewportThatIsNaNOrNotPositiveCountsAsUnknown() {
        fun heightAfter(viewport: Float?): Dp? {
            val s = state()
            s.native(176f, 160f)
            s.native(192f, 176f)
            s.onEngineEvent(EngineEvent.ContentSize(300f, 192f, viewport))
            s.native(208f, 192f)
            s.native(224f, 208f)
            return s.contentSize?.height
        }
        val unknown = heightAfter(null)
        for (viewport in listOf(Float.NaN, 0f, -5f)) assertEquals(unknown, heightAfter(viewport), "viewport $viewport")
    }

    // --- Console ---

    @Test fun theShimsConsoleSizeAndSaveProviderEventsReachTheState() = runTest {
        val s = state()
        s.bridge.connect(sink = { _, _ -> }, originAllowed = { it == origin.origin }, scope = backgroundScope)
        s.onEngineEvent(EngineEvent.PageMessage(origin.origin, BridgeCodec.encode(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") }), from = "n1")))
        val messages = mutableListOf<ConsoleMessage>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { s.console.collect { messages += it } }
        fun page(event: BridgeMessage.Event) = s.onEngineEvent(EngineEvent.PageMessage(origin.origin, BridgeCodec.encode(event, from = "n1")))
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
        s.onEngineEvent(EngineEvent.Console(ConsoleMessage(ConsoleLevel.Error, "boom", uncaught = true)))
        runCurrent()
        assertEquals(listOf(ConsoleMessage(ConsoleLevel.Error, "boom", uncaught = true)), messages)
        job.cancel()
    }

    /** Text cut as the shim cuts it: its first [BridgeShim.CONSOLE_TEXT_MAX] characters, then `…`. */
    private fun capped(c: Char) = c.toString().repeat(BridgeShim.CONSOLE_TEXT_MAX) + "…"

    @Test fun aConsoleMessageThatBypassedTheShimIsCapped() = runTest {
        val s = readyState()
        val got = mutableListOf<ConsoleMessage>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { s.console.collect { got += it } }
        s.page(BridgeMessage.Event("\$console", buildJsonObject { put("text", "x".repeat(10_000)); put("source", "s".repeat(10_000)) }))
        // Text the shim already cut passes unchanged: the host doesn't cut its marker off.
        s.page(BridgeMessage.Event("\$console", buildJsonObject { put("text", capped('y')) }))
        runCurrent()
        assertEquals(listOf(ConsoleMessage(ConsoleLevel.Log, capped('x'), source = capped('s')), ConsoleMessage(ConsoleLevel.Log, capped('y'))), got)
        job.cancel()
        s.bridge.disconnect()
    }

    @Test fun nativeConsoleTextIsCappedLikeTheShims() = runTest {
        val s = state()
        val got = mutableListOf<ConsoleMessage>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { s.console.collect { got += it } }
        s.onEngineEvent(EngineEvent.Console(ConsoleMessage(ConsoleLevel.Log, "x".repeat(1_000_000), source = "s".repeat(100_000))))
        runCurrent()
        assertEquals(listOf(ConsoleMessage(ConsoleLevel.Log, capped('x'), source = capped('s'))), got)
        job.cancel()
    }

    // --- Loading ---

    @Test fun loadingNewContentForgetsEverythingSavedForTheOld() {
        val s = state()
        s.savedEngineState = byteArrayOf(1)
        s.pageState = SavedPageState("1", origin.origin)
        s.pageSavesState = true
        s.snapshotKey = SnapshotKey("html:demo", WebProfile.Default, 1, 1, false, 1f)
        s.error = WebError.Http(null, 500)
        s.load(WebContent.Url("https://a.test/"))
        assertNull(s.savedEngineState)
        assertNull(s.pageState)
        assertFalse(s.pageSavesState)
        assertNull(s.snapshotKey)
        assertNull(s.error)
        assertEquals(WebContent.Url("https://a.test/"), s.content)
    }

    @Test fun loadingNewContentClearsWhatTheOldPageShowed() {
        val s = state()
        s.onEngineEvent(EngineEvent.UrlChanged("https://demo.invalid/"))
        s.onEngineEvent(EngineEvent.TitleChanged("Old demo"))
        s.onEngineEvent(EngineEvent.HistoryChanged(canGoBack = true, canGoForward = true))
        s.onEngineEvent(EngineEvent.Progress(0.4f))
        s.onEngineEvent(EngineEvent.ContentSize(300f, 640f))
        s.load(WebContent.Url("https://other.test/"))
        assertNull(s.url)
        assertNull(s.title)
        assertFalse(s.canGoBack)
        assertFalse(s.canGoForward)
        assertEquals(LoadingState.Idle, s.loading)
        assertNull(s.contentSize)
    }

    @Test fun newContentStartsWithoutTheOldPagesLoopVerdict() {
        val s = state()
        s.native(176f, 160f)
        s.native(192f, 176f)
        s.native(208f, 192f)
        assertEquals(192.dp, s.contentSize?.height)
        s.load(WebContent.Url("https://a.test/"))
        // Kept, the old page's verdict would hold these at 240, and its last report would make them a new streak's end.
        s.native(240f, 200f)
        s.native(260f, 210f)
        assertEquals(260.dp, s.contentSize?.height)
    }

    @Test fun loadingNewContentRetriesACrashedOrUnavailableView() {
        val s = state()
        s.phase = WebViewPhase.Crashed
        s.load(WebContent.Url("https://a.test/"))
        assertEquals(WebViewPhase.Cold, s.phase)
        s.phase = WebViewPhase.Unavailable(UnavailableReason.CreationFailed)
        s.load(WebContent.Url("https://b.test/"))
        assertEquals(WebViewPhase.Cold, s.phase)
        s.phase = WebViewPhase.Live
        s.load(WebContent.Url("https://c.test/"))
        assertEquals(WebViewPhase.Live, s.phase)
    }

    @Test fun loadingWhileLiveLeavesTheEngineToTheWebView() {
        val s = state()
        val engine = FakeWebEngine(1)
        s.owner = Owner(engine)
        var asked = 0
        s.onNeedsEvaluation = { asked++ }
        s.navigator.load("https://a.test/")
        assertEquals(WebContent.Url("https://a.test/"), s.content)
        assertTrue(engine.calls.none { it.startsWith("load:") })
        assertEquals(1, asked)
    }

    /**
     * Loading what is already shown navigates to it again, through the WebView: the engine's reload would reload the
     * page showing now, which after in-page navigation is another one.
     */
    @Test fun loadingTheSameContentLoadsItAgain() {
        val s = state()
        val engine = FakeWebEngine(1)
        s.owner = Owner(engine)
        var asked = 0
        s.onNeedsEvaluation = { asked++ }
        val key = SnapshotKey("html:demo", WebProfile.Default, 1, 1, false, 1f)
        s.snapshotKey = key
        s.onEngineEvent(EngineEvent.UrlChanged("https://demo.invalid/elsewhere"))
        s.onEngineEvent(EngineEvent.ContentSize(300f, 640f))
        s.pageState = SavedPageState("1", origin.origin)
        s.pageSavesState = true
        s.savedEngineState = byteArrayOf(1)
        val generation = s.loadGeneration
        s.load(WebContent.Html("<p/>", origin))
        assertTrue(engine.calls.isEmpty())
        assertEquals(generation + 1, s.loadGeneration)
        assertEquals(1, asked)
        // A new document starts: the old one's history, page state and save provider go, and the new one announces
        // its own provider.
        assertNull(s.savedEngineState)
        assertNull(s.pageState)
        assertFalse(s.pageSavesState)
        assertNull(s.url)
        // It is the same page, so its snapshot and size still cover it until the new document paints.
        assertEquals(key, s.snapshotKey)
        assertEquals(640.dp, s.contentSize?.height)
        s.owner = null
        s.phase = WebViewPhase.Crashed
        s.load(WebContent.Html("<p/>", origin))
        assertEquals(WebViewPhase.Cold, s.phase)
        assertEquals(generation + 2, s.loadGeneration)
        assertEquals(2, asked)
    }

    @Test fun everyLoadIsANewGeneration() {
        val s = state()
        val first = s.loadGeneration
        s.load(WebContent.Url("https://a.test/"))
        s.navigator.load("https://b.test/")
        assertEquals(first + 2, s.loadGeneration)
        // A reload isn't a load: it reloads the page showing.
        s.navigator.reload()
        assertEquals(first + 2, s.loadGeneration)
    }

    /** A Url sent with other headers (another user's credentials) is another page: nothing of the first one stays. */
    @Test fun otherHeadersForTheSameUrlAreAnotherPage() {
        val s = WebViewState(WebContent.Url("https://a.test/"))
        s.snapshotKey = SnapshotKey("url:https://a.test/", WebProfile.Default, 1, 1, false, 1f)
        s.onEngineEvent(EngineEvent.ContentSize(300f, 640f))
        s.savedEngineState = byteArrayOf(1)
        s.load(WebContent.Url("https://a.test/", headers = mapOf("Authorization" to "Bearer x")))
        assertNull(s.snapshotKey)
        assertNull(s.contentSize)
        assertNull(s.savedEngineState)
    }

    // --- Navigation ---

    @Test fun navigatorCommandsReachALiveEngine() {
        val s = state()
        val engine = FakeWebEngine(1)
        s.owner = Owner(engine)
        s.navigator.back()
        s.navigator.forward()
        s.navigator.stop()
        assertEquals(listOf("back", "forward", "stop"), engine.calls)
    }

    @Test fun reloadReachesALiveEngine() {
        val s = state()
        val engine = FakeWebEngine(1)
        s.owner = Owner(engine)
        var asked = 0
        s.onNeedsEvaluation = { asked++ }
        s.navigator.reload()
        assertEquals(listOf("reload"), engine.calls)
        assertEquals(0, asked)
    }

    /** Task 16b: the navigator reaches only the engine of the view that owns the state, through that view. */
    @Test fun navigatorCommandsGoThroughTheOwningViewOnly() {
        val s = state()
        var asked = 0
        s.onNeedsEvaluation = { asked++ }
        // No view owns the state: nothing to reach, and reload asks for activation instead.
        s.navigator.back()
        s.navigator.forward()
        s.navigator.stop()
        s.navigator.reload()
        assertNull(s.engine)
        assertEquals(1, asked)
        // A view owns it, with no engine bound yet: the same.
        s.owner = Owner(null)
        s.navigator.reload()
        assertEquals(2, asked)
        // The owner's engine, once bound, is the state's.
        val engine = FakeWebEngine(1)
        s.owner = Owner(engine)
        assertSame(engine, s.engine)
        s.navigator.reload()
        assertEquals(listOf("reload"), engine.calls)
        assertEquals(2, asked)
    }

    /** Task 16b: a load leaves the old document at once, before the WebView has even started the new one. */
    @Test fun aLoadDetachesTheBridgeFromTheOldDocumentAtOnce() = runTest {
        val sent = mutableListOf<String>()
        val s = state()
        s.bridge.connect(sink = { text, _ -> sent += text }, originAllowed = { it == origin.origin }, scope = backgroundScope)
        s.page(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") }))
        assertTrue(s.bridge.isReady)
        s.load(WebContent.Html("<p>next</p>", WebOrigin("next")))
        assertFalse(s.bridge.isReady)
        assertTrue(s.bridge.post("config", JsonPrimitive("for next")))
        // The old document speaks on, until the new one loads: none of it is heard, and nothing goes to it.
        s.page(BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "n1") }))
        runCurrent()
        assertFalse(s.bridge.isReady)
        assertTrue(sent.isEmpty(), "sent to the old document: $sent")
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

    @Test fun reloadClearsTheErrorAndRetriesAnUnavailableView() {
        val s = state()
        var asked = 0
        s.onNeedsEvaluation = { asked++ }
        s.phase = WebViewPhase.Unavailable(UnavailableReason.CreationFailed, "fake")
        s.error = WebError.Network("https://demo.invalid/", "offline")
        s.navigator.reload()
        assertEquals(WebViewPhase.Cold, s.phase)
        assertNull(s.error)
        assertEquals(1, asked)
        val engine = FakeWebEngine(1)
        s.owner = Owner(engine)
        s.error = WebError.Http("https://demo.invalid/", 500)
        s.navigator.reload()
        assertNull(s.error)
        assertEquals(listOf("reload"), engine.calls)
    }

    // --- Page memory ---

    @Test fun pageMemoryRoundTrips() {
        val s = state()
        s.snapshotKey = SnapshotKey("html:demo", WebProfile.Default, 2, 3, true, 2f)
        val bytes = byteArrayOf(7)
        s.savedEngineState = bytes
        s.pageState = SavedPageState("\"x\"", origin.origin)
        s.pageSavesState = true
        s.replay(firstView)
        val memory = s.memory()
        assertEquals(SizeReport(194f, 178f), memory.lastSizeReport)
        assertTrue(memory.growsWithViewport)
        val fresh = state()
        fresh.restoreFrom(memory)
        assertEquals(SnapshotKey("html:demo", WebProfile.Default, 2, 3, true, 2f), fresh.snapshotKey)
        assertEquals(DpSize(300.dp, 178.dp), fresh.contentSize)
        assertSame(bytes, fresh.savedEngineState)
        assertEquals(SavedPageState("\"x\"", origin.origin), fresh.pageState)
        assertTrue(fresh.pageSavesState)
        val again = fresh.memory()
        assertEquals(SizeReport(194f, 178f), again.lastSizeReport)
        assertTrue(again.growsWithViewport)
    }
}

/*
 * Size reports from a frame-level simulation of a WrapContentHeight view and the page inside it: the view eases to
 * the content size with Compose's default spring, retargeted every frame at 60 fps, and the page reports its height
 * and its viewport's height ("h:vh", CSS px) one frame later, whenever either changed. The page and the view form a
 * loop, so each sequence is what that page reports under this detector, except where noted.
 */

/** `100vh` + 16 px, changing by 1 px every second (up, then back), for 20 s. Unchecked, it crept to 530. */
private val creeping = reports(
    """
    176:160 178:162 182:166 186:170 189:173 191:175 192:176 193:177 194:178 195:178 196:179 195:179 194:178
    195:178 196:179 195:179 194:178 195:178 196:179 195:179 194:178 195:178 196:179 195:179 194:178 195:178
    196:179 195:179 194:178 195:178 196:179 195:179 194:178 195:178 196:179 195:179 194:178 195:178 196:179
    195:179 194:178 195:178 196:179 195:179 194:178 195:178 196:179
    """,
)

/** A `100vh` + 16 px hero with a log under it that grows 20 px every 200 ms, to 400 px, for 10 s. */
private val hero = reports(
    """
    176:160 178:162 182:166 186:170 189:173 191:175 192:176 193:177 194:178 214:178 217:181 221:185 226:190
    229:193 231:195 232:196 233:197 234:198 254:198 257:201 261:205 266:210 269:213 271:215 272:216 273:217
    274:218 294:218 297:221 301:225 306:230 309:233 311:235 312:236 313:237 314:238 334:238 337:241 341:245
    346:250 349:253 351:255 352:256 353:257 354:258 374:258 377:261 381:265 386:270 389:273 391:275 392:276
    393:277 394:278 414:278 417:281 421:285 426:290 429:293 431:295 432:296 433:297 434:298 454:298 457:301
    461:305 466:310 469:313 471:315 472:316 473:317 474:318 494:318 497:321 501:325 506:330 509:333 511:335
    512:336 513:337 514:338 534:338 537:341 541:345 546:350 549:353 551:355 552:356 553:357 554:358 574:358
    577:361 581:365 586:370 589:373 591:375 592:376 593:377 594:378 614:378 617:381 621:385 626:390 629:393
    631:395 632:396 633:397 634:398 654:398 657:401 661:405 666:410 669:413 671:415 672:416 673:417 674:418
    694:418 697:421 701:425 706:430 709:433 711:435 712:436 713:437 714:438 734:438 737:441 741:445 746:450
    749:453 751:455 752:456 753:457 754:458 774:458 777:461 781:465 786:470 789:473 791:475 792:476 793:477
    794:478 814:478 817:481 821:485 826:490 829:493 831:495 832:496 833:497 834:498 854:498 857:501 861:505
    866:510 869:513 871:515 872:516 873:517 874:518 894:518 897:521 901:525 906:530 909:533 911:535 912:536
    913:537 914:538 934:538 937:541 941:545 946:550 949:553 951:555 952:556 953:557 954:558 974:558 977:561
    981:565 986:570 989:573 991:575 992:576 993:577 994:578
    """,
)

/**
 * A plain page that grows from 100 to 700 px in 200 ms, its height independent of its viewport, as the previous
 * detector saw it: it caught the page and left the view at 191.
 */
private val fastPlainBefore = reports(
    """
    100:160 100:152 100:138 100:125 100:116 100:110 100:106 100:104 100:102 100:101 100:100 141:100 191:106
    241:122 291:142 341:159 391:171 441:178 491:183 541:187 591:188 641:189 691:190 700:191
    """,
)

/** The same page under this detector. */
private val fastPlain = reports(
    """
    100:160 100:152 100:138 100:125 100:116 100:110 100:106 100:104 100:102 100:101 100:100 141:100 191:106
    241:122 291:149 341:177 391:199 441:215 491:225 541:266 591:335 641:407 691:476 700:539 700:592 700:631
    700:657 700:674 700:685 700:691 700:695 700:697 700:698 700:699 700:700
    """,
)

/** A `100vh` + 16 px page's first view, from the default 160 dp: caught at 178. */
private val firstView = reports(
    """
    176:160 178:162 182:166 186:170 189:173 191:175 192:176 193:177 194:178
    """,
)

/** A new view of [firstView]'s page, which is now a static 700 px. */
private val nowTaller = reports(
    """
    700:178 700:247 700:367 700:476 700:556 700:611 700:646 700:668 700:681 700:689 700:694 700:696 700:698
    700:699 700:700
    """,
)

/** [firstView]'s page, reported twice over: by the engine and by the shim, with the same values. */
private val duplicates = reports(
    """
    176:160 176:160 178:162 178:162 182:166 182:166 186:170 186:170 189:173 189:173 191:175 191:175 192:176
    192:176 193:177 193:177 194:178 194:178
    """,
)

private fun reports(text: String): List<Pair<Float, Float>> =
    text.trim().split(Regex("\\s+")).map { report -> report.split(':').let { (h, vh) -> h.toFloat() to vh.toFloat() } }
