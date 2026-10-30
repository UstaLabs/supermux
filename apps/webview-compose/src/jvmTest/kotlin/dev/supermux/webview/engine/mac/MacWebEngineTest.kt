package dev.supermux.webview.engine.mac

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.SavedWebKitState
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.computeCutouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's plumbing, driven as WebKit and the glue would drive it, through `FakePageGlue` on the test's virtual
 * clock: no WebKit runs here. What needs real pages is checked in the sample app (Tasks 13–16).
 */
class MacWebEngineTest {
    private val setup = PageSetup(
        javaScriptEnabled = true,
        inspectable = false,
        dark = false,
        transparentBackground = false,
        userAgentSuffix = null,
        bridgeOrigins = setOf("https://c1.invalid"),
    )
    private val html = WebContent.Html("<p>a</p>", WebOrigin("c1"))
    private val doc = "0123456789abcdef"
    private val ready = "{\"t\":\"event\",\"n\":\"\$ready\",\"d\":{\"nonce\":\"n1\"},\"from\":\"n1\"}"

    private class RecordingClient(
        var navigation: NavigationDecision = NavigationDecision.Block,
        var newWindow: NewWindowDecision = NewWindowDecision.Block,
        val dialog: CompletableDeferred<DialogResult> = CompletableDeferred(DialogResult.Dismissed),
    ) : EngineClient {
        val asked = mutableListOf<Any>()
        override fun onNavigation(request: NavigationRequest): NavigationDecision = navigation.also { asked += request }

        override suspend fun onDialog(dialog: WebDialog): DialogResult {
            asked += dialog
            return this.dialog.await()
        }

        override suspend fun onFileChooser(request: FileChooserRequest): List<String>? = listOf("/tmp/a.txt").also { asked += request }

        override suspend fun onPermission(request: PermissionRequest): Boolean = true.also { asked += request }

        override suspend fun onDownload(request: DownloadRequest): Boolean = false.also { asked += request }

        override fun onNewWindow(request: NewWindowRequest): NewWindowDecision = newWindow.also { asked += request }
    }

    /**
     * An engine on [scope]'s virtual clock, its events collected, played by hand as WebKit would play it. [inList]: its
     * surface is an embed in a scrolling list, not a pane.
     */
    private inner class Rig(val scope: TestScope, profile: WebProfile = WebProfile.Ephemeral, inList: Boolean = false) {
        val glue = FakePageGlue()
        val released = mutableListOf<MacWebEngine>()
        val engine = MacWebEngine(
            config = EngineConfig(profile),
            glue = glue,
            scripts = WebKitScripts("webview", "out-1", "in-1"),
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
            windowHandle = { 7L },
            onReleased = { released += it },
        )
        val node = MacSurfaceNode(engine, inList = inList)
        val events = mutableListOf<EngineEvent>()

        /** The subscription, as the runtime's view holds it: cancelled when the view parks the page. */
        val collector: Job = scope.backgroundScope.launch { engine.events.collect { events += it } }
        private val answered = mutableSetOf<Long>()

        fun page(url: String?, back: List<String> = emptyList(), title: String? = null, loading: Boolean = false) =
            WebKitPage(url, title, back, emptyList(), loading)

        fun send(event: GlueEvent) {
            engine.onGlueEvent(event)
            scope.runCurrent()
        }

        /** Answers the newest call of [kind] that carries an id with [result]. */
        fun answer(kind: String, result: (Long) -> GlueEvent) {
            scope.runCurrent()
            val id = glue.ids(kind).last()
            answered += id
            send(result(id))
        }

        /** The ticks asked for and not answered yet. */
        fun ticksWaiting(): List<Long> = glue.ids("tick").filter { it !in answered }

        fun tick(inWindow: Boolean = true) = answer("tick") { GlueEvent.Tick(it, inWindow) }

        fun repainted() = answer("repaint") { GlueEvent.Result(it, true, null, null) }

        fun relay(epoch: Int, body: String, mainFrame: Boolean = true) =
            send(GlueEvent.Relay(epoch, mainFrame, body, "https", "c1.invalid", 0))

        /** The slot laid out at these window px, [visible] of it (all of it by default), at the window's [scale]. */
        fun place(
            x: Float = 0f,
            y: Float = 0f,
            width: Float = 640f,
            height: Float = 480f,
            visible: Rect? = null,
            scale: Float = 2f,
        ): Boolean {
            val bounds = Rect(x, y, x + width, y + height)
            return engine.surfacePlaced(node, 7L, bounds, visible ?: bounds, scale).also { scope.runCurrent() }
        }

        /** The page's snapshot, as the runtime takes it: [width] × [height] px of one colour; it is the motion cover too. */
        suspend fun snapshot(width: Int = 640, height: Int = 480) {
            val shot = scope.async { engine.snapshot() }
            answer("snapshot") { GlueEvent.Snapshot(it, width, height, ByteArray(width * height * 4) { 0x7f }) }
            checkNotNull(shot.await()) { "no snapshot" }
        }

        /** The lease's first document (epoch 1), painted and shown in a placed, still surface; then a second goes by. */
        fun live() {
            engine.configure(setup)
            engine.load(html)
            send(GlueEvent.Committed(1, page(html.origin.url, loading = true), 0.5f))
            relay(1, "$doc start")
            relay(1, "$doc paint")
            engine.surfaceAttached(node)
            place()
            repainted()
            tick()
            tick()
            tick()
            tick()
            check(EngineEvent.FirstPaint in events) { "not live: $events" }
            scope.advanceTimeBy(1_000)
            scope.runCurrent()
        }
    }

    private fun policy(question: Long, url: String, kind: NavigationKind = NavigationKind.Other, fromNetwork: Boolean = false) =
        GlueEvent.Policy(question, url, mainFrame = true, opensWindow = false, download = false, kind = kind, fromNetwork = fromNetwork)

    /** As WebKit ran it on macOS 26 against a local server: the load, its redirect's policy, the redirect, the commit. */
    @Test fun theServerRedirectsOfItsOwnLoadAreNotAskedAboutUntilItCommits() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(navigation = NavigationDecision.Block)
        rig.engine.client = client
        rig.engine.configure(setup)
        rig.engine.load(WebContent.Url("https://example.com"))
        rig.send(policy(1, "https://example.com/"))
        assertEquals("answerPolicy 1 true", rig.glue.last("answerPolicy"))
        rig.send(GlueEvent.Started(1, "https://example.com/"))
        rig.send(policy(2, "https://www.example.com/", fromNetwork = true))
        assertEquals("answerPolicy 2 true", rig.glue.last("answerPolicy"), "the server's redirect of the engine's own load")
        // The old document navigates meanwhile: its request hasn't been sent, so it is the page's, and asked about.
        rig.send(policy(3, "https://evil.example/"))
        assertEquals("answerPolicy 3 false", rig.glue.last("answerPolicy"))
        rig.send(GlueEvent.Committed(1, rig.page("https://www.example.com/", loading = true), 0.5f))
        // Committed: what follows is the page's, redirects of its own navigations included.
        rig.send(policy(4, "https://www.example.com/next", fromNetwork = true))
        assertEquals("answerPolicy 4 false", rig.glue.last("answerPolicy"))
        assertEquals(
            listOf<Any>(
                NavigationRequest("https://evil.example/", isMainFrame = true, isUserGesture = false),
                NavigationRequest("https://www.example.com/next", isMainFrame = true, isUserGesture = false),
            ),
            client.asked,
        )
        assertEquals(listOf(EngineEvent.UrlChanged("https://www.example.com/")), rig.events.filterIsInstance<EngineEvent.UrlChanged>())
    }

    /** To the web only, as on Android: a redirect elsewhere ends the load, with an error the runtime shows. */
    @Test fun aRedirectOfItsOwnLoadOffTheWebEndsTheLoadWithAnError() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(navigation = NavigationDecision.Allow)
        rig.engine.client = client
        rig.engine.configure(setup)
        rig.engine.surfaceAttached(rig.node)
        rig.place()
        rig.repainted()
        rig.tick()
        rig.tick()
        rig.engine.load(WebContent.Url("https://example.com/app"))
        rig.send(policy(1, "https://example.com/app"))
        rig.send(GlueEvent.Started(1, "https://example.com/app"))
        rig.send(policy(2, "myapp://callback?code=1", fromNetwork = true))
        assertEquals("answerPolicy 2 false", rig.glue.last("answerPolicy"))
        assertTrue(client.asked.isEmpty(), "the app's load is never the client's to decide, nor its redirects: ${client.asked}")
        assertEquals(
            listOf(EngineEvent.Error(WebError.BlockedByPolicy("myapp://callback?code=1"))),
            rig.events.filterIsInstance<EngineEvent.Error>(),
        )
        assertTrue(rig.glue.lastShown, "the runtime shows the error: the page's cover is lifted, as for any failed load")
        // WebKit's own report of the refused navigation adds nothing.
        rig.send(GlueEvent.Failed(1, "myapp://callback?code=1", "WebKitErrorDomain", 102, "Frame load interrupted"))
        assertEquals(1, rig.events.filterIsInstance<EngineEvent.Error>().size)
        assertTrue(rig.events.none { it is EngineEvent.UrlChanged }, "nothing committed: ${rig.events}")
    }

    /** As on Android, the app's reload and its back and forward too: their redirects go to the web, and nowhere else. */
    @Test fun aRedirectOfItsOwnReloadOrBackOffTheWebEndsItWithAnError() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(navigation = NavigationDecision.Allow)
        rig.engine.client = client
        rig.engine.configure(setup)
        rig.engine.load(WebContent.Url("https://example.com/b"))
        rig.send(policy(1, "https://example.com/b"))
        rig.send(GlueEvent.Committed(1, rig.page("https://example.com/b", back = listOf("https://example.com/a")), 0.5f))
        // The reload: redirected to a login page, followed; from there off the web, refused, and the reload ends.
        rig.engine.reload()
        rig.send(policy(2, "https://example.com/b", NavigationKind.Reload))
        rig.send(GlueEvent.Started(2, "https://example.com/b"))
        rig.send(policy(3, "https://login.example.com/", NavigationKind.Reload, fromNetwork = true))
        assertEquals("answerPolicy 3 true", rig.glue.last("answerPolicy"))
        rig.send(policy(4, "myapp://callback", NavigationKind.Reload, fromNetwork = true))
        assertEquals("answerPolicy 4 false", rig.glue.last("answerPolicy"))
        // Back: off the web at once.
        rig.engine.goBack()
        rig.send(policy(5, "https://example.com/a", NavigationKind.BackForward))
        assertEquals("answerPolicy 5 true", rig.glue.last("answerPolicy"))
        rig.send(policy(6, "about:blank", NavigationKind.BackForward, fromNetwork = true))
        assertEquals("answerPolicy 6 false", rig.glue.last("answerPolicy"))
        assertTrue(client.asked.isEmpty(), "the app's navigations are never the client's to decide: ${client.asked}")
        assertEquals(
            listOf(EngineEvent.Error(WebError.BlockedByPolicy("myapp://callback")), EngineEvent.Error(WebError.BlockedByPolicy("about:blank"))),
            rig.events.filterIsInstance<EngineEvent.Error>(),
        )
    }

    @Test fun warmUpLoadsTheFloorAndLearnsTheUserAgent() = runTest {
        val rig = Rig(this)
        val warm = async { rig.engine.warmUp() }
        runCurrent()
        assertEquals("loadUrl 0 ${WebKitUrls.FLOOR_URL}", rig.glue.calls.last())
        rig.send(policy(1, WebKitUrls.FLOOR_URL))
        assertEquals("answerPolicy 1 true", rig.glue.last("answerPolicy"), "the engine's own navigation to the floor")
        rig.send(GlueEvent.Finished(0, rig.page(WebKitUrls.FLOOR_URL), floorKept = true))
        rig.answer("evaluate") { GlueEvent.Result(it, true, "\"Mozilla/5.0 Test\"", null) }
        assertTrue(warm.await())
        assertTrue(rig.glue.calls.any { it.startsWith("evaluate") && " client navigator.userAgent" in it })
        rig.engine.configure(setup.copy(userAgentSuffix = "Sample/1"))
        assertEquals(
            "configure dark=false transparent=false ua=Mozilla/5.0 Test Sample/1 inspectable=false shim=true",
            rig.glue.last("configure"),
        )
    }

    @Test fun aStaleCommitChangesNothingBeforeItIsDropped() = runTest {
        val rig = Rig(this)
        val client = RecordingClient()
        rig.engine.client = client
        rig.engine.configure(setup)
        rig.engine.load(WebContent.Url("https://a.test/"))
        rig.engine.load(WebContent.Url("https://b.test/"))
        assertEquals(listOf("loadUrl 1 https://a.test/", "loadUrl 2 https://b.test/"), rig.glue.calls.filter { it.startsWith("loadUrl") })
        // A commits before B's load reached AppKit: its epoch is 1, and it is dropped before it touches anything (D4).
        rig.send(GlueEvent.Committed(1, rig.page("https://a.test/", loading = true), 0.5f))
        assertTrue(rig.events.none { it is EngineEvent.UrlChanged }, "${rig.events}")
        // So B is still the engine's own navigation: allowed without asking the client.
        rig.send(policy(5, "https://b.test/"))
        assertEquals("answerPolicy 5 true", rig.glue.last("answerPolicy"))
        assertTrue(client.asked.isEmpty(), "${client.asked}")
        rig.send(GlueEvent.Committed(2, rig.page("https://b.test/", loading = true), 0.5f))
        assertEquals(listOf(EngineEvent.UrlChanged("https://b.test/")), rig.events.filterIsInstance<EngineEvent.UrlChanged>())
    }

    /**
     * A parked page that floods its bridge holds nothing of it: what it says while no view subscribes reaches no one,
     * and is dropped as it comes. The engine stays usable, so the view that adopts the page gets it as it is, and hears
     * its `$ready`, nothing of the flood.
     */
    @Test fun aParkedPageThatFloodsItsBridgeStaysAdoptable() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.collector.cancel()
        runCurrent()
        val tick = "{\"t\":\"event\",\"n\":\"tick\",\"from\":\"n1\"}"
        repeat(5_000) { rig.relay(1, "$doc msg $tick") }
        rig.relay(1, "$doc msg $ready")
        assertTrue(rig.engine.isUsable, "a chatty parked page went the way of a crash")
        val heard = mutableListOf<EngineEvent>()
        val adopter = backgroundScope.launch { rig.engine.events.collect { heard += it } }
        runCurrent()
        assertEquals(listOf(EngineEvent.PageMessage("https://c1.invalid", ready)), heard.filterIsInstance<EngineEvent.PageMessage>())
        adopter.cancel()
    }

    @Test fun relayMessagesCountOnlyFromTheCurrentDocumentsMainFrame() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.load(html)
        rig.send(GlueEvent.Committed(1, rig.page(html.origin.url, loading = true), 0.5f))
        rig.relay(1, "$doc start")
        rig.relay(1, "$doc msg $ready")
        rig.relay(1, "fedcba9876543210 msg $ready")
        rig.relay(1, "$doc msg $ready", mainFrame = false)
        rig.relay(0, "$doc msg $ready")
        assertEquals(listOf(EngineEvent.PageMessage("https://c1.invalid", ready)), rig.events.filterIsInstance<EngineEvent.PageMessage>())
    }

    @Test fun firstPaintWaitsForContentAPlacedStillSurfaceAndTwoTicks() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.load(html)
        rig.send(GlueEvent.Committed(1, rig.page(html.origin.url, loading = true), 0.5f))
        rig.relay(1, "$doc start")
        rig.relay(1, "$doc paint")
        assertTrue(EngineEvent.FirstPaint !in rig.events, "no surface yet")
        rig.engine.surfaceAttached(rig.node)
        assertTrue(rig.place())
        assertEquals("attach 7", rig.glue.calls.first { it.startsWith("attach") })
        assertEquals("place 0.0 0.0 640.0 480.0 in 0.0 0.0 640.0 480.0 at 2.0 hidden=false", rig.glue.last("place"))
        rig.repainted() // in a new window: drawn there again first (D6)
        rig.tick()
        rig.tick() // still now (D2)
        assertTrue(EngineEvent.FirstPaint !in rig.events, "still, but not two ticks after")
        assertFalse(rig.glue.lastInteractive)
        rig.tick()
        rig.tick() // the handoff's two ticks in place
        assertTrue(EngineEvent.FirstPaint in rig.events)
        assertTrue(rig.glue.lastInteractive && rig.glue.lastShown)
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node))
    }

    /** Minor 8: a list's scroll only moves the page; it stays live and keeps taking presses throughout. */
    @Test fun aMovingSlotKeepsTheLivePageAndItsInput() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.snapshot()
        advanceTimeBy(1_500) // the cover is old now: a motion that needed it would take a fresh one
        runCurrent()
        val repaints = rig.glue.ids("repaint").size
        val snapshots = rig.glue.ids("snapshot").size
        rig.place(y = 10f)
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node), "a move alone keeps the live page: nothing trails behind it")
        assertTrue(rig.glue.lastInteractive, "and its input: the view keeps up with its slot")
        advanceTimeBy(16)
        rig.place(y = 20f)
        advanceTimeBy(MacWebEngine.MOTION_SETTLE_MILLIS - 10)
        runCurrent()
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node), "still moving, still live")
        assertTrue(rig.glue.lastInteractive)
        advanceTimeBy(20)
        rig.tick()
        rig.tick()
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node))
        assertTrue(rig.glue.lastInteractive)
        assertEquals(repaints, rig.glue.ids("repaint").size, "a move alone draws nothing again")
        assertEquals(snapshots, rig.glue.ids("snapshot").size, "and takes no picture for a cover")
    }

    /**
     * C2: a pane resized shows its last picture at its true size, top-left (never scaled to the new slot), and takes no
     * presses until WebKit has drawn it at its new size.
     */
    @Test fun aResizingSlotShowsTheMotionCoverAtItsTrueSizeAndTakesNoInputUntilItSettles() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.snapshot(640, 480)
        assertEquals("snapshot ${rig.glue.ids("snapshot").last()} 320.0", rig.glue.last("snapshot"), "640 px at 2x")
        rig.place(width = 600f)
        val cover = assertIs<SurfaceDraw.Cover>(rig.engine.drawFor(rig.node), "covered from the frame its size first changes (D2)")
        assertEquals(1f, cover.scale, "drawn at its true size: 640 px for a page 320 points wide, at 2x")
        assertFalse(rig.glue.lastInteractive)
        advanceTimeBy(16)
        rig.place(x = 40f, width = 560f, height = 600f)
        advanceTimeBy(MacWebEngine.MOTION_SETTLE_MILLIS - 10)
        runCurrent()
        assertEquals(1f, assertIs<SurfaceDraw.Cover>(rig.engine.drawFor(rig.node), "still resizing").scale, "never magnified")
        advanceTimeBy(20)
        rig.repainted()
        rig.tick()
        rig.tick()
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node))
        assertTrue(rig.glue.lastInteractive)
        advanceTimeBy(1_000)
        rig.place(x = 40f, y = 30f, width = 560f, height = 600f)
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node), "settled: the next motion, a move, keeps the live page")
    }

    /** The cover keeps its size in points: on a window moved to a 1x screen it is half as many pixels. */
    @Test fun theMotionCoverFollowsTheWindowsScale() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.snapshot(640, 480)
        rig.place(width = 320f, height = 240f, scale = 1f)
        assertEquals(0.5f, assertIs<SurfaceDraw.Cover>(rig.engine.drawFor(rig.node)).scale)
    }

    /**
     * C2: an embed (a chat's demo) growing as its page reports a new height, a few pixels a frame under the runtime's
     * spring: nothing it shows moves or reflows, so it stays live, uncovered, and takes presses while it grows.
     */
    @Test fun anEmbedGrowingInHeightStaysLiveAndTakesInput() = runTest {
        val rig = Rig(this, inList = true)
        rig.live()
        rig.snapshot()
        for (height in listOf(484f, 492f, 505f, 520f, 538f, 560f)) {
            rig.place(height = height)
            assertTrue(rig.engine.drawFor(rig.node) !is SurfaceDraw.Cover, "live while it grows to $height px: no cover")
            assertTrue(rig.glue.lastInteractive, "taking presses while it grows to $height px")
            advanceTimeBy(16)
        }
        advanceTimeBy(MacWebEngine.MOTION_SETTLE_MILLIS)
        for (repaint in rig.glue.ids("repaint")) rig.send(GlueEvent.Result(repaint, true, null, null))
        rig.tick()
        rig.tick()
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node))
        assertTrue(rig.glue.lastInteractive)
    }

    /**
     * C2: what an embed grows by shows WKWebView's own white for a frame or two before WebKit draws it, whatever its
     * under-page colour (tried on macOS 26): a white flicker under a dark page's every new line. The grown part shows
     * the page's own background meanwhile, only until WebKit has drawn the new height (a repaint, two frames).
     */
    @Test fun whatAnEmbedGrowsByShowsThePagesBackgroundUntilWebKitHasDrawnIt() = runTest {
        val rig = Rig(this, inList = true)
        rig.live()
        rig.snapshot()
        val repaints = rig.glue.ids("repaint").size
        rig.place(height = 520f)
        val grown = assertIs<SurfaceDraw.Grown>(rig.engine.drawFor(rig.node), "the hole, and past 480 px the page's background")
        assertEquals(480f, grown.from)
        assertTrue(grown.background.alpha > 0f, "the page's own colour, from its last picture")
        assertTrue(rig.glue.ids("repaint").size > repaints, "WebKit is asked to draw the new height at once, not after the motion")
        rig.repainted()
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node), "drawn at 520 px: all of it live")
        assertTrue(rig.glue.lastInteractive)
        rig.place(height = 500f)
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node), "shrinking shows nothing new")
    }

    /** A pane's new height reflows its page (a footer, 100vh): it is covered meanwhile, and so is an embed's new width. */
    @Test fun aPanesNewHeightAndAnEmbedsNewWidthAreCovered() = runTest {
        val pane = Rig(this)
        pane.live()
        pane.snapshot()
        pane.place(height = 520f)
        assertIs<SurfaceDraw.Cover>(pane.engine.drawFor(pane.node))
        assertFalse(pane.glue.lastInteractive)
        val embed = Rig(this, inList = true)
        embed.live()
        embed.snapshot()
        embed.place(width = 600f)
        assertIs<SurfaceDraw.Cover>(embed.engine.drawFor(embed.node))
        assertFalse(embed.glue.lastInteractive)
    }

    /**
     * I2: a slot partly scrolled out of its clipped scroller places its view at the part that shows, so the page never
     * draws outside its clip; a clip that changes alone redraws nothing and keeps the page live.
     */
    @Test fun aClippedSlotPlacesItsViewAtTheVisiblePart() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.place(visible = Rect(0f, 240f, 640f, 480f))
        assertEquals("place 0.0 0.0 640.0 480.0 in 0.0 240.0 640.0 240.0 at 2.0 hidden=false", rig.glue.last("place"))
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node))
        assertTrue(rig.glue.lastInteractive)
        rig.place(visible = Rect(0f, 120f, 640f, 480f))
        assertEquals("place 0.0 0.0 640.0 480.0 in 0.0 120.0 640.0 360.0 at 2.0 hidden=false", rig.glue.last("place"))
    }

    @Test fun aResizedSlotIsDrawnAgainBeforeThePageShows() = runTest {
        val rig = Rig(this)
        rig.live()
        val repaints = rig.glue.ids("repaint").size
        rig.place(width = 600f)
        assertEquals(repaints + 1, rig.glue.ids("repaint").size, "a new size draws the page again first")
        assertFalse(rig.glue.lastInteractive)
        rig.repainted()
        rig.tick()
        rig.tick()
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node))
        assertTrue(rig.glue.lastInteractive)
    }

    @Test fun overlaysShapeTheInputRegion() = runTest {
        val rig = Rig(this)
        rig.live()
        val page = Rect(0f, 0f, 640f, 480f)
        val menu = OverlayShape(RoundRect(Rect(100f, 100f, 200f, 200f), CornerRadius(8f)))
        val cutouts = computeCutouts(page, page, listOf(menu), emptyList())
        rig.engine.applyOverlays(cutouts)
        assertEquals(InputRegion.of(cutouts), rig.glue.lastRegion)
        assertTrue(rig.engine.needsCutouts, "under the canvas, overlays still shape input (D1)")
    }

    @Test fun resetPutsTheFloorBackAnswersWhatWaitsAndWipesAnEphemeralStore() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(dialog = CompletableDeferred())
        rig.engine.client = client
        rig.engine.configure(setup)
        rig.engine.load(html)
        rig.send(GlueEvent.Committed(1, rig.page(html.origin.url, back = listOf(WebKitUrls.FLOOR_URL), loading = true), 0.5f))
        rig.send(GlueEvent.Dialog(3, DialogKind.Alert, "hi", null, html.origin.url))
        assertTrue(client.asked.any { it is WebDialog.Alert }, "${client.asked}")
        val reset = async { rig.engine.reset() }
        runCurrent()
        val calls = rig.glue.calls
        assertTrue(calls.indexOf("answerDialog 3 true null") in 0 until calls.indexOf("showFloor 2"), "$calls")
        assertTrue(listOf("stop", "stopMedia", "removeScripts").all { it in calls }, "$calls")
        rig.send(policy(9, WebKitUrls.FLOOR_URL, NavigationKind.BackForward))
        assertEquals("answerPolicy 9 true", rig.glue.last("answerPolicy"))
        rig.send(GlueEvent.Finished(2, rig.page(WebKitUrls.FLOOR_URL), floorKept = true))
        rig.answer("wipeData") { GlueEvent.Result(it, true, null, null) }
        assertTrue(reset.await())
        rig.engine.configure(setup)
        rig.engine.load(html)
        rig.send(GlueEvent.Committed(4, rig.page(html.origin.url, back = listOf(WebKitUrls.FLOOR_URL), loading = true), 0.5f))
        assertEquals(
            EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false),
            rig.events.filterIsInstance<EngineEvent.HistoryChanged>().last(),
            "history begins with the next lease's first load",
        )
    }

    @Test fun releaseAnswersWhatWaitsAndIsFinal() = runTest {
        val rig = Rig(this)
        val pending = async { rig.engine.evaluate("1") }
        runCurrent()
        rig.engine.release()
        assertNull(pending.await())
        assertTrue(rig.glue.released)
        assertEquals(listOf(rig.engine), rig.released)
        assertFalse(rig.engine.isUsable)
        val calls = rig.glue.calls.size
        rig.engine.configure(setup)
        rig.engine.load(html)
        assertEquals(calls, rig.glue.calls.size, "nothing reaches a released glue")
        assertNull(rig.engine.evaluate("1"))
    }

    @Test fun aBundleServesOnlyItsOwnFilesThroughItsProvider() = runTest {
        val rig = Rig(this)
        val files = object : WebResourceProvider {
            override suspend fun load(path: String): WebResource? =
                if (path == "index.html") WebResource("<p>hi</p>".encodeToByteArray(), "text/html") else null
        }
        val bundle = WebContent.Bundle(WebOrigin("plugin"), files)
        rig.engine.configure(setup)
        rig.engine.load(bundle)
        assertEquals("loadUrl 1 webview-bundle://plugin.invalid/index.html", rig.glue.last("loadUrl"))
        rig.send(GlueEvent.TaskStart(1, "webview-bundle://plugin.invalid/index.html"))
        assertEquals("respond 1 200 text/html 9", rig.glue.last("respond"))
        rig.send(GlueEvent.TaskStart(2, "webview-bundle://plugin.invalid/missing.js"))
        assertEquals("respond 2 404 text/plain 0", rig.glue.last("respond"))
        rig.send(GlueEvent.TaskStart(3, "webview-bundle://other.invalid/index.html"))
        assertEquals("failTask 3", rig.glue.last("failTask"))
        rig.send(GlueEvent.TaskStart(4, "webview-bundle://plugin.invalid/%2e%2e%2fsecret"))
        assertEquals("respond 4 404 text/plain 0", rig.glue.last("respond"))
    }

    @Test fun thePagesNavigationsAskTheClientAndAPressMakesTheNextOneTheUsers() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(navigation = NavigationDecision.Allow)
        rig.engine.client = client
        rig.engine.configure(setup)
        rig.engine.load(html)
        rig.send(GlueEvent.Committed(1, rig.page(html.origin.url), 0.5f))
        rig.send(policy(1, "https://example.org/", NavigationKind.Link))
        assertEquals(NavigationRequest("https://example.org/", isMainFrame = true, isUserGesture = false), client.asked.last())
        assertEquals("answerPolicy 1 true", rig.glue.last("answerPolicy"))
        rig.send(GlueEvent.Pointer(10.0, 10.0))
        assertTrue(EngineEvent.FocusChanged(true) in rig.events)
        rig.send(policy(2, "https://example.org/b", NavigationKind.Link))
        assertEquals(NavigationRequest("https://example.org/b", isMainFrame = true, isUserGesture = true), client.asked.last())
        advanceTimeBy(1_001)
        rig.send(policy(3, "https://example.org/c", NavigationKind.Link))
        assertEquals(NavigationRequest("https://example.org/c", isMainFrame = true, isUserGesture = false), client.asked.last())
    }

    /**
     * One press, one request of the user's (final review, minor): a page that leaves twice, or opens windows, after a
     * click had them all count as the user's within the second, and one click could become several external opens. As
     * on iOS, the main frame's request it is credited to spends it; a subframe's doesn't. An app's load starts afresh.
     */
    @Test fun aPressCountsForOneRequestToLeaveOnly() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(navigation = NavigationDecision.Allow)
        rig.engine.client = client
        rig.engine.configure(setup)
        rig.engine.load(html)
        rig.send(GlueEvent.Committed(1, rig.page(html.origin.url), 0.5f))
        fun gestures() = client.asked.mapNotNull {
            when (it) {
                is NavigationRequest -> "${if (it.isMainFrame) "nav" else "frame"} ${it.url.substringAfterLast('/')} ${it.isUserGesture}"
                is NewWindowRequest -> "window ${it.url.substringAfterLast('/')} ${it.isUserGesture}"
                else -> null
            }
        }
        rig.send(GlueEvent.Pointer(10.0, 10.0))
        rig.send(GlueEvent.Policy(1, "https://example.org/f", mainFrame = false, opensWindow = false, download = false, kind = NavigationKind.Link))
        rig.send(policy(2, "https://example.org/a", NavigationKind.Link))
        rig.send(policy(3, "https://example.org/b", NavigationKind.Link))
        rig.send(GlueEvent.NewWindow("https://example.org/w1"))
        rig.send(GlueEvent.Pointer(10.0, 10.0))
        rig.send(GlueEvent.NewWindow("https://example.org/w2"))
        rig.send(policy(4, "https://example.org/c", NavigationKind.Link))
        rig.send(GlueEvent.Pointer(10.0, 10.0))
        rig.engine.load(WebContent.Html("<p>b</p>", WebOrigin("c2")))
        rig.send(GlueEvent.Committed(2, rig.page("https://c2.invalid/"), 0.5f))
        rig.send(policy(5, "https://example.org/d", NavigationKind.Link))
        assertEquals(
            listOf("frame f true", "nav a true", "nav b false", "window w1 false", "window w2 true", "nav c false", "nav d false"),
            gestures(),
        )
    }

    // --- Minor 1, 2, 4 ---

    /** Minor 1: a dead page's view shows nothing worth seeing: its last picture covers it, and it takes nothing. */
    @Test fun aCrashedPageIsCoveredAndTakesNothing() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.snapshot()
        rig.send(GlueEvent.Crashed)
        assertEquals(EngineEvent.Crashed, rig.events.last(), "the crash is reported")
        assertFalse(rig.engine.isUsable)
        assertIs<SurfaceDraw.Cover>(rig.engine.drawFor(rig.node), "its last picture, never the dead view")
        assertFalse(rig.glue.lastInteractive)
        assertFalse(rig.glue.lastShown)
        val bare = Rig(this)
        bare.live()
        bare.send(GlueEvent.Crashed)
        assertEquals(SurfaceDraw.Nothing, bare.engine.drawFor(bare.node), "no picture: Compose's own content, never the dead view")
    }

    /** Minor 2: a release while a reset waits for the floor ends the reset; nothing reaches the freed glue after it. */
    @Test fun aReleaseDuringAResetEndsItWithoutTouchingTheGlue() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.engine.setVisible(false) // so the reset would resume its media
        val reset = async { rig.engine.reset() }
        runCurrent()
        assertTrue(rig.glue.calls.any { it.startsWith("showFloor") }, "waiting for the floor")
        rig.engine.release()
        assertFalse(reset.await())
        assertEquals("release", rig.glue.calls.last(), "nothing after the release: ${rig.glue.calls}")
    }

    /** Minor 4: a settle that a newer motion cancels forgets its request, and one whose repaint never answers gives up. */
    @Test fun cancelledRequestsAreForgottenAndARepaintThatNeverAnswersStillEndsTheMotion() = runTest {
        val rig = Rig(this)
        rig.live()
        val waiting = rig.engine.pendingRequests
        for (step in 0 until 20) {
            rig.place(width = 600f - step)
            advanceTimeBy(MacWebEngine.MOTION_SETTLE_MILLIS + 50) // the settle asks for its repaint, which never answers
            runCurrent()
        }
        // The last settle's repaint, and the motion cover's picture: never one a cancelled settle left behind.
        assertTrue(rig.engine.pendingRequests <= waiting + 2, "${rig.engine.pendingRequests} requests wait, $waiting did before")
        advanceTimeBy(10_000)
        runCurrent()
        for (tick in rig.ticksWaiting()) rig.send(GlueEvent.Tick(tick, inWindow = true))
        for (tick in rig.ticksWaiting()) rig.send(GlueEvent.Tick(tick, inWindow = true))
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node), "uncovered once the repaint gave up")
        assertTrue(rig.glue.lastInteractive, "and taking presses")
    }

    /** Minor 4: a display tick that never comes (an occluded window) doesn't keep the page covered for good. */
    @Test fun aTickThatNeverComesDoesntKeepThePageCovered() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.load(html)
        rig.send(GlueEvent.Committed(1, rig.page(html.origin.url, loading = true), 0.5f))
        rig.relay(1, "$doc start")
        rig.relay(1, "$doc paint")
        rig.engine.surfaceAttached(rig.node)
        rig.place()
        rig.repainted()
        advanceTimeBy(10_000) // no tick answers
        runCurrent()
        assertTrue(EngineEvent.FirstPaint in rig.events, "${rig.events}")
    }

    // --- Tests the mutants showed missing ---

    /** push()'s epoch filter: what an earlier document caused never reaches the subscriber. */
    @Test fun anEarlierDocumentsStartIsDropped() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.load(html)
        rig.engine.load(WebContent.Html("<p>b</p>", WebOrigin("c2")))
        rig.send(GlueEvent.Started(1, html.origin.url))
        assertTrue(rig.events.none { it is EngineEvent.Progress }, "${rig.events}")
        rig.send(GlueEvent.Started(2, "https://c2.invalid/"))
        assertEquals(listOf(EngineEvent.Progress(0.1f)), rig.events.filterIsInstance<EngineEvent.Progress>())
    }

    /** reset's last drop: nothing that happened during the reset reaches the next lease. */
    @Test fun nothingFromDuringTheResetReachesTheNextLease() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.collector.cancel() // the lease ended: its subscriber is gone
        val reset = async { rig.engine.reset() }
        runCurrent()
        rig.send(GlueEvent.Pointer(-1.0, -1.0)) // the old page took a key meanwhile
        rig.send(policy(9, WebKitUrls.FLOOR_URL, NavigationKind.BackForward))
        rig.send(GlueEvent.Finished(2, rig.page(WebKitUrls.FLOOR_URL), floorKept = true))
        rig.answer("wipeData") { GlueEvent.Result(it, true, null, null) }
        assertTrue(reset.await())
        val next = mutableListOf<EngineEvent>()
        backgroundScope.launch { rig.engine.events.collect { next += it } }
        runCurrent()
        assertEquals(emptyList<EngineEvent>(), next)
    }

    /** A slot shown again (a lazy list's retained item placed again) is drawn again, under its cover, before input. */
    @Test fun aSlotShownAgainIsDrawnAgainBeforeItTakesInput() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.snapshot()
        assertTrue(rig.engine.surfaceUnplaced(rig.node))
        advanceTimeBy(1_000) // scrolled back a while later
        runCurrent()
        val repaints = rig.glue.ids("repaint").size
        rig.place()
        assertEquals(repaints + 1, rig.glue.ids("repaint").size, "WebKit draws a view shown again before it shows")
        assertIs<SurfaceDraw.Cover>(rig.engine.drawFor(rig.node))
        assertFalse(rig.glue.lastInteractive)
        rig.repainted()
        rig.tick()
        rig.tick()
        assertTrue(rig.glue.lastInteractive)
    }

    /** The first paint waits for the slot to be still: drawn again where it is, then two ticks. */
    @Test fun firstPaintWaitsUntilTheSlotIsStill() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.surfaceAttached(rig.node)
        rig.place()
        // Placed, in a new window: not still until WebKit has drawn it there. The document paints meanwhile.
        rig.engine.load(html)
        rig.send(GlueEvent.Committed(1, rig.page(html.origin.url, loading = true), 0.5f))
        rig.relay(1, "$doc start")
        rig.relay(1, "$doc paint")
        repeat(3) { for (tick in rig.ticksWaiting()) rig.send(GlueEvent.Tick(tick, inWindow = true)) }
        assertTrue(EngineEvent.FirstPaint !in rig.events, "not before the slot is still: ${rig.events}")
        rig.repainted()
        rig.tick()
        rig.tick()
        rig.tick()
        rig.tick()
        assertTrue(EngineEvent.FirstPaint in rig.events)
    }

    @Test fun aCrashIsReported() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.send(GlueEvent.Crashed)
        assertTrue(EngineEvent.Crashed in rig.events)
        assertFalse(rig.engine.isUsable)
    }

    /** A reset whose floor came back with the old page still behind it fails: the next lease would inherit its history. */
    @Test fun aResetWithTheOldHistoryStillThereFails() = runTest {
        val rig = Rig(this)
        rig.live()
        val reset = async { rig.engine.reset() }
        runCurrent()
        rig.send(policy(9, WebKitUrls.FLOOR_URL, NavigationKind.BackForward))
        rig.send(GlueEvent.Finished(2, rig.page(WebKitUrls.FLOOR_URL, back = listOf(html.origin.url)), floorKept = true))
        rig.answer("wipeData") { GlueEvent.Result(it, true, null, null) }
        assertFalse(reset.await())
    }

    /** A dialog during a reset is answered by default at once, whoever the client is by then. */
    @Test fun aDialogDuringAResetIsAnsweredByDefault() = runTest {
        val rig = Rig(this)
        rig.live()
        val reset = async { rig.engine.reset() }
        runCurrent()
        val client = RecordingClient(dialog = CompletableDeferred())
        rig.engine.client = client
        rig.send(GlueEvent.Dialog(7, DialogKind.Confirm, "leave?", null, html.origin.url))
        assertEquals("answerDialog 7 false null", rig.glue.last("answerDialog"))
        assertTrue(client.asked.isEmpty(), "${client.asked}")
        reset.cancel()
    }

    /**
     * The view that asked goes (it parks the page, and its dialog went with it): what it was asked gets the safe answer
     * then, or the page would wait on a dialog no UI shows, the adopting view's page with it (final review, as iOS does).
     * The same client again asks nothing anew.
     */
    @Test fun aQuestionPendingWhenItsClientGoesGetsTheSafeAnswer() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(dialog = CompletableDeferred())
        rig.engine.client = client
        rig.live()
        rig.send(GlueEvent.Dialog(7, DialogKind.Confirm, "leave?", null, html.origin.url))
        assertTrue(client.asked.any { it is WebDialog.Confirm }, "the client is deciding")
        rig.engine.client = client
        assertNull(rig.glue.last("answerDialog"), "the same client still answers")
        rig.engine.client = null
        assertEquals("answerDialog 7 false null", rig.glue.last("answerDialog"))
        client.dialog.complete(DialogResult.Confirmed)
        runCurrent()
        assertEquals(listOf("answerDialog 7 false null"), rig.glue.calls.filter { it.startsWith("answerDialog") }, "answered once")
    }

    /** Release answers what the client still decides, with WebKit's safe default, before it frees the glue. */
    @Test fun releaseAnswersWhatTheClientStillDecidesBeforeTheGlueGoes() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(dialog = CompletableDeferred())
        rig.engine.client = client
        rig.live()
        rig.send(GlueEvent.Dialog(7, DialogKind.Confirm, "leave?", null, html.origin.url))
        assertTrue(client.asked.any { it is WebDialog.Confirm }, "the client is deciding")
        rig.engine.release()
        client.dialog.complete(DialogResult.Confirmed)
        runCurrent()
        val calls = rig.glue.calls
        assertEquals(listOf("answerDialog 7 false null", "release"), calls.takeLast(2), "$calls")
    }

    @Test fun anAttachmentAndADownloadLinkAreDownloadsTheClientHearsOf() = runTest {
        val rig = Rig(this)
        val client = RecordingClient()
        rig.engine.client = client
        rig.live()
        rig.send(
            GlueEvent.Response(5, "https://c1.invalid/a.txt", forMainFrame = true, canShow = true, status = 200, length = 10,
                mimeType = "text/plain", suggestedFilename = "a.txt", disposition = "attachment; filename=a.txt"),
        )
        assertEquals("answerResponse 5 false", rig.glue.last("answerResponse"), "saved nowhere")
        rig.send(GlueEvent.Policy(6, "https://c1.invalid/b.zip", mainFrame = true, opensWindow = false, download = true, kind = NavigationKind.Link))
        assertEquals("answerPolicy 6 false", rig.glue.last("answerPolicy"))
        runCurrent()
        assertEquals(
            listOf(
                DownloadRequest("https://c1.invalid/a.txt", "a.txt", "text/plain", 10),
                DownloadRequest("https://c1.invalid/b.zip", null, null, null),
            ),
            client.asked.filterIsInstance<DownloadRequest>(),
        )
    }

    /** A main-frame error lifts the cover: the page shows what WebKit has, and takes input. */
    @Test fun anErrorLeavesThePageShownAndTakingInput() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.surfaceAttached(rig.node)
        rig.place()
        rig.repainted()
        rig.tick()
        rig.tick()
        rig.engine.load(WebContent.Url("https://offline.test/"))
        rig.send(GlueEvent.Failed(1, "https://offline.test/", "NSURLErrorDomain", -1009, "offline"))
        assertEquals(listOf(EngineEvent.Error(WebError.Network("https://offline.test/", "offline"))), rig.events.filterIsInstance<EngineEvent.Error>())
        assertTrue(rig.glue.lastShown)
        assertTrue(rig.glue.lastInteractive)
    }

    @Test fun javaScriptOffInstallsNoShim() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup.copy(javaScriptEnabled = false))
        assertEquals("configure dark=false transparent=false ua=null inspectable=false shim=false", rig.glue.last("configure"))
    }

    /** A replay covers the page until it is drawn in its new place: its first paint comes again only then. */
    @Test fun aReplayCoversThePageUntilItsFirstPaint() = runTest {
        val rig = Rig(this)
        rig.live()
        assertTrue(rig.glue.lastShown)
        rig.engine.replayState()
        runCurrent()
        assertFalse(rig.glue.lastShown, "covered again until its first paint")
        assertFalse(rig.glue.lastInteractive)
    }

    /** Private schemes never open in a new window, whatever the client says. */
    @Test fun aNewWindowOnAPrivateSchemeIsRefused() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(newWindow = NewWindowDecision.OpenHere)
        rig.engine.client = client
        rig.live()
        val loads = rig.glue.calls.count { it.startsWith("loadUrl") }
        rig.send(GlueEvent.NewWindow(WebKitUrls.FLOOR_URL))
        rig.send(GlueEvent.NewWindow("webview-bundle://other.invalid/index.html"))
        assertTrue(client.asked.none { it is NewWindowRequest }, "${client.asked}")
        assertEquals(loads, rig.glue.calls.count { it.startsWith("loadUrl") })
        rig.send(GlueEvent.NewWindow("https://example.org/popup"))
        assertTrue(rig.glue.last("loadUrl")!!.endsWith(" https://example.org/popup"), "the web, which the client may open here")
    }

    @Test fun anAdoptedPageReplaysWhatItIsAndPaintsOnceDrawnInItsNewPlace() = runTest {
        val rig = Rig(this)
        rig.live()
        rig.send(GlueEvent.State(1, rig.page(html.origin.url + "#moved", title = "kept")))
        // Parked: its surface leaves, it is hidden, its subscriber goes.
        rig.engine.surfaceDetached(rig.node)
        rig.engine.setVisible(false)
        assertEquals("detach 7", rig.glue.last("detach"))
        assertEquals("setMediaSuspended true", rig.glue.last("setMediaSuspended"))
        rig.events.clear()
        rig.engine.replayState()
        runCurrent()
        assertEquals(EngineEvent.UrlChanged(html.origin.url + "#moved"), rig.events.first())
        assertTrue(EngineEvent.TitleChanged("kept") in rig.events)
        assertTrue(EngineEvent.FirstPaint !in rig.events, "not before it is drawn in its new place")
        val adopted = MacSurfaceNode(rig.engine, inList = true)
        rig.engine.surfaceAttached(adopted)
        val bounds = Rect(0f, 100f, 640f, 580f)
        rig.engine.surfacePlaced(adopted, 7L, bounds, bounds, 2f)
        rig.repainted()
        rig.tick()
        rig.tick() // still
        rig.tick()
        rig.tick() // the replay's two ticks in place
        assertTrue(EngineEvent.FirstPaint in rig.events)
        assertFalse(rig.glue.lastWheelToPage, "an embed leaves the wheel to Compose (D7)")
    }

    // --- Failed loads: iOS's rules (final review, item 3) ---

    private val offline = WebContent.Url("https://offline.test/")

    /** The glue's calls that start a document: loads, WebKit's reload, restores. */
    private fun FakePageGlue.starts(): List<String> =
        calls.filter { it.startsWith("loadUrl") || it.startsWith("loadHtml") || it.startsWith("reload") || it.startsWith("restoreState") }

    /** [offline] loaded, and failed before anything of it committed, as an app started offline sees it. */
    private fun Rig.failedFirstLoad() {
        engine.configure(setup)
        engine.load(offline)
        send(GlueEvent.Failed(1, offline.url, "NSURLErrorDomain", -1009, "offline"))
    }

    /** The engine's saveState, its glue answering with [bytes] if it is asked. */
    private suspend fun Rig.saved(bytes: ByteArray): ByteArray? {
        val saved = scope.async { engine.saveState() }
        scope.runCurrent()
        glue.ids("saveState").lastOrNull()?.let { send(GlueEvent.Result(it, true, null, bytes)) }
        return saved.await()
    }

    /**
     * The review's probe: WebKit's reload would reload the floor, the committed document, which the rules refuse in
     * silence, and the navigator had cleared the error, so a retry button did nothing. The content loads again.
     */
    @Test fun aRetryOfContentThatNeverCommittedLoadsItAgain() = runTest {
        val rig = Rig(this)
        val client = RecordingClient(navigation = NavigationDecision.Block)
        rig.engine.client = client
        rig.failedFirstLoad()
        rig.glue.clear()
        rig.engine.reload()
        assertEquals(listOf("loadUrl 2 ${offline.url}"), rig.glue.starts())
        rig.send(policy(5, offline.url))
        assertEquals("answerPolicy 5 true", rig.glue.last("answerPolicy"), "the engine's own load, never the client's")
        assertTrue(client.asked.isEmpty(), "${client.asked}")
    }

    @Test fun aRetryOfAPageThatCommittedIsWebKitsReload() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.load(offline)
        rig.send(GlueEvent.Committed(1, rig.page(offline.url), 0.5f))
        rig.glue.clear()
        rig.engine.reload()
        assertEquals(listOf("reload 2"), rig.glue.starts())
    }

    /**
     * The review's probe: the floor's history, saved under the content's key, restored nothing and reported nothing:
     * an app started offline left its views under their covers for good.
     */
    @Test fun contentThatNeverCommittedSavesNoState() = runTest {
        val rig = Rig(this)
        rig.failedFirstLoad()
        assertNull(rig.saved("the floor's history".encodeToByteArray()), "WebKit's state is the floor's, or an earlier content's")
    }

    @Test fun aPageThatCommittedSavesItsStateUnderItsKey() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.load(offline)
        rig.send(GlueEvent.Committed(1, rig.page(offline.url), 0.5f))
        val saved = assertNotNull(rig.saved("its history".encodeToByteArray()))
        assertEquals("its history", SavedWebKitState.decode(saved, offline.key)?.decodeToString())
    }

    /** A content another load replaced before it committed saves nothing either: WebKit still holds the page before. */
    @Test fun contentStillLoadingSavesTheStateOfNoOtherPage() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.load(offline)
        rig.send(GlueEvent.Committed(1, rig.page(offline.url), 0.5f))
        rig.engine.load(WebContent.Url("https://next.test/"))
        assertNull(rig.saved("the last page's history".encodeToByteArray()))
    }

    /** A retry of a restore that never committed restores it again; one WebKit refuses then loads the content. */
    @Test fun aRetryOfARestoreThatNeverCommittedRestoresItAgain() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        val state = SavedWebKitState.encode(offline.key, "state".encodeToByteArray())
        assertTrue(rig.engine.restoreState(state, offline))
        rig.send(GlueEvent.Failed(1, offline.url, "NSURLErrorDomain", -1009, "offline"))
        rig.glue.clear()
        rig.engine.reload()
        assertEquals(listOf("restoreState 2 5"), rig.glue.starts())
        rig.send(GlueEvent.Failed(2, offline.url, "NSURLErrorDomain", -1009, "offline"))
        rig.glue.restoreAccepted = false
        rig.glue.clear()
        rig.engine.reload()
        assertEquals(listOf("restoreState 3 5", "loadUrl 4 ${offline.url}"), rig.glue.starts())
    }

    /** The engine's own navigation refused by the rules (WebKit's reload of the floor, say) is an error, never silence. */
    @Test fun anOwnNavigationTheRulesRefuseIsAnError() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.surfaceAttached(rig.node)
        rig.place()
        rig.repainted()
        rig.tick()
        rig.tick()
        rig.engine.load(offline)
        rig.send(GlueEvent.Committed(1, rig.page(offline.url), 0.5f))
        rig.engine.reload()
        rig.send(policy(9, WebKitUrls.FLOOR_URL, NavigationKind.Reload))
        assertEquals("answerPolicy 9 false", rig.glue.last("answerPolicy"))
        assertEquals(listOf(EngineEvent.Error(WebError.BlockedByPolicy(offline.url))), rig.events.filterIsInstance<EngineEvent.Error>())
        assertTrue(rig.glue.lastShown, "the runtime shows the error: the page's cover is lifted, as for any failed load")
        // A retry loads the content again: WebKit's committed document is the floor's.
        rig.glue.clear()
        rig.engine.reload()
        assertEquals(listOf("loadUrl 3 ${offline.url}"), rig.glue.starts())
    }

    /** The page's own navigation to the floor is refused, and is no error of the engine's. */
    @Test fun thePagesNavigationToTheFloorIsRefusedInSilence() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.load(offline)
        rig.send(policy(1, offline.url))
        rig.send(GlueEvent.Committed(1, rig.page(offline.url), 0.5f))
        rig.send(policy(2, WebKitUrls.FLOOR_URL, NavigationKind.Link))
        assertEquals("answerPolicy 2 false", rig.glue.last("answerPolicy"))
        assertTrue(rig.events.none { it is EngineEvent.Error }, "${rig.events}")
    }

    /**
     * A failure WebKit reports without its URL (seen on iOS for a retry after a host that doesn't resolve) names the
     * content the engine was loading: never WebKit's committed document, which is still the floor or an earlier page.
     */
    @Test fun aFailureWithoutItsUrlNamesTheContentBeingLoaded() = runTest {
        val rig = Rig(this)
        rig.engine.configure(setup)
        rig.engine.load(offline)
        rig.send(GlueEvent.Failed(1, null, "NSURLErrorDomain", -1003, "no such host"))
        assertEquals(listOf<String?>(offline.url), rig.events.filterIsInstance<EngineEvent.Error>().map { it.error.url })
    }
}
