package dev.supermux.webview.engine.windows

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.engine.EngineEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * A crashed engine closes its page, its WebView2 controller, as it reports the crash, whatever ended it: the browser then
 * ends the renderer at once, a hung one too, whatever WebView2's crash reporter does with it (it kept a frozen one over
 * 2 s while it wrote its report). Nothing later reaches the closed page but its release: the engine is never used again.
 */
class WindowsWebEngineCrashTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))
    private val BLUE = 0xFF3366CC.toInt()

    @AfterTest fun quiet() = WinPages.resetForTests()

    /** Each way the engine learns its page is dead or can't be drawn: the pool it tells finds the page closed already. */
    @Test fun everyDeathClosesThePageOnceBeforeAnyoneHearsOfIt() = runTest {
        val deaths: List<Pair<String, (WinRig) -> WinEvent>> = listOf(
            "the renderer" to { _ -> WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER) },
            "the browser" to { _ -> WinEvent.ProcessFailed(WinGlue.FAILED_BROWSER) },
            "the device" to { _ -> WinEvent.ProcessFailed(WinGlue.FAILED_NO_DEVICE) },
            "the document script" to { rig -> WinEvent.ScriptReady(rig.page.epoch, 0x80004005L) },
        )
        for ((death, event) in deaths) {
            val rig = WinRig(this)
            rig.warm()
            rig.show(demo, demo.origin.url)
            var closedWhenTold = -1
            rig.engine.onUnusable = { closedWhenTold = rig.page.closes }
            rig.engine.onEvent(event(rig))
            assertEquals(1, closedWhenTold, "$death: closed before the pool was told")
            assertEquals(EngineEvent.Crashed, rig.events.last(), death)
            rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_BROWSER))
            assertEquals(1, rig.page.closes, "$death: a second death closes nothing more")
            rig.close()
        }
    }

    /**
     * A page the heartbeat finds silent: CDP's Page.crash first, which ends the renderer even where another page shares
     * it, then the close, which ends it at once.
     */
    @Test fun aHungPageTheHeartbeatFindsIsCrashedThenClosed() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        advanceTimeBy(3_100)          // the heartbeat asks
        advanceTimeBy(5_100)          // and hears nothing
        val crash = rig.page.calls.indexOf("devtools Page.crash")
        assertTrue(crash in 0 until rig.page.calls.indexOf("close"), "${rig.page.calls}")
        assertEquals(1, rig.page.closes)
        assertEquals(EngineEvent.Crashed, rig.events.last())
        rig.close()
    }

    @Test fun aPageWebView2FindsUnresponsiveIsClosedOnceItsGraceIsOver() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_UNRESPONSIVE))
        advanceTimeBy(1_999)
        assertEquals(0, rig.page.closes, "a moment's grace first")
        advanceTimeBy(2)
        assertEquals(1, rig.page.closes)
        rig.close()
    }

    @Test fun aHungPageAResetFindsIsClosed() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.resets(answers = false)
        advanceTimeBy(1_600)
        assertEquals(false, result())
        assertEquals(1, rig.page.closes)
        rig.close()
    }

    /** A crash while a reset waits for the page to answer fails the reset then, and the reset sends the page nothing more. */
    @Test fun aResetTheCrashCutsShortFailsAtOnce() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.resets(answers = false)
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER))
        assertEquals(false, result())
        assertEquals(listOf<String>(), rig.page.afterClose)
        rig.close()
    }

    /** The pool's discard: the release of a page closed at its crash forgets it, and sends a silent one no Page.crash (V10). */
    @Test fun aReleaseAfterTheCloseOnlyForgetsThePage() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        advanceTimeBy(3_100)
        advanceTimeBy(5_100)          // silent, so ended, and closed
        rig.engine.release()
        assertEquals(listOf("release"), rig.page.afterClose)
        assertTrue(rig.page.released)
        rig.close()
    }

    /** onUnusable: a pool that releases the parked engine as it is told releases a page closed already. */
    @Test fun aPoolThatReleasesTheEngineAsItIsToldReleasesAClosedPage() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.park()
        rig.engine.onUnusable = { rig.engine.release() }
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER))
        assertEquals(1, rig.page.closes)
        assertEquals(listOf("release"), rig.page.afterClose)
        rig.close()
    }

    /**
     * Until the view lets the crashed engine go, its surface is composed: it draws, is placed, its window moves; the view
     * may ask for a script, the page's state, its focus or a stop. None of it reaches the closed page, nothing waits for
     * it, and the snapshot is still the page's last frame.
     */
    @Test fun nothingAfterTheCrashReachesTheClosedPageButCopiesOfItsLastFrame() = runTest {
        val rig = WinRig(this)
        rig.engine.windows = FakeSurfaceWindows
        rig.warm()
        rig.page.fill = BLUE
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.paints()
        rig.drawnInPlace()
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER))
        rig.engine.onSurfaceFrame(full = true)
        rig.engine.onSurfacePlaced(null, 0, 0, 320, 480, now = 0L)
        rig.engine.onSurfacePlaced(null, 0, 0, 300, 400, now = 1L)
        rig.engine.settlePlacement(1L + WinDefaults.SIZE_SETTLE_NANOS)
        rig.engine.onWindowMoved()
        rig.engine.requestFocus()
        rig.engine.stop()
        val before = currentTime
        assertNull(rig.engine.evaluate("1"))
        rig.engine.saveState()
        assertEquals(before, currentTime, "nothing waited for the closed page")
        val cover = assertNotNull(rig.engine.snapshot(), "the page's last frame")
        assertEquals(BLUE, cover.toPixelMap()[0, 0].toArgb())
        rig.engine.onSurfaceAttached(false)
        rig.engine.release()
        assertEquals(1, rig.page.closes)
        assertEquals(listOf("release"), rig.page.afterClose)
        rig.close()
    }

    /**
     * What waited on the page at its crash is answered then, while it can still take the answers: a script call with no
     * answer, at once; the client's dialog with its default; a file the provider hadn't given with a 404. The client's
     * late answer, and the release, reach nothing.
     */
    @Test fun whatWaitedOnThePageIsAnsweredAsItCloses() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = object : WebResourceProvider {
            override suspend fun load(path: String): WebResource? {
                gate.await()
                return null
            }
        }
        val rig = WinRig(this)
        rig.warm()
        rig.show(WebContent.Bundle(WebOrigin("slow"), slow), "https://slow.invalid/index.html")
        rig.engine.onEvent(WinEvent.Resource(rig.page.epoch, 40, "https://slow.invalid/a.js", "GET"))
        rig.client.gate = CompletableDeferred()
        rig.engine.onEvent(WinEvent.Dialog(rig.page.epoch, 9, 1, "sure?", null, "https://slow.invalid/index.html"))
        var result: String? = "pending"
        rig.scope.launch { result = rig.engine.evaluate("document.title") }
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER))
        assertNull(result, "the closed page will never answer the script: no wait for its timeout")
        assertEquals(0 to null, rig.page.answers[9], "the dialog dismissed")
        assertEquals(404, rig.page.responses[40]?.first, "the file a 404")
        assertEquals(listOf<String>(), rig.page.afterClose, "all of it before the close")
        rig.client.gate?.complete(Unit)
        gate.complete(Unit)
        rig.engine.release()
        assertEquals(listOf("release"), rig.page.afterClose)
        rig.close()
    }

    /**
     * What the page said before the close, heard after the crash (the glue's events cross to the EDT): the glue answered
     * its questions as it closed, so the client is asked nothing, and nothing more of the page is reported.
     */
    @Test fun whatThePageSaidBeforeItsCloseIsNotHeardAfterTheCrash() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER))
        rig.events.clear()
        rig.client.asked.clear()
        rig.engine.onEvent(WinEvent.Dialog(rig.page.epoch, 9, 0, "hello", null, demo.origin.url))
        rig.engine.onEvent(WinEvent.Permission(rig.page.epoch, 10, 2, true, demo.origin.url))
        rig.engine.onEvent(WinEvent.Title(rig.page.epoch, "late"))
        rig.engine.onEvent(WinEvent.Resource(rig.page.epoch, 11, demo.origin.url + "x.js", "GET"))
        assertTrue(rig.client.asked.isEmpty(), "${rig.client.asked}")
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        assertEquals(listOf<String>(), rig.page.afterClose)
        rig.close()
    }

    /** A file chooser the page opened as it crashed: the client is never asked for files for a page that is gone. */
    @Test fun aFileChooserOpenedAsThePageCrashesAsksNoOne() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.DevToolsEvent(rig.page.epoch, "Page.fileChooserOpened", "{\"backendNodeId\":5,\"mode\":\"selectSingle\"}"))
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_RENDERER))
        advanceTimeBy(20_000)
        assertTrue(rig.client.asked.none { it is FileChooserRequest }, "${rig.client.asked}")
        assertEquals(listOf<String>(), rig.page.afterClose)
        rig.close()
    }
}
