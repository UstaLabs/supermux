package dev.supermux.webview.engine.linux

import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.webkit.NavigationKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * WPE says which frame a navigation is for only at its response (W4): no main-frame response may get past the client
 * unasked, whatever the page does in between, unless it is the engine's own navigation.
 */
class LinuxWebEngineNavigationTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))
    private val evil = "https://evil.example/"

    /** A client as the default policy is for local content: subframes are the page's business, its main frame stays. */
    private fun EngineRig.mainFrameStaysHome() {
        client.decide = { if (it.isMainFrame) NavigationDecision.Block else NavigationDecision.Allow }
    }

    private fun response(decision: Long, url: String, main: Boolean) =
        WpeEvent.Response(decision, url, mainFrame = main, canShow = true, attachment = false, status = 200, mime = "text/html", filename = null)

    @Test fun anIframeAnsweredFirstCannotTakeTheMainFramesQuestion() = runTest {
        // The reviewer's exploit: location.href = U, then an <iframe src=U>; the server answers the iframe first.
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.mainFrameStaysHome()
        rig.engine.onEvent(WpeEvent.Policy(10, evil, NavigationKind.Other, false, false, false))
        rig.engine.onEvent(WpeEvent.Policy(11, evil, NavigationKind.Other, false, false, false))
        rig.engine.onEvent(response(12, evil, main = false))
        assertEquals(WpeNative.USE, rig.view.decisions[12], "the subframe: the page's business")
        rig.engine.onEvent(response(13, evil, main = true))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[13], "the main frame left unasked")
        assertContains(rig.client.asked, NavigationRequest(evil, isMainFrame = true, isUserGesture = false))
        rig.close()
    }

    @Test fun manyIframeNavigationsCannotEvictTheMainFramesQuestion() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.mainFrameStaysHome()
        rig.engine.onEvent(WpeEvent.Policy(10, evil, NavigationKind.Link, false, true, false))
        for (i in 0 until 200) rig.engine.onEvent(WpeEvent.Policy(100L + i, "https://frames.example/$i", NavigationKind.Other, false, false, false))
        rig.engine.onEvent(response(13, evil, main = true))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[13])
        rig.close()
    }

    @Test fun aMainFrameResponseNoNavigationAnnouncedIsAsked() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.mainFrameStaysHome()
        rig.engine.onEvent(response(14, evil, main = true))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[14])
        rig.close()
    }

    @Test fun theEnginesOwnLoadIsNeverAskedNorForgotten() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.client = rig.client
        rig.client.navigation = NavigationDecision.Block
        val url = "https://a.example/"
        rig.engine.load(WebContent.Url(url))
        rig.engine.onEvent(WpeEvent.Policy(20, url, NavigationKind.Other, false, false, false))
        // An old document's frames navigate meanwhile, as many times as they like.
        for (i in 0 until 200) rig.engine.onEvent(WpeEvent.Policy(100L + i, "https://frames.example/$i", NavigationKind.Other, false, false, false))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, url))
        rig.engine.onEvent(response(21, url, main = true))
        assertEquals(WpeNative.USE, rig.view.decisions[21])
        assertTrue(rig.client.asked.none { it is NavigationRequest && it.isMainFrame }, "${rig.client.asked}")
        rig.close()
    }

    @Test fun anIframeCannotTakeTheEnginesOwnLoad() = runTest {
        // An iframe of the old document answered first with the URL of the engine's own load: its response is the
        // iframe's, and the main frame's response is still the engine's.
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.client = rig.client
        rig.client.navigation = NavigationDecision.Block
        val url = "https://a.example/"
        rig.engine.load(WebContent.Url(url))
        rig.engine.onEvent(WpeEvent.Policy(20, url, NavigationKind.Other, false, false, false))
        rig.engine.onEvent(WpeEvent.Policy(22, url, NavigationKind.Other, false, false, false))
        rig.engine.onEvent(response(23, url, main = false))
        rig.engine.onEvent(response(21, url, main = true))
        assertEquals(WpeNative.USE, rig.view.decisions[21])
        rig.close()
    }

    @Test fun theEnginesHistoryAndReloadAreNotAskedButThePagesAre() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        val url = "https://a.example/"
        rig.show(WebContent.Url(url), url)
        rig.engine.onEvent(WpeEvent.History(true, false, listOf(url), emptyList()))
        rig.client.navigation = NavigationDecision.Block
        rig.engine.goBack()
        rig.engine.onEvent(WpeEvent.Policy(30, url, NavigationKind.BackForward, false, false, false))
        rig.engine.onEvent(response(31, url, main = true))
        assertEquals(WpeNative.USE, rig.view.decisions[31], "goBack is the engine's")
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, url))
        // The page's own history.back(): WPE can't tell a subframe's from the main frame's at the action, so it is the
        // main frame's response that is asked about.
        rig.engine.onEvent(WpeEvent.Policy(32, evil, NavigationKind.BackForward, false, false, false))
        assertEquals(WpeNative.USE, rig.view.decisions[32])
        rig.engine.onEvent(response(33, evil, main = true))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[33])
        assertContains(rig.client.asked, NavigationRequest(evil, isMainFrame = true, isUserGesture = false))
        rig.close()
    }

    @Test fun aNavigationTheClientAllowedAtItsActionIsNotAskedAgainAtItsResponse() = runTest {
        // A bundle's next page (no http response to wait for: asked at the action, as the main frame's).
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val next = "about:blank"
        rig.engine.onEvent(WpeEvent.Policy(40, next, NavigationKind.Link, false, true, false))
        assertEquals(WpeNative.USE, rig.view.decisions[40])
        rig.engine.onEvent(response(41, next, main = true))
        assertEquals(WpeNative.USE, rig.view.decisions[41])
        assertEquals(1, rig.client.asked.count { it is NavigationRequest }, "${rig.client.asked}")
        rig.close()
    }

    @Test fun aFragmentDoesNotHideAQuestion() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.mainFrameStaysHome()
        rig.engine.onEvent(WpeEvent.Policy(50, "$evil#top", NavigationKind.Link, false, true, false))
        rig.engine.onEvent(response(51, evil, main = true))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[51])
        assertContains(rig.client.asked, NavigationRequest(evil, isMainFrame = true, isUserGesture = true))
        rig.close()
    }
}
