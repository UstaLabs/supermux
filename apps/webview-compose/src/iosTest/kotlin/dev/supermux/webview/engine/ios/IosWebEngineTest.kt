package dev.supermux.webview.engine.ios

import dev.supermux.webview.DialogResult
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.CompletableDeferred
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationType
import platform.WebKit.WKNavigationTypeBackForward
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKNavigationTypeOther
import platform.WebKit.WKUserScript
import platform.WebKit.WKWebpagePreferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine's plumbing, driven through its WebKit callbacks with fakes: no page loads in a Kotlin/Native test process.
 * What needs a real page is checked in the sample app (EngineChecks).
 */
class IosWebEngineTest {
    private val engines = mutableListOf<IosWebEngine>()
    private val logs = mutableListOf<EventLog>()
    private val setup = PageSetup(
        javaScriptEnabled = true,
        inspectable = false,
        dark = false,
        transparentBackground = false,
        userAgentSuffix = null,
        bridgeOrigins = setOf("https://c1.invalid"),
    )
    private val html = WebContent.Html("<p>a</p>", WebOrigin("c1"))
    private val mainFrame = FakeFrame(main = true, origin = FakeOrigin("https", "c1.invalid"))
    private val doc = "0123456789abcdef"
    private val ready = "{\"t\":\"event\",\"n\":\"\$ready\",\"d\":{\"nonce\":\"n1\"},\"from\":\"n1\"}"

    private fun engine(profile: WebProfile = WebProfile.Ephemeral) = IosWebEngine.create(EngineConfig(profile)).also { engines += it }

    private fun log(engine: IosWebEngine) = EventLog(engine).also { logs += it }

    private fun IosWebEngine.relay(text: String, frame: FakeFrame = mainFrame) = onRelayMessage(FakeMessage(text, frame))

    private fun IosWebEngine.scripts(): List<WKUserScript> =
        webView.configuration.userContentController.userScripts.map { it as WKUserScript }

    /** Asks [engine]'s navigation policy about [url]; true if allowed. */
    private fun decide(engine: IosWebEngine, url: String, type: WKNavigationType): Boolean {
        var policy: WKNavigationActionPolicy? = null
        engine.decideAction(FakeAction(url, type), WKWebpagePreferences()) { answer, _ -> policy = answer }
        return policy == WKNavigationActionPolicy.WKNavigationActionPolicyAllow
    }

    @AfterTest fun releaseAll() {
        logs.forEach { it.stop() }
        engines.forEach { it.release() }
    }

    @Test fun theRelayIsInstalledEverywhereAndTheShimOnlyWithJavaScript() {
        val engine = engine()
        engine.configure(setup)
        val scripts = engine.scripts()
        assertEquals(2, scripts.size)
        assertTrue(scripts.all { it.forMainFrameOnly })
        engine.configure(setup.copy(javaScriptEnabled = false))
        assertEquals(1, engine.scripts().size)
    }

    @Test fun configureAppliesTheUserAgentSuffixAndTheBackground() {
        val engine = engine()
        engine.configure(setup.copy(userAgentSuffix = "Sample/1", transparentBackground = true))
        assertTrue(engine.webView.customUserAgent?.endsWith("Sample/1") == true, engine.webView.customUserAgent)
        assertFalse(engine.webView.opaque)
        engine.configure(setup)
        assertTrue(engine.webView.customUserAgent.isNullOrEmpty())
        assertTrue(engine.webView.opaque)
    }

    @Test fun pageMessagesCountOnlyFromTheCommittedDocumentsRelay() {
        val engine = engine()
        val log = log(engine)
        engine.configure(setup)
        engine.load(html)
        engine.onCommitted(null)
        engine.relay("$doc msg $ready")
        engine.relay("$doc start")
        engine.relay("$doc msg $ready")
        engine.relay("fedcba9876543210 msg $ready")
        engine.relay("$doc msg $ready", FakeFrame(main = false, origin = FakeOrigin("https", "c1.invalid")))
        engine.onRelayMessage(FakeMessage(42, mainFrame))
        assertEquals(listOf(EngineEvent.PageMessage("https://c1.invalid", ready)), log.settle().filterIsInstance<EngineEvent.PageMessage>())
    }

    @Test fun aBundlesMessagesComeFromItsLocalOrigin() {
        val engine = engine()
        val log = log(engine)
        engine.configure(setup.copy(bridgeOrigins = setOf("https://plugin-1.invalid")))
        engine.load(WebContent.Bundle(WebOrigin("plugin-1"), { null }))
        engine.onCommitted(null)
        val frame = FakeFrame(main = true, origin = FakeOrigin("webview-bundle", "plugin-1.invalid"))
        engine.relay("$doc start", frame)
        engine.relay("$doc msg $ready", frame)
        assertEquals("https://plugin-1.invalid", log.settle().filterIsInstance<EngineEvent.PageMessage>().single().origin)
    }

    @Test fun aLoadLeavesEverythingTheOldDocumentCausedBehindEvenIfQueued() {
        val engine = engine()
        val log = log(engine)
        engine.configure(setup)
        engine.load(html)
        engine.onCommitted(null)
        engine.relay("$doc start")
        // Queued, not yet delivered, when the next load starts.
        engine.relay("$doc msg $ready")
        engine.relay("$doc paint")
        engine.load(WebContent.Html("<p>b</p>", WebOrigin("c2")))
        engine.relay("$doc msg $ready")
        val events = log.settle()
        assertTrue(events.none { it is EngineEvent.PageMessage || it == EngineEvent.FirstPaint }, "$events")
    }

    @Test fun aLoadKeepsTheViewsQueuedFocusButNothingTheOldDocumentCaused() {
        val engine = engine()
        engine.configure(setup)
        engine.load(html)
        engine.onCommitted(null)
        engine.relay("$doc start")
        // Queued while nobody collects: the touch is the view's, the $ready the old document's.
        engine.onTouch()
        engine.relay("$doc msg $ready")
        engine.load(WebContent.Html("<p>b</p>", WebOrigin("c2")))
        val events = log(engine).settle()
        assertEquals(listOf(EngineEvent.FocusChanged(true)), events.filterIsInstance<EngineEvent.FocusChanged>())
        assertTrue(events.none { it is EngineEvent.PageMessage }, "$events")
    }

    @Test fun eachDocumentPaintsOnce() {
        val engine = engine()
        val log = log(engine)
        engine.configure(setup)
        engine.load(html)
        engine.onCommitted(null)
        engine.relay("$doc start")
        engine.relay("$doc paint")
        engine.relay("$doc paint")
        assertEquals(1, log.settle().count { it == EngineEvent.FirstPaint })
    }

    @Test fun touchesAndTheWindowsFocusReportFocusOnChange() {
        val engine = engine()
        val log = log(engine)
        engine.configure(setup)
        engine.load(html)
        engine.onCommitted(null)
        engine.relay("$doc start")
        engine.onTouch()
        engine.onTouch()
        engine.relay("$doc blur")
        engine.relay("$doc focus")
        assertEquals(
            listOf(EngineEvent.FocusChanged(true), EngineEvent.FocusChanged(false), EngineEvent.FocusChanged(true)),
            log.settle().filterIsInstance<EngineEvent.FocusChanged>(),
        )
    }

    @Test fun aRendererCrashMakesTheEngineUnusableForGood() {
        val engine = engine()
        val log = log(engine)
        engine.onRendererGone()
        assertFalse(engine.isUsable)
        assertEquals(listOf<EngineEvent>(EngineEvent.Crashed), log.settle())
    }

    @Test fun afterReleaseEveryMemberDoesNothing() {
        val engine = engine()
        engine.configure(setup)
        engine.release()
        engine.configure(setup)
        engine.load(html)
        engine.postMessage("{}", "https://c1.invalid")
        engine.reload()
        engine.goBack()
        engine.stop()
        engine.release()
        assertFalse(engine.isUsable)
        assertEquals(0, engine.scripts().size)
    }

    @Test fun onlyNavigationsThePageStartsAreAskedAbout() {
        val engine = engine()
        val client = RecordingClient(navigation = NavigationDecision.Allow)
        engine.client = client
        engine.configure(setup)
        engine.load(html)
        assertTrue(decide(engine, "https://c1.invalid/", WKNavigationTypeOther), "the engine's own load")
        assertTrue(decide(engine, "https://example.com/", WKNavigationTypeLinkActivated))
        client.navigation = NavigationDecision.Block
        assertFalse(decide(engine, "https://example.com/", WKNavigationTypeLinkActivated))
        assertTrue(decide(engine, "https://example.com/b", WKNavigationTypeBackForward), "history isn't asked about")
        assertFalse(decide(engine, WebKitUrls.FLOOR_URL, WKNavigationTypeBackForward), "the floor is never reached")
        assertFalse(decide(engine, "webview-bundle://plugin-9.invalid/", WKNavigationTypeLinkActivated))
        assertEquals(
            listOf<Any>(
                NavigationRequest("https://example.com/", isMainFrame = true, isUserGesture = false),
                NavigationRequest("https://example.com/", isMainFrame = true, isUserGesture = false),
            ),
            client.asked,
        )
    }

    @Test fun aTouchMakesTheNextNavigationTheUsers() {
        val engine = engine()
        val client = RecordingClient(navigation = NavigationDecision.Allow)
        engine.client = client
        engine.configure(setup)
        engine.onTouch()
        decide(engine, "https://example.com/", WKNavigationTypeLinkActivated)
        assertEquals(NavigationRequest("https://example.com/", isMainFrame = true, isUserGesture = true), client.asked.single())
    }

    @Test fun aNewWindowAsksTheClient() {
        val engine = engine()
        val client = RecordingClient()
        engine.client = client
        engine.onNewWindow(FakeAction("https://example.com/popup", WKNavigationTypeLinkActivated, target = null))
        assertEquals(listOf<Any>(NewWindowRequest("https://example.com/popup", isUserGesture = false)), client.asked)
    }

    @Test fun dialogsGetTheClientsAnswer() {
        val engine = engine()
        val client = RecordingClient(dialog = CompletableDeferred(DialogResult.Confirmed))
        engine.client = client
        var confirmed: Boolean? = null
        engine.onConfirm("Sure?", mainFrame) { confirmed = it }
        assertTrue(pumpUntil { confirmed != null })
        assertEquals(true, confirmed)
        assertEquals(WebDialog.Confirm("Sure?", "https://c1.invalid/"), client.asked.single())
    }

    @Test fun aDialogTheClientNeverAnswersGetsTheSafeAnswerAtRelease() {
        val engine = engine()
        engine.client = RecordingClient(dialog = CompletableDeferred())
        var confirmed: Boolean? = null
        engine.onConfirm("Sure?", mainFrame) { confirmed = it }
        pumpUntil(0.2) { false }
        assertEquals(null, confirmed)
        engine.release()
        assertEquals(false, confirmed)
    }

    /** [engine] with a committed, started document of [html], as a view leaves it: its events heard, then its log gone. */
    private fun parkedPage(engine: IosWebEngine, painted: Boolean): List<EngineEvent> {
        // This process has no WebContent process to give the page: iOS 16's WebKit reports the one the load tried to
        // start as gone, which would crash the engine. The test drives WebKit's callbacks itself.
        engine.webView.navigationDelegate = null
        val first = EventLog(engine)
        engine.configure(setup)
        engine.load(html)
        engine.onCommitted(null)
        engine.relay("$doc start")
        if (painted) engine.relay("$doc paint")
        val heard = first.settle()
        assertFalse(EngineEvent.Crashed in heard, "WebKit reported the page's process gone: $heard")
        first.stop()
        engine.client = null
        engine.setVisible(false)
        return heard
    }

    @Test fun anAdoptingViewHearsThePageAsItIsWithNoNewDocument() {
        val engine = engine()
        val heard = parkedPage(engine, painted = false)
        val commit = heard.filterIsInstance<EngineEvent.UrlChanged>().single()
        val log = log(engine)
        engine.replayState()
        assertEquals(
            listOf(commit, EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false), EngineEvent.TitleChanged(null)),
            log.settle(),
            "the commit, history and title again, and nothing a navigation reports",
        )
    }

    @Test fun aPaintedPagesReplayedFirstPaintWaitsUntilItHasDrawnInAWindow() {
        val engine = engine()
        val heard = parkedPage(engine, painted = true)
        assertTrue(EngineEvent.FirstPaint in heard)
        val log = log(engine)
        engine.replayState()
        val replayed = log.settle()
        assertTrue(replayed.any { it is EngineEvent.UrlChanged }, "$replayed")
        // The test process has no window: the page never draws again here (the engine checks see it in the app).
        assertFalse(EngineEvent.FirstPaint in replayed, "$replayed")
    }

    @Test fun thereIsNothingToReplayWithoutACommittedDocument() {
        val engine = engine()
        val log = log(engine)
        engine.replayState()
        engine.configure(setup)
        engine.load(html)
        // The load is under way: its commit reports the page, not a replay.
        engine.replayState()
        assertTrue(log.settle().none { it is EngineEvent.UrlChanged || it is EngineEvent.HistoryChanged }, "${log.events}")
        engine.release()
        engine.replayState()
        engine.setVisible(true)
        engine.overlaysAtSnapshot(OverlayCutouts.Hidden)
        assertTrue(log.settle().none { it is EngineEvent.UrlChanged }, "${log.events}")
    }

    @Test fun withNoClientADialogIsDismissedAtOnce() {
        val engine = engine()
        var text: String? = "unset"
        engine.onPrompt("Name?", "x", mainFrame) { text = it }
        assertEquals(null, text)
    }
}
