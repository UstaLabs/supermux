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
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.computeCutouts
import kotlinx.coroutines.CompletableDeferred
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

    /** An engine on [scope]'s virtual clock, its events collected, played by hand as WebKit would play it. */
    private inner class Rig(val scope: TestScope, profile: WebProfile = WebProfile.Ephemeral) {
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
        val node = MacSurfaceNode(engine, inList = false)
        val events = mutableListOf<EngineEvent>()

        init {
            scope.backgroundScope.launch { engine.events.collect { events += it } }
        }

        fun page(url: String?, back: List<String> = emptyList(), title: String? = null, loading: Boolean = false) =
            WebKitPage(url, title, back, emptyList(), loading)

        fun send(event: GlueEvent) {
            engine.onGlueEvent(event)
            scope.runCurrent()
        }

        /** Answers the newest call of [kind] that carries an id with [result]. */
        fun answer(kind: String, result: (Long) -> GlueEvent) {
            scope.runCurrent()
            send(result(glue.ids(kind).last()))
        }

        fun tick(inWindow: Boolean = true) = answer("tick") { GlueEvent.Tick(it, inWindow) }

        fun repainted() = answer("repaint") { GlueEvent.Result(it, true, null, null) }

        fun relay(epoch: Int, body: String, mainFrame: Boolean = true) =
            send(GlueEvent.Relay(epoch, mainFrame, body, "https", "c1.invalid", 0))

        fun place(x: Float = 0f, y: Float = 0f, width: Float = 640f, height: Float = 480f): Boolean {
            val bounds = Rect(x, y, x + width, y + height)
            return engine.surfacePlaced(node, 7L, bounds, bounds, 2f).also { scope.runCurrent() }
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

    private fun policy(question: Long, url: String, kind: NavigationKind = NavigationKind.Other) =
        GlueEvent.Policy(question, url, mainFrame = true, opensWindow = false, download = false, kind = kind)

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
        assertEquals("place 0.0 0.0 640.0 480.0 2.0 hidden=false", rig.glue.last("place"))
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

    @Test fun aMovingSlotShowsTheMotionCoverAndTakesNoInputUntilItSettles() = runTest {
        val rig = Rig(this)
        rig.live()
        val shot = async { rig.engine.snapshot() }
        rig.answer("snapshot") { GlueEvent.Snapshot(it, 4, 2, ByteArray(4 * 2 * 4) { 0x7f }) }
        assertNotNull(shot.await(), "the runtime's snapshot, kept as the motion cover")
        assertEquals("snapshot ${rig.glue.ids("snapshot").last()} 320.0", rig.glue.last("snapshot"), "640 px at 2x")
        val repaints = rig.glue.ids("repaint").size
        rig.place(y = 10f)
        assertIs<SurfaceDraw.Cover>(rig.engine.drawFor(rig.node), "covered from the frame it first moves (D2)")
        assertFalse(rig.glue.lastInteractive)
        advanceTimeBy(16)
        rig.place(y = 20f)
        advanceTimeBy(MacWebEngine.MOTION_SETTLE_MILLIS - 10)
        runCurrent()
        assertIs<SurfaceDraw.Cover>(rig.engine.drawFor(rig.node), "still moving")
        advanceTimeBy(20)
        rig.tick()
        rig.tick()
        assertEquals(SurfaceDraw.Hole, rig.engine.drawFor(rig.node))
        assertTrue(rig.glue.lastInteractive)
        assertEquals(repaints, rig.glue.ids("repaint").size, "a move alone draws nothing again")
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
}
