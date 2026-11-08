package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.EngineEvent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/** A page that stops answering: its web process never outlives its engine, and a hung page is replaced (I2). */
class LinuxWebEngineHangTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    @Test fun releaseEndsThePagesProcessBeforeDroppingTheView() = runTest {
        // Dropped alone, the view waits for its page to close, which a page in a loop never does: its process spun on.
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.release()
        assertEquals(listOf("terminate", "destroy"), rig.view.calls.takeLast(2))
        rig.close()
    }

    @Test fun aPageWebKitFindsUnresponsiveIsEndedAndReportedCrashed() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WpeEvent.Responsive(false))
        advanceTimeBy(1_999)
        assertTrue(rig.engine.isUsable, "a moment's grace, as Android's 5 s")
        assertFalse("terminate" in rig.view.calls)
        advanceTimeBy(2)
        assertContains(rig.view.calls, "terminate")
        assertContains(rig.events, EngineEvent.Crashed)
        assertFalse(rig.engine.isUsable)
        rig.close()
    }

    /** A parked page WebKit finds unresponsive is ended as one on screen is, and tells the pool it parked with, once. */
    @Test fun aParkedPageThatHangsIsEndedAndTellsThePoolOnce() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.park()
        var told = 0
        rig.engine.onUnusable = { told++ }
        rig.engine.onEvent(WpeEvent.Responsive(false))
        advanceTimeBy(2_001)
        assertContains(rig.view.calls, "terminate")
        assertFalse(rig.engine.isUsable)
        assertEquals(1, told)
        rig.engine.release()
        assertEquals(1, told)
        rig.close()
    }

    @Test fun aPageThatAnswersAgainInTimeIsKept() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WpeEvent.Responsive(false))
        advanceTimeBy(1_000)
        rig.engine.onEvent(WpeEvent.Responsive(true))
        advanceTimeBy(5_000)
        assertFalse("terminate" in rig.view.calls)
        assertTrue(rig.engine.isUsable)
        rig.close()
    }

    /** The heartbeat's questions since [from]: scripts "1" in the engine's world. */
    private fun EngineRig.probes(from: Int = 0) = view.evaluations.drop(from).filter { it.second == "1" && it.third == WebEngineWorld.WORLD }

    @Test fun aShownPageThatStopsAnsweringIsEndedAndReportedCrashed() = runTest {
        // WebKit's own check needs input it waits on, and a mouse move, which it sends unchecked, blocks the queue
        // before any click (WebPageProxy::processNextQueuedMouseEvent): a page on screen is asked by the engine.
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val from = rig.view.evaluations.size
        advanceTimeBy(3_001)
        assertEquals(1, rig.probes(from).size, "asked whether it answers")
        advanceTimeBy(4_998)
        assertTrue(rig.engine.isUsable)
        advanceTimeBy(2)
        assertContains(rig.view.calls, "terminate")
        assertContains(rig.events, EngineEvent.Crashed)
        rig.close()
    }

    @Test fun aShownPageThatAnswersIsKept() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val from = rig.view.evaluations.size
        repeat(10) {
            advanceTimeBy(3_001)
            for ((request, _, _) in rig.probes(from)) rig.engine.onEvent(WpeEvent.Eval(request, true, "1"))
        }
        assertTrue(rig.probes(from).size >= 9, "asked every 3 s")
        assertFalse("terminate" in rig.view.calls)
        rig.close()
    }

    @Test fun aPageADialogHoldsIsNotTakenForHung() = runTest {
        // A script dialog blocks the page until the client answers: WebKit stops its own check meanwhile, so does the engine.
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.client.gate = kotlinx.coroutines.CompletableDeferred()
        rig.engine.onEvent(WpeEvent.Dialog(3, WpeNative.DIALOG_ALERT, "Hold on", null))
        advanceTimeBy(30_000)
        assertFalse("terminate" in rig.view.calls)
        assertTrue(rig.engine.isUsable)
        rig.close()
    }

    @Test fun aPageNotOnScreenIsNotAsked() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.setVisible(false)
        val from = rig.view.evaluations.size
        advanceTimeBy(30_000)
        assertTrue(rig.probes(from).isEmpty())
        rig.engine.setVisible(true)
        rig.engine.onSurfaceAttached(false)
        advanceTimeBy(30_000)
        assertTrue(rig.probes(from).isEmpty(), "no surface")
        rig.close()
    }

    @Test fun responsivenessIsDecoded() {
        assertEquals(WpeEvent.Responsive(false), WpeEvent.decode(WpeNative.RESPONSIVE, 0, 0, null, null))
        assertEquals(WpeEvent.Responsive(true), WpeEvent.decode(WpeNative.RESPONSIVE, 1, 0, null, null))
    }
}
