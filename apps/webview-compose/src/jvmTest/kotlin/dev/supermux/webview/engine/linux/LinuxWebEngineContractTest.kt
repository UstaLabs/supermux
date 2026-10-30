package dev.supermux.webview.engine.linux

import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.pageSetup
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** The contract's lines the review's mutants got past: each test fails with its mutant applied. */
class LinuxWebEngineContractTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    /** The floor's load, as WebKit answers a reset's restore. */
    private fun EngineRig.floorLoads() {
        engine.onEvent(WpeEvent.Policy(90, WebKitUrls.FLOOR_URL, NavigationKind.BackForward, false, false, false))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, WebKitUrls.FLOOR_URL))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, WebKitUrls.FLOOR_URL))
        engine.onEvent(WpeEvent.History(false, false, emptyList(), emptyList()))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_FINISHED, WebKitUrls.FLOOR_URL))
    }

    /** Starts a reset and answers its question whether the page still answers; its result once it has one. */
    private fun EngineRig.startReset(): () -> Boolean? {
        var result: Boolean? = null
        scope.launch { result = engine.reset() }
        val (request, _, _) = view.evaluations.last()
        engine.onEvent(WpeEvent.Eval(request, true, "1"))
        return { result }
    }

    @Test fun whatAResetDidItselfIsNeverTheNextLeasesToHear() = runTest {
        // Reset's second epoch advance: with nobody subscribed (the runtime gave the engine back), the floor's own commit
        // was buffered, and the next lease's subscriber heard of webview-floor://.
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.unsubscribe()
        rig.events.clear()
        val result = rig.startReset()
        rig.floorLoads()
        assertEquals(true, result())
        rig.subscribe()
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        rig.close()
    }

    @Test fun aLeaseCallAfterAFailedResetReachesNothing() = runTest {
        // The resetFailed check: the pool destroys an engine whose reset failed; until then it takes no lease.
        val rig = EngineRig(this, WebProfile.Ephemeral)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.startReset()
        rig.floorLoads()
        val wipe = rig.view.calls.last { it.startsWith("clearData") }.substringAfter(' ').toLong()
        rig.engine.onEvent(WpeEvent.DataCleared(wipe, false))
        assertEquals(false, result())
        rig.view.calls.clear()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        rig.engine.load(WebContent.Url("https://a.example/"))
        rig.engine.postMessage("{}", "https://a.example")
        assertTrue(rig.view.calls.isEmpty(), "${rig.view.calls}")
        rig.close()
    }

    @Test fun aResetTakesTheFocusFromThePage() = runTest {
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.startReset()
        assertContains(rig.view.calls, "setFocus false")
        rig.close()
    }

    @Test fun aCrashDuringTheWarmUpFailsIt() = runTest {
        val rig = EngineRig(this)
        var ready: Boolean? = null
        rig.scope.launch { ready = rig.engine.warmUp() }
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, WebKitUrls.FLOOR_URL))
        rig.engine.onEvent(WpeEvent.Crashed(1))
        assertEquals(false, ready)
        assertFalse(rig.engine.isUsable)
        rig.close()
    }

    @Test fun aCrashDuringAResetFailsIt() = runTest {
        val rig = EngineRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val result = rig.startReset()
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, WebKitUrls.FLOOR_URL))
        rig.engine.onEvent(WpeEvent.Crashed(1))
        assertEquals(false, result())
        rig.close()
    }

    @Test fun aSchemeWithNoResponseIsTheClientsToDecideAtTheAction() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.client.navigation = NavigationDecision.Block
        rig.engine.onEvent(WpeEvent.Policy(60, "mailto:a@b.example", NavigationKind.Link, false, true, false))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[60])
        assertContains(rig.client.asked, NavigationRequest("mailto:a@b.example", isMainFrame = true, isUserGesture = true))
        rig.client.navigation = NavigationDecision.Allow
        rig.engine.onEvent(WpeEvent.Policy(61, "about:blank", NavigationKind.Link, false, true, false))
        assertEquals(WpeNative.USE, rig.view.decisions[61])
        rig.close()
    }

    @Test fun aLoadPolicyStoppedIsNoError() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.engine.onEvent(WpeEvent.LoadFailed(102, cancelled = false, tls = false, policy = true, cannotShow = false, uri = "https://a.example/x", message = "interrupted"))
        assertTrue(rig.events.none { it is EngineEvent.Error }, "${rig.events}")
        rig.close()
    }

    @Test fun everyLeaseSetsTheProcessesDarkMode() = runTest {
        // WPE's dark mode is the process's (W9): after another engine's lease set light, this one's dark is told again.
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = true, bridgeOrigins = emptySet()))
        rig.view.setDark(false) // another engine's lease, on the same process
        rig.engine.configure(WebSettings.Default.pageSetup(dark = true, bridgeOrigins = emptySet()))
        assertEquals(true, rig.view.dark)
        rig.close()
    }

    @Test fun anHttpErrorPageIsReportedOnceItIsShown() = runTest {
        for (status in listOf(404, 503)) {
            val rig = EngineRig(this)
            rig.warm()
            val url = "https://a.example/missing"
            rig.engine.load(WebContent.Url(url))
            rig.engine.onEvent(WpeEvent.Policy(2, url, NavigationKind.Other, false, false, false))
            rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, url))
            rig.engine.onEvent(WpeEvent.Response(3, url, mainFrame = true, canShow = true, attachment = false, status = status, mime = "text/html", filename = null))
            assertEquals(WpeNative.USE, rig.view.decisions[3], "its page is shown")
            rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, url))
            rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_FINISHED, url))
            assertTrue(rig.events.none { it is EngineEvent.Error }, "not before it is shown")
            rig.drawnInPlace()
            assertEquals(EngineEvent.Error(WebError.Http(url, status)), rig.events.last { it is EngineEvent.Error })
            rig.close()
        }
    }
}
