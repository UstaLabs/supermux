package dev.supermux.webview.engine.windows

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.pageSetup
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/** Starts a reset and plays WebView2's side of it: the answer to the ping, the floor, the history and the wipe. */
internal fun WinRig.resets(answers: Boolean = true, historyCleared: Boolean = true, wiped: Boolean = true): () -> Boolean? {
    var result: Boolean? = null
    scope.launch { result = engine.reset() }
    if (!answers) return { result }
    engine.onEvent(WinEvent.Result(page.executed.last().first, true, "1"))
    val floor = starts(Wv2Navigation.FLOOR_URL)
    engine.onEvent(WinEvent.Committed(page.epoch, floor, false, Wv2Navigation.FLOOR_URL, page.frameSeq()))
    engine.onEvent(WinEvent.Completed(page.epoch, floor, true, 0, 200, Wv2Navigation.FLOOR_URL))
    engine.onEvent(WinEvent.Result(page.devtoolsRequest("Page.resetNavigationHistory"), historyCleared, "{}"))
    if (historyCleared) {
        engine.onEvent(WinEvent.Result(page.devtoolsRequest("Page.getNavigationHistory"), true,
            "{\"currentIndex\":0,\"entries\":[{\"id\":1,\"url\":\"${Wv2Navigation.FLOOR_URL}\",\"title\":\"\"}]}"))
    }
    page.calls.lastOrNull { it.startsWith("clearData") }?.let { engine.onEvent(WinEvent.Result(it.substringAfter(' ').toLong(), wiped, "null")) }
    return { result }
}

class WindowsWebEngineResetTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    @AfterTest fun quiet() = WinPages.resetForTests()

    @Test fun aResetLeavesTheFloorAloneInTheHistoryAndWipesItsOwnProfile() = runTest {
        val rig = WinRig(this, WebProfile.Ephemeral)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val wipesBefore = rig.page.calls.count { it.startsWith("clearData") }
        assertEquals(true, rig.resets()())
        assertEquals(Wv2Navigation.FLOOR_URL, rig.page.navigations.last().second)
        assertTrue("hostBridge" !in rig.page.scripts.last().third, "the floor runs no shim")
        assertEquals(wipesBefore + 1, rig.page.calls.count { it.startsWith("clearData") })
        rig.close()
    }

    @Test fun whatAResetDidItselfIsNeverTheNextLeasesToHear() = runTest {
        // L25: reset's second epoch advance. With nobody subscribed, the floor's own events would wait for the next lease.
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.unsubscribe()
        rig.events.clear()
        assertEquals(true, rig.resets()())
        rig.subscribe()
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        rig.close()
    }

    @Test fun theNextLeasesHistoryBeginsWithItsOwnLoad() = runTest {
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        assertEquals(true, rig.resets()())
        rig.events.clear()
        rig.show(WebContent.Url("https://b.example/"), "https://b.example/")
        // WebView2 still has the floor behind the page until the lease's first commit clears it (D10).
        rig.engine.onEvent(WinEvent.History(rig.page.epoch, true, false))
        assertTrue(rig.events.filterIsInstance<EngineEvent.HistoryChanged>().none { it.canGoBack }, "${rig.events}")
        rig.engine.onEvent(WinEvent.Result(rig.page.devtoolsRequest("Page.resetNavigationHistory"), true, "{}"))
        rig.engine.goBack()
        assertTrue(rig.page.calls.none { it == "go 0 ${WinGlue.GO_BACK}" }, "nothing to go back to")
        rig.close()
    }

    @Test fun whereTheHistoryCantBeClearedAResetFailsAndNeverTriesAgain() = runTest {
        // V1's fallback: correct, slower: the pool rebuilds the engine.
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        assertEquals(false, rig.resets(historyCleared = false)())
        var again: Boolean? = null
        rig.scope.launch { again = rig.engine.reset() }
        assertEquals(false, again)
        rig.close()
    }

    @Test fun aResetMidLoadSucceeds() = runTest {
        // L4: the reset's own Stop cancels the load under way; that cancellation is the last epoch's, not the floor's.
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        rig.engine.load(WebContent.Url("https://slow.example/"))
        val loading = rig.starts("https://slow.example/")
        val loadEpoch = rig.page.epoch
        var result: Boolean? = null
        rig.scope.launch { result = rig.engine.reset() }
        rig.engine.onEvent(WinEvent.Completed(loadEpoch, loading, false, WinGlue.ERROR_CANCELED, 0, "https://slow.example/"))
        rig.engine.onEvent(WinEvent.Result(rig.page.executed.last().first, true, "1"))
        val floor = rig.starts(Wv2Navigation.FLOOR_URL)
        rig.engine.onEvent(WinEvent.Committed(rig.page.epoch, floor, false, Wv2Navigation.FLOOR_URL, 0))
        rig.engine.onEvent(WinEvent.Completed(rig.page.epoch, floor, true, 0, 200, Wv2Navigation.FLOOR_URL))
        rig.engine.onEvent(WinEvent.Result(rig.page.devtoolsRequest("Page.resetNavigationHistory"), true, "{}"))
        rig.engine.onEvent(WinEvent.Result(rig.page.devtoolsRequest("Page.getNavigationHistory"), true,
            "{\"currentIndex\":0,\"entries\":[{\"id\":1,\"url\":\"${Wv2Navigation.FLOOR_URL}\"}]}"))
        assertEquals(true, result)
        rig.close()
    }

    @Test fun aResetAnswersAPendingDialogAndAFailedResetTakesNoLease() = runTest {
        val rig = WinRig(this, WebProfile.Ephemeral)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.client.gate = kotlinx.coroutines.CompletableDeferred()
        rig.engine.onEvent(WinEvent.Dialog(rig.page.epoch, 9, 1, "sure?", null, demo.origin.url))
        assertEquals(false, rig.resets(wiped = false)(), "a wipe that failed fails the reset")
        assertEquals(0, rig.page.answers.getValue(9).first, "the dialog got its default")
        rig.page.calls.clear()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        rig.engine.load(demo)
        assertTrue(rig.page.calls.isEmpty(), "the pool destroys it; until then it takes nothing: ${rig.page.calls}")
        rig.close()
    }

    @Test fun aHungPageIsEndedAtReset() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.resets(answers = false)
        advanceTimeBy(1_600)
        assertEquals(false, result())
        assertTrue(rig.page.devtools.any { it.second == "Page.crash" })
        assertEquals(EngineEvent.Crashed, rig.events.last())
        rig.close()
    }

    @Test fun aPageThatStopsAnsweringOnScreenIsEndedAndReportedCrashed() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        advanceTimeBy(3_100)          // the heartbeat asks
        advanceTimeBy(5_100)          // and hears nothing
        assertTrue(rig.page.devtools.any { it.second == "Page.crash" })
        assertFalse(rig.engine.isUsable)
        rig.close()
    }

    @Test fun anUnresponsiveRendererThatAnswersIsLeftAlone() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.ProcessFailed(WinGlue.FAILED_UNRESPONSIVE))
        rig.engine.onEvent(WinEvent.Result(rig.page.executed.last().first, true, "1"))
        advanceTimeBy(3_000)
        assertTrue(rig.engine.isUsable)
        rig.close()
    }

    @Test fun aReleaseOfASilentPageEndsItsRendererFirst() = runTest {
        // V10's fallback, built in: a page that hasn't answered for a second is crashed before its controller closes.
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        advanceTimeBy(3_100)
        advanceTimeBy(1_200)
        rig.engine.release()
        val crash = rig.page.calls.indexOf("devtools Page.crash")
        assertTrue(crash in 0 until rig.page.calls.indexOf("release"), "${rig.page.calls}")
        rig.close()
    }

    @Test fun anAdoptedPagesFirstPaintWaitsForItsNewPlace() = runTest {
        // L8: the replay's first paint means drawn in the new place, never at once.
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.paints()
        rig.drawnInPlace()
        rig.unsubscribe()
        rig.engine.onSurfaceAttached(false)
        rig.engine.setVisible(false)
        assertEquals(false, rig.page.visible)
        rig.events.clear()
        rig.subscribe()
        rig.engine.setVisible(true)
        rig.engine.replayState()
        assertTrue(EngineEvent.UrlChanged(demo.origin.url) in rig.events)
        assertTrue(EngineEvent.FirstPaint !in rig.events, "not before a surface draws it")
        rig.engine.onSurfaceAttached(true)
        rig.page.frame = Triple(320, 480, 2L)
        rig.drawnInPlace()
        assertTrue(EngineEvent.FirstPaint in rig.events)
        rig.close()
    }
}
