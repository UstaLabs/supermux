package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

class LinuxWebEngineResetTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    /** Starts a reset, and answers its question whether the page still answers; its result once it has one. */
    private fun EngineRig.startReset(): () -> Boolean? {
        var result: Boolean? = null
        val asked = view.evaluations.size
        scope.launch { result = engine.reset() }
        val (request, script, world) = view.evaluations.drop(asked).single()
        check(script == "1" && world == WebEngineWorld.WORLD) { "not the reset's question: $script" }
        engine.onEvent(WpeEvent.Eval(request, true, "1"))
        return { result }
    }

    /** The floor's restore, as WebKit answers it: its decision and its load; [back] if WebKit reports a list. */
    private fun EngineRig.floorLoads(back: List<String>? = null) {
        engine.onEvent(WpeEvent.Policy(90, WebKitUrls.FLOOR_URL, NavigationKind.BackForward, false, false, false))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, WebKitUrls.FLOOR_URL))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, WebKitUrls.FLOOR_URL))
        if (back != null) engine.onEvent(WpeEvent.History(back.isNotEmpty(), false, back, emptyList()))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_FINISHED, WebKitUrls.FLOOR_URL))
    }

    /** WebKit's answer to the wipe the reset asked for. */
    private fun EngineRig.wiped(ok: Boolean) {
        val request = view.calls.last { it.startsWith("clearData") }.substringAfter(' ').toLong()
        engine.onEvent(WpeEvent.DataCleared(request, ok))
    }

    @Test fun aResetPutsTheFloorBackDropsTheScriptsAndWipesAnEphemeralEngine() = runTest {
        val rig = EngineRig(this, WebProfile.Ephemeral)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.startReset()
        assertContains(rig.view.calls, "navigate ${WpeNative.GO_STOP}")
        assertContains(rig.view.calls, "restoreState floor-state")
        assertNull(rig.view.configured!![0], "no relay")
        assertNull(rig.view.configured!![1], "no shim")
        rig.floorLoads(back = emptyList())
        assertEquals(WpeNative.USE, rig.view.decisions[90])
        assertNull(result(), "waiting for the wipe")
        rig.wiped(ok = true)
        assertEquals(true, result())
        rig.close()
    }

    @Test fun aDefaultProfileIsNotWiped() = runTest {
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.startReset()
        rig.floorLoads()
        assertEquals(true, result())
        assertTrue(rig.view.calls.none { it.startsWith("clearData") })
        rig.close()
    }

    @Test fun aFailedWipeFailsTheReset() = runTest {
        val rig = EngineRig(this, WebProfile.Ephemeral)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.startReset()
        rig.floorLoads()
        rig.wiped(ok = false)
        assertEquals(false, result())
        rig.close()
    }

    @Test fun aListWithMoreThanTheFloorFailsTheReset() = runTest {
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.startReset()
        rig.floorLoads(back = listOf(demo.origin.url))
        assertEquals(false, result())
        rig.close()
    }

    @Test fun nothingFromTheLastLeaseIsDeliveredAfterAReset() = runTest {
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.startReset()
        rig.floorLoads()
        assertEquals(true, result())
        val before = rig.events.size
        rig.engine.onEvent(WpeEvent.Title("late"))
        rig.engine.onEvent(WpeEvent.Relay("0123456789abcdef msg {}"))
        rig.engine.onEvent(WpeEvent.Relay("0123456789abcdef paint"))
        rig.drawnInPlace()
        assertEquals(before, rig.events.size, "${rig.events.drop(before)}")
        rig.close()
    }

    @Test fun aBundlesOpenRequestIsAnsweredAtTheReset() = runTest {
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        val bundle = WebContent.Bundle(WebOrigin("app"), WebResourceProvider { awaitCancellation() })
        rig.show(bundle, WebKitUrls.bundleUrl(bundle.origin, "index.html"))
        rig.engine.onEvent(WpeEvent.Scheme(7, WebKitUrls.bundleUrl(bundle.origin, "slow.js")))
        assertNull(rig.view.finished[7])
        rig.startReset()
        assertEquals(404, rig.view.finished[7]?.first)
        rig.close()
    }

    @Test fun anAdoptedPagesFirstPaintWaitsForItsNewSurfacesDraw() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WpeEvent.Relay("0123456789abcdef paint"))
        rig.drawnInPlace()
        assertEquals(1, rig.events.count { it == EngineEvent.FirstPaint })
        val parkedAt = rig.view.calls.size
        // Parked: unbound, hidden, its surface gone.
        rig.engine.client = null
        rig.engine.setVisible(false)
        rig.engine.onSurfaceAttached(false)
        // Adopted: replayed, then a surface comes.
        rig.engine.replayState()
        assertEquals(EngineEvent.UrlChanged(demo.origin.url), rig.events.last { it is EngineEvent.UrlChanged })
        assertEquals(1, rig.events.count { it == EngineEvent.FirstPaint }, "not before it is drawn again")
        rig.engine.onSurfaceAttached(true)
        rig.engine.setVisible(true)
        rig.drawnInPlace()
        assertEquals(2, rig.events.count { it == EngineEvent.FirstPaint })
        val since = rig.view.calls.drop(parkedAt)
        assertTrue(since.none { it.startsWith("load") || it.startsWith("navigate") || it.startsWith("restoreState") }, "not reloaded: $since")
        rig.close()
    }

    @Test fun aHiddenPageIsToldOnceAndShownAgain() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.setVisible(false)
        rig.engine.setVisible(false)
        rig.engine.setVisible(true)
        assertEquals(listOf("setVisible false", "setVisible true"), rig.view.calls.filter { it.startsWith("setVisible") })
        rig.close()
    }

    @Test fun aResetOnTheFloorReloadsItSinceGoingToItsOwnItemLoadsNothing() = runTest {
        // A lease whose page never committed (an unreachable URL) leaves the floor shown. The restore's navigation to the
        // floor item is then no load at all (the sample's perf lab, offline, waited 10 s for it), so the floor reloads.
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        val url = "https://unreachable.example/"
        rig.engine.load(WebContent.Url(url))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, url))
        rig.engine.onEvent(WpeEvent.LoadFailed(-1, cancelled = false, tls = false, policy = false, cannotShow = false, uri = url, message = "unresolved"))
        val result = rig.startReset()
        val calls = rig.view.calls
        assertTrue(calls.indexOf("navigate ${WpeNative.GO_RELOAD}") > calls.indexOf("restoreState floor-state"), "$calls")
        rig.floorLoads()
        assertEquals(true, result())
        rig.close()
    }

    @Test fun aResetEndsAPageThatNoLongerAnswersInsteadOfKeepingIt() = runTest {
        // Its process would spin on after the floor took another one (a hung page at reset, desktop 2026-10-30).
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        var result: Boolean? = null
        rig.scope.launch { result = rig.engine.reset() }
        assertEquals("1", rig.view.evaluations.last().second, "the page is asked whether it answers")
        assertTrue(rig.view.calls.none { it.startsWith("restoreState") }, "nothing more before it answers")
        advanceTimeBy(1_501)
        assertEquals(false, result)
        assertContains(rig.view.calls, "terminate")
        assertTrue(!rig.engine.isUsable)
        rig.close()
    }

    @Test fun aResetAfterACommittedPageLetsTheRestoreLoadTheFloor() = runTest {
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.startReset()
        assertTrue(rig.view.calls.none { it == "navigate ${WpeNative.GO_RELOAD}" }, "${rig.view.calls}")
        rig.floorLoads()
        assertEquals(true, result())
        rig.close()
    }
}
