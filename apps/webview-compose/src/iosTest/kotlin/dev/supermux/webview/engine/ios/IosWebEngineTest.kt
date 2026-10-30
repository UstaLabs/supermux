package dev.supermux.webview.engine.ios

import dev.supermux.webview.DialogResult
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.CompletableDeferred
import platform.CoreGraphics.CGPointMake
import platform.UIKit.UIWindow
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationType
import platform.WebKit.WKNavigationTypeBackForward
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKNavigationTypeOther
import platform.WebKit.WKNavigationTypeReload
import platform.WebKit.WKUserScript
import platform.WebKit.WKWebpagePreferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The engine's plumbing, driven through its WebKit callbacks with fakes: no page loads in a Kotlin/Native test process.
 * What needs a real page is checked in the sample app (EngineChecks).
 */
class IosWebEngineTest {
    private val engines = mutableListOf<IosWebEngine>()
    private val logs = mutableListOf<EventLog>()
    private val windows = mutableListOf<UIWindow>()
    private val setup = PageSetup(
        javaScriptEnabled = true,
        inspectable = false,
        dark = false,
        transparentBackground = false,
        userAgentSuffix = null,
        bridgeOrigins = setOf("https://c1.invalid"),
    )
    private val html = WebContent.Html("<p>a</p>", WebOrigin("c1"))
    private val mainFrame = TestFrame()
    private val doc = "0123456789abcdef"
    private val ready = "{\"t\":\"event\",\"n\":\"\$ready\",\"d\":{\"nonce\":\"n1\"},\"from\":\"n1\"}"

    private fun engine(profile: WebProfile = WebProfile.Ephemeral, ticks: Ticks = ManualTicks()) =
        IosWebEngine.create(EngineConfig(profile), ticks).also { engines += it }

    /**
     * [engine]'s surface in a window, kept until the test ends. The test drives WebKit's callbacks itself: in a window,
     * iOS 16's WebKit tries to start a WebContent process, which this process can't have, and would report it gone.
     */
    private fun onScreen(engine: IosWebEngine): EngineContainerView {
        engine.webView.navigationDelegate = null
        val (window, container) = inWindow(engine)
        windows += window
        return container
    }

    /**
     * [engine] with the document of [html] committed and started, its WebKit callbacks driven by the test: this process
     * has no WebContent process to give a page (iOS 16's WebKit reports the one a load tried to start as gone).
     */
    private fun committed(engine: IosWebEngine) {
        engine.webView.navigationDelegate = null
        engine.configure(setup)
        engine.load(html)
        engine.onCommitted(null)
        engine.relay("$doc start")
    }

    private fun log(engine: IosWebEngine) = EventLog(engine).also { logs += it }

    private fun IosWebEngine.relay(text: String, frame: FrameInfo = mainFrame) = onRelayMessage(text, frame)

    private fun IosWebEngine.scripts(): List<WKUserScript> =
        webView.configuration.userContentController.userScripts.map { it as WKUserScript }

    /**
     * Asks [engine]'s navigation policy about [url]; true if allowed. [sent]: the request is one WebKit has sent, with
     * the network's own headers, as a server redirect's is.
     */
    private fun decide(engine: IosWebEngine, url: String, type: WKNavigationType, sent: Boolean = false): Boolean {
        var policy: WKNavigationActionPolicy? = null
        val headers = if (sent) mapOf("Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Dest" to "document") else emptyMap()
        engine.decideAction(TestAction(url, type, headers = headers), WKWebpagePreferences()) { answer, _ -> policy = answer }
        return policy == WKNavigationActionPolicy.WKNavigationActionPolicyAllow
    }

    /**
     * The order WebKit made its callbacks in for a server redirect of the app's load, on macOS 26 against a local server
     * (the same WebKit): the load's policy, its start, the redirect's policy (its request carrying the network's
     * headers), then the commit. A Kotlin/Native test process loads no page, so the test makes them.
     */
    @Test fun theServerRedirectsOfItsOwnLoadAreNotAskedAboutUntilItCommits() {
        val engine = engine()
        val client = RecordingClient(navigation = NavigationDecision.Block)
        engine.client = client
        engine.webView.navigationDelegate = null
        engine.configure(setup)
        engine.load(WebContent.Url("https://example.com"))
        assertTrue(decide(engine, "https://example.com/", WKNavigationTypeOther), "the engine's own load")
        engine.onStarted(null)
        assertTrue(decide(engine, "https://www.example.com/", WKNavigationTypeOther, sent = true), "the server's redirect of it")
        assertFalse(decide(engine, "https://evil.example/", WKNavigationTypeOther), "the old document's navigation meanwhile")
        engine.onCommitted(null)
        assertFalse(decide(engine, "https://www.example.com/next", WKNavigationTypeOther, sent = true), "the page's, once committed")
        assertEquals(
            listOf<Any>(
                NavigationRequest("https://evil.example/", isMainFrame = true, isUserGesture = false),
                NavigationRequest("https://www.example.com/next", isMainFrame = true, isUserGesture = false),
            ),
            client.asked,
        )
    }

    /** To the web only, as on Android: a redirect of the engine's own load elsewhere ends the load, with an error. */
    @Test fun aRedirectOfItsOwnLoadOffTheWebEndsTheLoadWithAnError() {
        val engine = engine()
        val log = log(engine)
        val client = RecordingClient(navigation = NavigationDecision.Allow)
        engine.client = client
        engine.webView.navigationDelegate = null
        engine.configure(setup)
        engine.load(WebContent.Url("https://example.com/app"))
        assertTrue(decide(engine, "https://example.com/app", WKNavigationTypeOther))
        engine.onStarted(null)
        assertFalse(decide(engine, "myapp://callback?code=1", WKNavigationTypeOther, sent = true))
        assertTrue(client.asked.isEmpty(), "the app's load is never the client's to decide, nor its redirects: ${client.asked}")
        assertEquals(
            listOf(EngineEvent.Error(WebError.BlockedByPolicy("myapp://callback?code=1"))),
            log.settle().filterIsInstance<EngineEvent.Error>(),
        )
    }

    /** As on Android, the app's reload too: its redirects go to the web, and nowhere else. */
    @Test fun aRedirectOfItsOwnReloadOffTheWebEndsItWithAnError() {
        val engine = engine()
        val log = log(engine)
        val client = RecordingClient(navigation = NavigationDecision.Allow)
        engine.client = client
        engine.webView.navigationDelegate = null
        engine.configure(setup)
        engine.load(WebContent.Url("https://example.com/b"))
        assertTrue(decide(engine, "https://example.com/b", WKNavigationTypeOther))
        engine.onCommitted(null)
        engine.reload()
        assertTrue(decide(engine, "https://example.com/b", WKNavigationTypeReload), "the engine's own reload")
        engine.onStarted(null)
        assertTrue(decide(engine, "https://login.example.com/", WKNavigationTypeReload, sent = true), "its redirect to the web")
        assertFalse(decide(engine, "myapp://callback", WKNavigationTypeReload, sent = true), "and off it")
        assertTrue(client.asked.isEmpty(), "the app's reload is never the client's to decide, nor its redirects: ${client.asked}")
        assertEquals(
            listOf(EngineEvent.Error(WebError.BlockedByPolicy("myapp://callback"))),
            log.settle().filterIsInstance<EngineEvent.Error>(),
        )
    }

    @AfterTest fun releaseAll() {
        logs.forEach { it.stop() }
        engines.forEach { it.release() }
        windows.clear()
        collectGarbage()
    }

    @Test fun theRelayAndItsInputInEveryFrameAreInstalledAlwaysAndTheShimOnlyWithJavaScript() {
        val engine = engine()
        engine.configure(setup)
        // The relay in the main frame, the user's input in the page's other frames, the shim in the main frame.
        assertEquals(listOf(true, false, true), engine.scripts().map { it.forMainFrameOnly })
        engine.configure(setup.copy(javaScriptEnabled = false))
        assertEquals(listOf(true, false), engine.scripts().map { it.forMainFrameOnly })
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
        engine.relay("$doc msg $ready", TestFrame(isMainFrame = false))
        engine.onRelayMessage(42, mainFrame)
        assertEquals(listOf(EngineEvent.PageMessage("https://c1.invalid", ready)), log.settle().filterIsInstance<EngineEvent.PageMessage>())
    }

    @Test fun aBundlesMessagesComeFromItsLocalOrigin() {
        val engine = engine()
        val log = log(engine)
        engine.configure(setup.copy(bridgeOrigins = setOf("https://plugin-1.invalid")))
        engine.load(WebContent.Bundle(WebOrigin("plugin-1"), { null }))
        engine.onCommitted(null)
        val frame = TestFrame(securityOrigin = SecurityOriginInfo("webview-bundle", "plugin-1.invalid", 0))
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
        // Queued while nobody collects: the focus is the view's, the $ready the old document's.
        engine.relay("$doc focus")
        engine.relay("$doc msg $ready")
        engine.load(WebContent.Html("<p>b</p>", WebOrigin("c2")))
        val events = log(engine).settle()
        assertEquals(listOf(EngineEvent.FocusChanged(true)), events.filterIsInstance<EngineEvent.FocusChanged>())
        assertTrue(events.none { it is EngineEvent.PageMessage }, "$events")
    }

    @Test fun eachDocumentPaintsOnce() {
        val ticks = ManualTicks()
        val engine = engine(ticks = ticks)
        onScreen(engine)
        val log = log(engine)
        committed(engine)
        engine.relay("$doc paint")
        engine.relay("$doc paint")
        repeat(4) { ticks.tick() }
        assertEquals(1, log.settle().count { it == EngineEvent.FirstPaint })
    }

    @Test fun aDocumentsFirstPaintWaitsUntilItsWindowHasShownItForTwoDisplayTicks() {
        val ticks = ManualTicks()
        val engine = engine(ticks = ticks)
        onScreen(engine)
        val log = log(engine)
        committed(engine)
        engine.relay("$doc paint")
        assertFalse(EngineEvent.FirstPaint in log.settle(), "the page has content, not yet on screen under the cover")
        ticks.tick()
        assertFalse(EngineEvent.FirstPaint in log.settle(), "one display tick")
        ticks.tick()
        assertEquals(1, log.settle().count { it == EngineEvent.FirstPaint })
    }

    @Test fun aDocumentWithContentOffScreenSaysItPaintedOnlyOnceShownInAWindow() {
        val ticks = ManualTicks()
        val engine = engine(ticks = ticks)
        val log = log(engine)
        committed(engine)
        engine.relay("$doc paint")
        repeat(3) { ticks.tick() }
        assertFalse(EngineEvent.FirstPaint in log.settle(), "out of any window")
        onScreen(engine)
        ticks.tick()
        ticks.tick()
        assertEquals(1, log.settle().count { it == EngineEvent.FirstPaint })
    }

    @Test fun aDocumentThatLeavesItsWindowBeforeItIsShownThereWaitsAgainOnceBack() {
        val ticks = ManualTicks()
        val engine = engine(ticks = ticks)
        val container = onScreen(engine)
        val window = container.window
        val log = log(engine)
        committed(engine)
        engine.relay("$doc paint")
        ticks.tick()
        container.removeFromSuperview()
        repeat(3) { ticks.tick() }
        assertFalse(EngineEvent.FirstPaint in log.settle(), "it left its window")
        window?.addSubview(container)
        ticks.tick()
        ticks.tick()
        assertEquals(1, log.settle().count { it == EngineEvent.FirstPaint })
    }

    @Test fun aDocumentThatNeverHasContentIsShownAfterThreeSeconds() {
        val ticks = ManualTicks()
        val engine = engine(ticks = ticks)
        onScreen(engine)
        val log = log(engine)
        committed(engine)
        pumpUntil(2.5) { false }
        repeat(3) { ticks.tick() }
        assertFalse(EngineEvent.FirstPaint in log.settle(), "the cover stays up while the page may still paint")
        pumpUntil(1.0) { false }
        ticks.tick()
        ticks.tick()
        assertEquals(1, log.settle().count { it == EngineEvent.FirstPaint })
    }

    @Test fun theUsersGesturesAndTheWindowsFocusReportFocusOnChange() {
        val engine = engine()
        val log = log(engine)
        engine.configure(setup)
        engine.load(html)
        engine.onCommitted(null)
        engine.relay("$doc start")
        engine.relay("$doc gesture")
        engine.relay("$doc gesture")
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

    /** [engine], with a client that allows every navigation and records it, and a committed, started document. */
    private fun asking(engine: IosWebEngine): RecordingClient {
        val client = RecordingClient(navigation = NavigationDecision.Allow)
        engine.client = client
        committed(engine)
        return client
    }

    /** Whether the client heard each navigation it was asked about as the user's, in order. */
    private fun RecordingClient.gestures(): List<Boolean> = asked.filterIsInstance<NavigationRequest>().map { it.isUserGesture }

    /**
     * Compose hit-tests the page's view for every touch on it, a drag it keeps to scroll the list included (CMP 1.12.2,
     * Cooperative mode): a touch there says nothing of the page. A page that navigates by itself while the user scrolls
     * the chat over it doesn't get to leave the app.
     */
    @Test fun aScrollOverThePageIsNoGestureAndNoUseOfIt() {
        val engine = engine()
        val container = onScreen(engine)
        val log = log(engine)
        val client = asking(engine)
        assertNotNull(container.hitTest(CGPointMake(10.0, 10.0), TouchEvent()), "the touch lands on the page's view")
        decide(engine, "https://example.com/", WKNavigationTypeOther)
        assertEquals(listOf(false), client.gestures())
        assertTrue(log.settle().none { it is EngineEvent.FocusChanged }, "${log.events}")
    }

    /** The relay saw a trusted tap, click or key in the page: the page's next request to leave is the user's, once. */
    @Test fun aGestureInThePageCountsForItsNextRequestOnly() {
        val engine = engine()
        val client = asking(engine)
        engine.relay("$doc gesture")
        decide(engine, "https://example.com/", WKNavigationTypeLinkActivated)
        decide(engine, "https://example.com/again", WKNavigationTypeOther)
        assertEquals(listOf(true, false), client.gestures())
    }

    @Test fun aGestureCountsOnlyForTheDocumentThatSawIt() {
        val engine = engine()
        val client = asking(engine)
        engine.relay("$doc gesture")
        // The tap's own navigation committed: the next document never had the user's gesture.
        engine.onCommitted(null)
        engine.relay("fedcba9876543210 start")
        decide(engine, "https://example.com/", WKNavigationTypeOther)
        assertEquals(listOf(false), client.gestures())
    }

    @Test fun anOldDocumentsGestureIsNotTheCurrentOnes() {
        val engine = engine()
        val client = asking(engine)
        engine.relay("fedcba9876543210 gesture")
        decide(engine, "https://example.com/", WKNavigationTypeOther)
        assertEquals(listOf(false), client.gestures())
    }

    /** A tap in a frame of the page (a video's link, say) is the user's too: its relay has no token of the page's. */
    @Test fun aGestureInASubframeCountsForThePage() {
        val engine = engine()
        val client = asking(engine)
        engine.relay("0000000000000000 gesture", TestFrame(isMainFrame = false))
        decide(engine, "https://example.com/", WKNavigationTypeLinkActivated)
        assertEquals(listOf(true), client.gestures())
    }

    /** A scroll of the user's own in the page is use of it, as a tap is, but no gesture: it never starts a navigation. */
    @Test fun theUsersOwnScrollMarksThePageUsedButIsNoGesture() {
        val engine = engine()
        val log = log(engine)
        val client = asking(engine)
        engine.relay("$doc use")
        decide(engine, "https://example.com/", WKNavigationTypeOther)
        assertEquals(listOf(false), client.gestures())
        assertEquals(listOf(EngineEvent.FocusChanged(true)), log.settle().filterIsInstance<EngineEvent.FocusChanged>())
    }

    @Test fun aNewWindowAfterAGestureIsTheUsersOnce() {
        val engine = engine()
        val client = asking(engine)
        engine.relay("$doc gesture")
        engine.onNewWindow(TestAction("https://example.com/popup", WKNavigationTypeLinkActivated, targetFrame = null))
        engine.onNewWindow(TestAction("https://example.com/again", WKNavigationTypeOther, targetFrame = null))
        assertEquals(listOf(true, false), client.asked.filterIsInstance<NewWindowRequest>().map { it.isUserGesture })
    }

    @Test fun aNewWindowAsksTheClient() {
        val engine = engine()
        val client = RecordingClient()
        engine.client = client
        engine.onNewWindow(TestAction("https://example.com/popup", WKNavigationTypeLinkActivated, targetFrame = null))
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

    /**
     * [engine] with a committed, started document of [html], shown in a window if [painted], as a view leaves it: its
     * events heard, then its log gone, and its surface out of the window.
     */
    private fun parkedPage(engine: IosWebEngine, ticks: ManualTicks, painted: Boolean): List<EngineEvent> {
        val container = onScreen(engine)
        val first = EventLog(engine)
        committed(engine)
        if (painted) {
            engine.relay("$doc paint")
            ticks.tick()
            ticks.tick()
        }
        val heard = first.settle()
        engine.detach(container)
        assertFalse(EngineEvent.Crashed in heard, "WebKit reported the page's process gone: $heard")
        first.stop()
        engine.client = null
        engine.setVisible(false)
        return heard
    }

    @Test fun anAdoptingViewHearsThePageAsItIsWithNoNewDocument() {
        val ticks = ManualTicks()
        val engine = engine(ticks = ticks)
        val heard = parkedPage(engine, ticks, painted = false)
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
        val ticks = ManualTicks()
        val engine = engine(ticks = ticks)
        val heard = parkedPage(engine, ticks, painted = true)
        assertTrue(EngineEvent.FirstPaint in heard)
        val log = log(engine)
        engine.replayState()
        val replayed = log.settle()
        assertTrue(replayed.any { it is EngineEvent.UrlChanged }, "$replayed")
        // The test process has no window: the page never draws again here (the engine checks see it in the app).
        assertFalse(EngineEvent.FirstPaint in replayed, "$replayed")
    }

    /**
     * A parked page that floods its bridge: past what the queue holds while no view subscribes, the engine goes the way
     * of its web content process ending (unusable), so the pool discards it rather than hand it to a view.
     */
    @Test fun aParkedPageThatFloodsItsBridgeCrashes() {
        val ticks = ManualTicks()
        val engine = engine(ticks = ticks)
        parkedPage(engine, ticks, painted = true)
        // The view's subscription ends on the main queue.
        drainMainQueue()
        val tick = "{\"t\":\"event\",\"n\":\"tick\",\"from\":\"n1\"}"
        repeat(1_024) { engine.relay("$doc msg $tick") }
        assertTrue(engine.isUsable, "within what a parked page may hold")
        engine.relay("$doc msg $tick")
        assertFalse(engine.isUsable)
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
