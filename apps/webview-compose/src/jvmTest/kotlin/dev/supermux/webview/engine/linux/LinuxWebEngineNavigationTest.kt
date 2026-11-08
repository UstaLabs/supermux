package dev.supermux.webview.engine.linux

import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.WebKitUrls
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

    // --- Server redirects of the engine's own navigations (final review, item 4) ---

    private fun redirect(decision: Long, url: String, kind: NavigationKind = NavigationKind.Other) =
        WpeEvent.Policy(decision, url, kind, newWindow = false, gesture = false, redirect = true)

    private fun failed(uri: String?, policy: Boolean = false, cancelled: Boolean = false) =
        WpeEvent.LoadFailed(if (policy) 102 else 302, cancelled = cancelled, tls = false, policy = policy, cannotShow = false, uri = uri, message = "failed")

    /**
     * The review's probe: the server redirects the app's load to `www.`, and WebKit asks about the redirect's request, then
     * its response. Asked with no gesture and refused by a blocking client, it left the view under its cover for good.
     */
    @Test fun aServerRedirectOfTheEnginesOwnLoadIsNotAskedAbout() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.client.navigation = NavigationDecision.Block
        val url = "https://a.example/"
        val redirected = "https://www.a.example/"
        rig.engine.load(WebContent.Url(url))
        rig.engine.onEvent(WpeEvent.Policy(20, url, NavigationKind.Other, newWindow = false, gesture = false, redirect = false))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, url))
        rig.engine.onEvent(redirect(21, redirected))
        rig.engine.onEvent(response(22, redirected, main = true))
        assertEquals(emptyList(), rig.client.asked, "the app's own load's redirect was asked about")
        assertEquals(WpeNative.USE, rig.view.decisions[21])
        assertEquals(WpeNative.USE, rig.view.decisions[22], "the app's own load's redirect was refused")
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, redirected))
        assertContains(rig.events, EngineEvent.UrlChanged(redirected))
        rig.close()
    }

    /** To the web only, as on Android, iOS and macOS: a redirect elsewhere ends the load with an error the runtime shows. */
    @Test fun aRedirectOfTheEnginesOwnLoadOffTheWebEndsItWithAnError() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.client.navigation = NavigationDecision.Allow
        val url = "https://a.example/app"
        rig.engine.load(WebContent.Url(url))
        rig.engine.onEvent(WpeEvent.Policy(20, url, NavigationKind.Other, false, false, false))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, url))
        rig.engine.onEvent(redirect(21, "myapp://callback?code=1"))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[21])
        rig.engine.onEvent(failed("myapp://callback?code=1", policy = true))
        assertEquals(listOf<WebError>(WebError.BlockedByPolicy("myapp://callback?code=1")), rig.events.filterIsInstance<EngineEvent.Error>().map { it.error })
        assertTrue(rig.client.asked.isEmpty(), "the app's load is never the client's to decide, nor its redirects: ${rig.client.asked}")
        rig.close()
    }

    /** The app's reload and back too: an expired session's reload redirected to a login page loads it, unasked. */
    @Test fun aRedirectOfTheEnginesOwnReloadOrBackFollowsToTheWeb() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        val url = "https://a.example/account"
        rig.show(WebContent.Url(url), url)
        rig.client.navigation = NavigationDecision.Block
        rig.engine.reload()
        rig.engine.onEvent(WpeEvent.Policy(30, url, NavigationKind.Reload, false, false, false))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, url))
        rig.engine.onEvent(redirect(31, "https://login.a.example/", NavigationKind.Reload))
        rig.engine.onEvent(response(32, "https://login.a.example/", main = true))
        assertEquals(WpeNative.USE, rig.view.decisions[32])
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, "https://login.a.example/"))
        rig.engine.onEvent(WpeEvent.History(true, false, listOf(WebKitUrls.FLOOR_URL, "https://a.example/first"), emptyList()))
        rig.engine.goBack()
        rig.engine.onEvent(WpeEvent.Policy(33, "https://a.example/first", NavigationKind.BackForward, false, false, false))
        rig.engine.onEvent(redirect(34, "https://sso.example/", NavigationKind.BackForward))
        rig.engine.onEvent(response(35, "https://sso.example/", main = true))
        assertEquals(WpeNative.USE, rig.view.decisions[35])
        assertTrue(rig.client.asked.isEmpty(), "the app's navigations are never the client's to decide: ${rig.client.asked}")
        rig.close()
    }

    /** Committed, the page's own: a redirect of a navigation it starts is asked about, at its response. */
    @Test fun onceTheEnginesNavigationCommitsTheRedirectsAreThePages() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.client.navigation = NavigationDecision.Block
        rig.engine.onEvent(WpeEvent.Policy(40, "https://a.example/next", NavigationKind.Link, false, true, false))
        rig.engine.onEvent(redirect(41, evil))
        assertEquals(WpeNative.USE, rig.view.decisions[41], "asked at its response, where WPE says its frame")
        rig.engine.onEvent(response(42, evil, main = true))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[42])
        assertContains(rig.client.asked, NavigationRequest(evil, isMainFrame = true, isUserGesture = false))
        rig.close()
    }

    /**
     * The old document navigates its main frame while the engine's own load is under way, and the server redirects it:
     * WebKit cancels the engine's load first, which ends its claim, so the page's redirect is asked about.
     */
    @Test fun aPageNavigationThatReplacesTheEnginesLoadIsAskedAboutWithItsRedirects() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(WebContent.Url("https://old.example/"), "https://old.example/")
        rig.client.navigation = NavigationDecision.Block
        val url = "https://a.example/"
        rig.engine.load(WebContent.Url(url))
        rig.engine.onEvent(WpeEvent.Policy(50, url, NavigationKind.Other, false, false, false))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, url))
        rig.engine.onEvent(WpeEvent.Policy(51, "https://jump.example/", NavigationKind.Other, false, false, false))
        rig.engine.onEvent(failed(url, cancelled = true))
        rig.engine.onEvent(redirect(52, evil))
        rig.engine.onEvent(response(53, evil, main = true))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[53])
        assertContains(rig.client.asked, NavigationRequest(evil, isMainFrame = true, isUserGesture = false))
        rig.close()
    }

    /**
     * The document before is still loading when the engine's own load starts, and WebKit reports its cancellation after
     * it asked about the engine's: that ends nothing of the engine's own, whose redirect is still its own.
     */
    @Test fun anEarlierLoadsLateCancellationKeepsTheEnginesRedirectsItsOwn() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.client.navigation = NavigationDecision.Block
        rig.engine.load(WebContent.Url("https://slow.example/"))
        rig.engine.onEvent(WpeEvent.Policy(60, "https://slow.example/", NavigationKind.Other, false, false, false))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, "https://slow.example/"))
        val url = "https://a.example/"
        rig.engine.load(WebContent.Url(url))
        rig.engine.onEvent(WpeEvent.Policy(61, url, NavigationKind.Other, false, false, false))
        rig.engine.onEvent(failed("https://slow.example/", cancelled = true))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, url))
        rig.engine.onEvent(redirect(62, "https://www.a.example/"))
        rig.engine.onEvent(response(63, "https://www.a.example/", main = true))
        assertEquals(WpeNative.USE, rig.view.decisions[63])
        assertTrue(rig.client.asked.isEmpty(), "${rig.client.asked}")
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
