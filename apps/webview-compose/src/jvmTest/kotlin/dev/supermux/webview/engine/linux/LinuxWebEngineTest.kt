package dev.supermux.webview.engine.linux

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
import dev.supermux.webview.WebSettings
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.pageSetup
import dev.supermux.webview.localOrigin
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

internal class RecordingClient : EngineClient {
    val asked = mutableListOf<Any>()
    var navigation = NavigationDecision.Allow

    /** Decides each navigation instead of [navigation] when set: a policy that tells frames apart. */
    var decide: ((NavigationRequest) -> NavigationDecision)? = null
    var dialog: DialogResult = DialogResult.Dismissed
    var files: List<String>? = null
    var permission = false
    var newWindow = NewWindowDecision.Block

    /** Holds every suspending answer until completed: a client that hasn't answered yet. */
    var gate: CompletableDeferred<Unit>? = null

    override fun onNavigation(request: NavigationRequest): NavigationDecision {
        asked += request
        return decide?.invoke(request) ?: navigation
    }
    override suspend fun onDialog(dialog: WebDialog): DialogResult {
        asked += dialog
        gate?.await()
        return this.dialog
    }
    override suspend fun onFileChooser(request: FileChooserRequest): List<String>? {
        asked += request
        gate?.await()
        return files
    }
    override suspend fun onPermission(request: PermissionRequest): Boolean {
        asked += request
        gate?.await()
        return permission
    }
    override suspend fun onDownload(request: DownloadRequest): Boolean {
        asked += request
        return false
    }
    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision {
        asked += request
        return newWindow
    }
}

/** An engine on a fake view, warmed up (floor loaded, its state kept, the user agent learnt), its events collected. */
internal class EngineRig(val test: TestScope, profile: WebProfile = WebProfile.Ephemeral) {
    val view = FakeWpeView()
    val scope = CoroutineScope(UnconfinedTestDispatcher(test.testScheduler) + SupervisorJob())
    /** What the engine told WebKit the system's clipboard holds, in order. */
    val clipboardTold = mutableListOf<String?>()
    val engine = LinuxWebEngine(
        EngineConfig(profile), view, WebKitScripts("webview", "webview-out-t", "webview-in-t"), scope,
        WpeClipboard({ null }) { clipboardTold += it },
    )
    val events = mutableListOf<EngineEvent>()
    val client = RecordingClient()

    /** The subscription [warm] starts, as the runtime's view holds it: cancelled when the view parks the page. */
    var collector: Job? = null

    fun warm() {
        var ready: Boolean? = null
        scope.launch { ready = engine.warmUp() }
        engine.onEvent(WpeEvent.Policy(1, WebKitUrls.FLOOR_URL, NavigationKind.Other, false, false, false))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, WebKitUrls.FLOOR_URL))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, WebKitUrls.FLOOR_URL))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_FINISHED, WebKitUrls.FLOOR_URL))
        val (request, _, _) = view.evaluations.last()
        engine.onEvent(WpeEvent.Eval(request, true, "\"Mozilla/5.0 WPE\""))
        check(ready == true) { "warmUp didn't finish" }
        collector = scope.launch { engine.events.collect { events += it } }
        engine.client = client
        // A surface composed, as when a view shows the engine: drawnInPlace draws on it.
        engine.onSurfaceAttached(true)
    }

    /** The surface draws WebKit's newest frame, then the Compose frames a handoff waits pass. */
    fun drawnInPlace() {
        if (view.frame == null) view.frame = Triple(320, 480, 1L)
        engine.onSurfaceFrame(full = false)
        engine.onSurfaceDrawn()
        repeat(2) { engine.onSurfaceFrame(full = false) }
    }

    /** The lease's setup for [content], then the load, its commit and its relay's start. */
    fun show(content: WebContent, url: String, token: String = "0123456789abcdef") {
        engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOfNotNull(content.localOrigin?.origin)))
        engine.load(content)
        engine.onEvent(WpeEvent.Policy(2, url, NavigationKind.Other, false, false, false))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, url))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, url))
        engine.onEvent(WpeEvent.Relay("$token start"))
    }

    fun close() = scope.cancel()
}

class LinuxWebEngineTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    @Test fun warmUpLoadsTheFloorKeepsItsStateAndLearnsTheUserAgent() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        assertContains(rig.view.calls, "loadUri ${WebKitUrls.FLOOR_URL}")
        assertEquals(WpeNative.USE, rig.view.decisions[1])
        assertEquals(WebEngineWorld.WORLD, rig.view.evaluations.last().third)
        assertTrue(rig.events.isEmpty(), "nothing from before the first lease: ${rig.events}")
        rig.close()
    }

    @Test fun configureInstallsTheRelayInTheWorldTheShimWithScriptsAndTheProcessDarkMode() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.configure(WebSettings.Default.copy(userAgentSuffix = "demo/1").pageSetup(dark = true, bridgeOrigins = setOf("https://demo.invalid")))
        val (relay, shim, world, handler, pageScripts, _, background, agent) = rig.view.configured!!.let { Configured(it) }
        assertTrue((relay as String).contains("https://demo.invalid"))
        assertTrue(shim != null)
        assertEquals(WebEngineWorld.WORLD, world)
        assertEquals("webview", handler)
        assertEquals(true, pageScripts)
        assertEquals(0xFF000000.toInt(), background)
        assertEquals("Mozilla/5.0 WPE demo/1", agent)
        assertEquals(true, rig.view.dark)
        rig.engine.configure(WebSettings.Default.copy(javaScriptEnabled = false).pageSetup(dark = false, bridgeOrigins = emptySet()))
        assertNull(rig.view.configured!![1], "no shim without scripts")
        assertEquals(false, rig.view.configured!![4])
        rig.close()
    }

    @Test fun aLoadIsTheEnginesOwnAndItsCommitIsReported() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        assertContains(rig.view.calls, "loadHtml ${demo.origin.url}")
        assertEquals(WpeNative.USE, rig.view.decisions[2])
        assertContains(rig.events, EngineEvent.UrlChanged(demo.origin.url))
        assertContains(rig.events, EngineEvent.TitleChanged(null))
        rig.engine.onEvent(WpeEvent.Title("Hello"))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_FINISHED, demo.origin.url))
        assertEquals(EngineEvent.TitleChanged("Hello"), rig.events.last { it is EngineEvent.TitleChanged })
        assertEquals(EngineEvent.Progress(null), rig.events.last { it is EngineEvent.Progress })
        rig.close()
    }

    @Test fun theLastDocumentsLateEventsAreDropped() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val other = WebContent.Html("<p>b</p>", WebOrigin("other"))
        rig.engine.load(other)
        rig.events.clear()
        rig.engine.onEvent(WpeEvent.Title("late"))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_FINISHED, demo.origin.url))
        rig.engine.onEvent(WpeEvent.Relay("0123456789abcdef paint"))
        assertTrue(rig.events.none { it is EngineEvent.TitleChanged || it == EngineEvent.FirstPaint }, "${rig.events}")
        rig.close()
    }

    @Test fun theRelaysPaintIsItsDocumentsFirstPaintOnly() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WpeEvent.Relay("fedcba9876543210 paint"))
        assertFalse(EngineEvent.FirstPaint in rig.events, "another document's paint")
        rig.engine.onEvent(WpeEvent.Relay("0123456789abcdef paint"))
        rig.drawnInPlace()
        assertEquals(1, rig.events.count { it == EngineEvent.FirstPaint })
        rig.close()
    }

    @Test fun aPagesHttpNavigationIsAskedAtItsResponse() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.client.navigation = NavigationDecision.Block
        rig.engine.onEvent(WpeEvent.Policy(5, "https://b.example/", NavigationKind.Link, false, true, false))
        assertEquals(WpeNative.USE, rig.view.decisions[5])
        assertTrue(rig.client.asked.isEmpty())
        rig.engine.onEvent(WpeEvent.Response(6, "https://b.example/", mainFrame = true, canShow = true, attachment = false, status = 200, mime = "text/html", filename = null))
        assertEquals(listOf<Any>(NavigationRequest("https://b.example/", isMainFrame = true, isUserGesture = true)), rig.client.asked)
        assertEquals(WpeNative.IGNORE, rig.view.decisions[6])
        rig.close()
    }

    @Test fun aDownloadIsRefusedAndTheClientTold() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.engine.onEvent(WpeEvent.Response(7, "https://a.example/f.zip", mainFrame = true, canShow = false, attachment = true, status = 200, mime = "application/zip", filename = "f.zip"))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[7])
        assertContains(rig.client.asked, DownloadRequest("https://a.example/f.zip", "f.zip", "application/zip", null))
        rig.close()
    }

    @Test fun aFailedLoadIsAnErrorButACancelledOneIsNot() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.engine.onEvent(WpeEvent.LoadFailed(302, cancelled = true, tls = false, policy = false, cannotShow = false, uri = "https://a.example/", message = "cancelled"))
        assertTrue(rig.events.none { it is EngineEvent.Error })
        rig.engine.onEvent(WpeEvent.LoadFailed(1, cancelled = false, tls = true, policy = false, cannotShow = false, uri = "https://a.example/", message = "bad certificate"))
        assertEquals(EngineEvent.Error(WebError.Certificate("https://a.example/", "bad certificate")), rig.events.last { it is EngineEvent.Error })
        rig.close()
    }

    @Test fun historyCountsWhatComesAfterTheFloor() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.engine.onEvent(WpeEvent.History(canGoBack = true, canGoForward = false, back = listOf(WebKitUrls.FLOOR_URL), forward = emptyList()))
        assertEquals(EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false), rig.events.last { it is EngineEvent.HistoryChanged })
        rig.engine.onEvent(WpeEvent.History(true, false, listOf(WebKitUrls.FLOOR_URL, "https://a.example/"), emptyList()))
        assertEquals(EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false), rig.events.last { it is EngineEvent.HistoryChanged })
        rig.close()
    }

    @Test fun theFloorAndTheLeasesBundleAreServedAndNothingElse() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        val bundle = WebContent.Bundle(WebOrigin("app"), WebResourceProvider { path -> if (path == "index.html") WebResource("<p/>".encodeToByteArray(), "text/html") else null })
        rig.show(bundle, WebKitUrls.bundleUrl(bundle.origin, "index.html"))
        rig.engine.onEvent(WpeEvent.Scheme(1, WebKitUrls.FLOOR_URL))
        rig.engine.onEvent(WpeEvent.Scheme(2, WebKitUrls.bundleUrl(bundle.origin, "index.html")))
        rig.engine.onEvent(WpeEvent.Scheme(3, WebKitUrls.bundleUrl(WebOrigin("other"), "index.html")))
        rig.engine.onEvent(WpeEvent.Scheme(4, WebKitUrls.bundleUrl(bundle.origin, "missing.js")))
        assertEquals(200, rig.view.finished[1]?.first)
        assertEquals("<p/>", rig.view.finished[2]?.second?.decodeToString())
        assertEquals(0, rig.view.finished[3]?.first, "another bundle fails as an unknown host does")
        assertEquals(404, rig.view.finished[4]?.first)
        rig.close()
    }

    @Test fun aCrashIsStickyAndReleaseDestroysTheView() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.onEvent(WpeEvent.Crashed(0))
        assertFalse(rig.engine.isUsable)
        assertContains(rig.events, EngineEvent.Crashed)
        rig.engine.release()
        assertTrue(rig.view.destroyed)
        rig.close()
    }
}

/** The fake's recorded configure, by name. A plain class: a data class would generate its own component1. */
private class Configured(val values: List<Any?>) {
    operator fun component1() = values[0]
    operator fun component2() = values[1]
    operator fun component3() = values[2]
    operator fun component4() = values[3]
    operator fun component5() = values[4]
    operator fun component6() = values[5]
    operator fun component7() = values[6]
    operator fun component8() = values[7]
}
