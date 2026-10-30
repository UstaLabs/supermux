package dev.supermux.webview.engine.windows

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.linux.RecordingClient
import dev.supermux.webview.engine.pageSetup
import dev.supermux.webview.localOrigin
import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** One engine on a FakeWinPage, as the runtime holds it: subscribed, with a client, a surface composed. */
internal class WinRig(val test: TestScope, profile: WebProfile = WebProfile.Ephemeral) {
    val page = FakeWinPage()
    val scope = CoroutineScope(UnconfinedTestDispatcher(test.testScheduler) + SupervisorJob())
    val engine = WindowsWebEngine(EngineConfig(profile), page, Wv2Scripts("__wv_rtest", "__wv_ctest"), scope)
    val events = mutableListOf<EngineEvent>()
    val client = RecordingClient()
    var collector: Job? = null
    private var nextNavigation = 100L

    fun warm() {
        var ready: Boolean? = null
        scope.launch { ready = engine.warmUp() }
        // An ephemeral slot is wiped before its first lease (D8).
        page.calls.lastOrNull { it.startsWith("clearData") }?.let { engine.onEvent(WinEvent.Result(it.substringAfter(' ').toLong(), true, "null")) }
        check(ready == true) { "warmUp didn't finish" }
        subscribe()
        engine.client = client
        engine.onSurfaceAttached(true)
    }

    fun subscribe() {
        collector = scope.launch { engine.events.collect { events += it } }
    }

    fun unsubscribe() {
        collector?.cancel()
        collector = null
    }

    /** Parks the page as the runtime does: its view stops listening, lets its client go and hides it; its surface leaves. */
    fun park() {
        unsubscribe()
        engine.client = null
        engine.setVisible(false)
        engine.onSurfaceAttached(false)
    }

    /**
     * WebView2 asks about a main-frame navigation the way the glue does: synchronously, then reports its start. By default
     * it is flagged user-initiated, as WebView2 flags every navigation the host starts (156: Navigate, Reload, GoBack).
     */
    fun starts(url: String, flags: Int = WinGlue.NAV_MAIN_FRAME or WinGlue.NAV_USER or ((WinGlue.KIND_NEW_DOCUMENT + 1) shl 4), id: Long = nextNavigation++): Long {
        val epoch = page.epoch
        val verdict = WinGlue.decideNavigation(page.token, epoch, flags, url)
        engine.onEvent(WinEvent.NavStarting(epoch, id, url, flags and WinGlue.NAV_MAIN_FRAME != 0, flags and WinGlue.NAV_REDIRECT != 0,
            flags and WinGlue.NAV_USER != 0, ((flags shr 4) and 0xF) - 1, verdict))
        return id
    }

    /** The lease's setup, the load, its start, its commit and its document's `start`. */
    fun show(content: WebContent, url: String, doc: String = "0123456789abcdef"): Long {
        engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOfNotNull(content.localOrigin?.origin)))
        engine.load(content)
        val id = starts(url)
        engine.onEvent(WinEvent.Committed(page.epoch, id, false, url, page.frameSeq()))
        engine.onEvent(WinEvent.Message(page.epoch, "$doc start"))
        return id
    }

    /** The document script's `paint` (Task 19 hands off on it; before Task 19 the load does). */
    /** The document script's paint; with [agoMillis], how long before the glue had it ([arrivedNs]) it was presented. */
    fun paints(doc: String = "0123456789abcdef", agoMillis: Double? = null, arrivedNs: Long = 0L) {
        engine.onEvent(WinEvent.Message(page.epoch, if (agoMillis == null) "$doc paint" else "$doc paint $agoMillis", arrivedNs))
    }

    /** A surface draws the newest frame, then the Compose frames a handoff waits pass (Linux's drawnInPlace). */
    fun drawnInPlace() {
        if (page.frame == null) page.frame = Triple(320, 480, 1L)
        engine.onSurfacePlacedForTests(320, 480)
        engine.onSurfaceFrame(full = false)
        engine.onSurfaceDrawn()
        repeat(2) { engine.onSurfaceFrame(full = false) }
    }

    fun close() = scope.cancel()
}

class WindowsWebEngineTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    @AfterTest fun quiet() = WinPages.resetForTests()

    @Test fun aLoadSwapsTheDocumentScriptBeforeItNavigates() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val script = rig.page.calls.indexOfLast { it.startsWith("setScript") }
        val navigate = rig.page.calls.indexOfLast { it.startsWith("navigate") }
        assertTrue(script in 0 until navigate, "${rig.page.calls}")
        assertEquals(rig.page.epoch, rig.page.navigations.last().first, "the same epoch, natively and here")
        assertEquals("demo.invalid", rig.page.served?.first)
        assertEquals("<p>hi</p>", rig.page.served?.second?.decodeToString(), "Html is served by the glue itself (D7)")
        rig.close()
    }

    @Test fun ourCommitIsHeardAndTheLastDocumentsLateOnesAreNot() = runTest {
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        val first = rig.show(demo, demo.origin.url)
        val other = WebContent.Url("https://b.example/")
        val oldEpoch = rig.page.epoch
        rig.engine.load(other)
        rig.events.clear()
        // The last document's commit, arriving late: its epoch is over.
        rig.engine.onEvent(WinEvent.Committed(oldEpoch, first, false, demo.origin.url, 0))
        // A navigation of this epoch before ours started: still the last document's.
        rig.engine.onEvent(WinEvent.Committed(rig.page.epoch, 999, false, "https://late.example/", 0))
        assertTrue(rig.events.none { it is EngineEvent.UrlChanged }, "${rig.events}")
        val ours = rig.starts("https://b.example/")
        rig.engine.onEvent(WinEvent.Committed(rig.page.epoch, ours, false, "https://b.example/", 0))
        assertEquals(EngineEvent.UrlChanged("https://b.example/"), rig.events.first { it is EngineEvent.UrlChanged })
        rig.close()
    }

    @Test fun titlesAndSameDocumentUrlsAreReported() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.Title(rig.page.epoch, "titled"))
        rig.engine.onEvent(WinEvent.SourceChanged(rig.page.epoch, false, demo.origin.url + "#moved"))
        assertTrue(EngineEvent.TitleChanged("titled") in rig.events)
        assertTrue(EngineEvent.UrlChanged(demo.origin.url + "#moved") in rig.events)
        rig.close()
    }

    @Test fun aFailedLoadSaysWhyAndACancelledOneSaysNothing() = runTest {
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        rig.engine.load(WebContent.Url("https://nowhere.invalid/"))
        var id = rig.starts("https://nowhere.invalid/")
        rig.engine.onEvent(WinEvent.Completed(rig.page.epoch, id, false, WinGlue.ERROR_CANCELED, 0, "https://nowhere.invalid/"))
        assertTrue(rig.events.none { it is EngineEvent.Error }, "a replaced navigation is no error")
        rig.engine.load(WebContent.Url("https://expired.example/"))
        id = rig.starts("https://expired.example/")
        rig.engine.onEvent(WinEvent.Completed(rig.page.epoch, id, false, 2, 0, "https://expired.example/"))
        assertTrue(rig.events.any { it is EngineEvent.Error && it.error is WebError.Certificate }, "${rig.events}")
        rig.engine.load(WebContent.Url("https://gone.example/"))
        id = rig.starts("https://gone.example/")
        rig.engine.onEvent(WinEvent.Completed(rig.page.epoch, id, false, 13, 0, "https://gone.example/"))
        assertTrue(rig.events.any { it is EngineEvent.Error && it.error is WebError.Network })
        rig.close()
    }

    @Test fun anErrorPageIsNoCommit() = runTest {
        // L3's ground: WebView2 commits a blank error document when its own error page is off; it isn't the content's.
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        rig.engine.load(WebContent.Url("https://nowhere.invalid/"))
        val id = rig.starts("https://nowhere.invalid/")
        rig.engine.onEvent(WinEvent.Committed(rig.page.epoch, id, true, "https://nowhere.invalid/", 0))
        assertTrue(rig.events.none { it is EngineEvent.UrlChanged }, "${rig.events}")
        rig.close()
    }

    @Test fun aFailedLoadsErrorPageHasNoBridgeAndTheLoadEndsInAnError() = runTest {
        // WebView2 156: a failed load commits an error page, which runs the document script. It isn't the content: none of
        // its words are heard, before its commit or after, and the load ends with an error (L3).
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOf("https://nowhere.invalid")))
        rig.engine.load(WebContent.Url("https://nowhere.invalid/"))
        val id = rig.starts("https://nowhere.invalid/")
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "0123456789abcdef start"))
        rig.engine.onEvent(WinEvent.Committed(rig.page.epoch, id, true, "https://nowhere.invalid/", 0))
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "0123456789abcdef msg {\"t\":\"event\",\"n\":\"a\"}"))
        rig.engine.onEvent(WinEvent.Completed(rig.page.epoch, id, false, 13, 0, "https://nowhere.invalid/"))
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "0123456789abcdef paint"))
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "0123456789abcdef msg {\"t\":\"event\",\"n\":\"b\"}"))
        rig.engine.onEvent(WinEvent.Completed(rig.page.epoch, 777, true, 0, 200, "https://nowhere.invalid/"))
        rig.drawnInPlace()
        assertTrue(rig.events.none { it is EngineEvent.PageMessage || it is EngineEvent.UrlChanged || it == EngineEvent.FirstPaint }, "${rig.events}")
        assertTrue(rig.events.any { it is EngineEvent.Error && it.error is WebError.Network }, "${rig.events}")
        rig.close()
    }

    @Test fun anErrorPageOverAPagesOwnFailedNavigationHasNoBridgeEither() = runTest {
        // The page follows a link that fails: WebView2 puts its error page in the document's place, which runs the
        // document script under the same epoch. It never speaks for the document it replaced, and the failure is said.
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOf("https://a.example")))
        val link = rig.starts("https://a.example/gone", WinGlue.NAV_MAIN_FRAME or WinGlue.NAV_USER or ((WinGlue.KIND_NEW_DOCUMENT + 1) shl 4))
        rig.engine.onEvent(WinEvent.Committed(rig.page.epoch, link, true, "https://a.example/gone", 0))
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "fedcba9876543210 start"))
        rig.engine.onEvent(WinEvent.Completed(rig.page.epoch, link, false, 13, 0, "https://a.example/gone"))
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "fedcba9876543210 msg {\"t\":\"event\",\"n\":\"x\"}"))
        assertTrue(rig.events.none { it is EngineEvent.PageMessage }, "${rig.events}")
        assertEquals(EngineEvent.Error(WebError.Network("https://a.example/gone", "load failed (WebView2 status 13)")), rig.events.last { it is EngineEvent.Error })
        rig.close()
    }

    @Test fun anHttpErrorIsReportedOnceThePageIsShown() = runTest {
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        val id = rig.show(WebContent.Url("https://a.example/missing"), "https://a.example/missing")
        rig.engine.onEvent(WinEvent.Completed(rig.page.epoch, id, true, 0, 404, "https://a.example/missing"))
        rig.paints()
        assertTrue(rig.events.none { it is EngineEvent.Error }, "not before the page is shown")
        rig.drawnInPlace()
        assertEquals(EngineEvent.Error(WebError.Http("https://a.example/missing", 404)), rig.events.first { it is EngineEvent.Error })
        rig.close()
    }

    @Test fun aRendererCrashEndsTheEngineAndItTakesNoMoreLeaseCalls() = runTest {
        // L18: a crashed engine starts nothing (a reload would start a new renderer).
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER))
        assertEquals(EngineEvent.Crashed, rig.events.last())
        assertFalse(rig.engine.isUsable)
        rig.page.calls.clear()
        rig.engine.reload()
        rig.engine.load(demo)
        assertTrue(rig.page.calls.none { it.startsWith("navigate") || it.startsWith("go") }, "${rig.page.calls}")
        rig.close()
    }

    /**
     * A parked page whose renderer dies tells the pool, through the onUnusable it set as it parked the page, so the pool
     * releases it at once rather than at the next lease: once, unusable by then. WebView2 reporting a death again (its
     * browser's, after the renderer's) tells nothing more, nor does the release that follows.
     */
    @Test fun aParkedPageWhoseRendererDiesTellsThePoolOnce() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.park()
        val told = mutableListOf<Boolean>()
        rig.engine.onUnusable = { told += rig.engine.isUsable }
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER))
        assertEquals(listOf(false), told, "told once, and unusable by then")
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_BROWSER))
        rig.engine.release()
        assertEquals(listOf(false), told, "told of one death, once")
        rig.close()
    }

    /** A lost device ends the page as a crash does: the pool that parked it is told. */
    @Test fun aParkedPageWhoseDeviceIsLostTellsThePool() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.park()
        var told = 0
        rig.engine.onUnusable = { told++ }
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_NO_DEVICE))
        assertEquals(1, told)
        assertFalse(rig.engine.isUsable)
        rig.close()
    }

    /** A release is no death: the pool gives the page back itself, and isn't told; nor of a crash WebView2 reports after. */
    @Test fun aReleaseTellsThePoolNothing() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.park()
        var told = 0
        rig.engine.onUnusable = { told++ }
        rig.engine.release()
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER))
        assertEquals(0, told)
        rig.close()
    }

    @Test fun aSubframesRendererOrWebView2sOwnGpuProcessIsNoCrash() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_FRAME_RENDERER))
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_GPU))
        assertTrue(rig.engine.isUsable)
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_NO_DEVICE))
        assertFalse(rig.engine.isUsable, "a page the glue can't draw is crashed")
        rig.close()
    }

    @Test fun eachEngineKeepsItsOwnTheme() = runTest {
        // L17: dark mode is the engine's own (CDP emulation), never a profile's or the process's.
        val dark = WinRig(this, WebProfile.Default)
        val light = WinRig(this, WebProfile.Default)
        dark.warm()
        light.warm()
        dark.engine.configure(WebSettings.Default.pageSetup(dark = true, bridgeOrigins = emptySet()))
        light.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        assertTrue(dark.page.configured!!.first and WinGlue.CONF_DARK != 0)
        assertTrue(light.page.configured!!.first and WinGlue.CONF_DARK == 0)
        dark.close()
        light.close()
    }

    @Test fun aHugePageControlledTitleOrUrlCostsNoBacktracking() = runTest {
        // L20: no regex with nested quantifiers on what a page controls.
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val title = "(".repeat(500_000) + "a".repeat(500_000) + "!"
        val started = System.nanoTime()
        rig.engine.onEvent(WinEvent.Title(rig.page.epoch, title))
        rig.engine.onEvent(WinEvent.SourceChanged(rig.page.epoch, false, demo.origin.url + "#" + title))
        assertTrue(System.nanoTime() - started < 500_000_000L, "took ${(System.nanoTime() - started) / 1_000_000} ms")
        rig.close()
    }

    @Test fun releaseIsFinal() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.release()
        assertTrue(rig.page.released)
        assertFalse(rig.engine.isUsable)
        assertNull(rig.engine.evaluate("1"))
        rig.page.calls.clear()
        rig.engine.load(demo)
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        assertTrue(rig.page.calls.isEmpty(), "${rig.page.calls}")
        assertEquals(WinGlue.REFUSE, WinGlue.decideNavigation(rig.page.token, 1, WinGlue.NAV_MAIN_FRAME, "https://a.example/"))
        rig.close()
    }

    @Test fun aReleasedEngineIsCollected() = runTest {
        // L5: nothing the glue or the routing holds keeps a released engine.
        var rig: WinRig? = WinRig(this)
        rig!!.warm()
        rig.show(demo, demo.origin.url)
        val engine = WeakReference(rig.engine)
        rig.engine.release()
        rig.close()
        rig = null
        repeat(20) {
            if (engine.get() == null) return@runTest
            System.gc()
            Thread.sleep(25)
        }
        assertNull(engine.get(), "the released engine is still reachable")
    }
}
