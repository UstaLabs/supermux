package dev.supermux.webview.engine.linux

import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlin.test.Test
import kotlin.test.assertEquals

class WpeNavigationTest {
    private val demo = WebOrigin("demo")

    private fun policy(url: String, kind: NavigationKind = NavigationKind.Link, gesture: Boolean = true, newWindow: Boolean = false) =
        WpeEvent.Policy(1, url, kind, newWindow, gesture, redirect = false)

    private fun response(url: String, main: Boolean) =
        WpeEvent.Response(2, url, mainFrame = main, canShow = true, attachment = false, status = 200, mime = "text/html", filename = null)

    private fun ask(url: String, main: Boolean, gesture: Boolean) =
        WpeResponseVerdict.Ask(NavigationRequest(url, isMainFrame = main, isUserGesture = gesture))

    @Test fun theEnginesOwnLoadIsOwn() {
        val n = WpeNavigation()
        assertEquals(WpeVerdict.Own, n.decideAction(policy("https://a.example/", NavigationKind.Other), OwnNavigation.Load("https://a.example/"), null, null))
    }

    @Test fun theFloorIsReachedOnlyByTheEngine() {
        val n = WpeNavigation()
        assertEquals(WpeVerdict.Ignore, n.decideAction(policy(WebKitUrls.FLOOR_URL, NavigationKind.BackForward), null, null, null))
        assertEquals(WpeVerdict.Own, n.decideAction(policy(WebKitUrls.FLOOR_URL, NavigationKind.Other), OwnNavigation.Floor, null, null))
    }

    @Test fun anotherBundleIsRefused() {
        val n = WpeNavigation()
        assertEquals(WpeVerdict.Ignore, n.decideAction(policy("webview-bundle://other.invalid/x.html"), null, demo, null))
    }

    @Test fun aSchemeWithNoResponseIsAskedAtTheAction() {
        val n = WpeNavigation()
        assertEquals(
            WpeVerdict.Ask(NavigationRequest("mailto:a@b.example", isMainFrame = true, isUserGesture = true)),
            n.decideAction(policy("mailto:a@b.example"), null, null, null),
        )
    }

    @Test fun anHttpNavigationIsAskedAtItsResponseWithItsFrame() {
        val n = WpeNavigation()
        assertEquals(WpeVerdict.AtResponse, n.decideAction(policy("https://b.example/next"), null, null, null))
        assertEquals(ask("https://b.example/next", main = true, gesture = true), n.atResponse(response("https://b.example/next", true), null))
        n.decideAction(policy("https://c.example/frame", gesture = false), null, null, null)
        assertEquals(ask("https://c.example/frame", main = false, gesture = false), n.atResponse(response("https://c.example/frame", false), null))
    }

    @Test fun theEnginesOwnNavigationIsTheMainFramesOnlyAndOnce() {
        val n = WpeNavigation()
        assertEquals(WpeVerdict.Own, n.decideAction(policy("https://a.example/", NavigationKind.Other), OwnNavigation.Load("https://a.example/"), null, null))
        assertEquals(WpeResponseVerdict.Use, n.atResponse(response("https://a.example/", false), null), "a subframe's, with nothing to ask")
        assertEquals(WpeResponseVerdict.Use, n.atResponse(response("https://a.example/", true), null))
        assertEquals(ask("https://a.example/", main = true, gesture = false), n.atResponse(response("https://a.example/", true), null), "once")
    }

    @Test fun historyReloadAndNewWindowsAreNotAskedAtTheAction() {
        val n = WpeNavigation()
        assertEquals(WpeVerdict.Use, n.decideAction(policy("https://a.example/", NavigationKind.BackForward), null, null, null))
        assertEquals(WpeVerdict.Use, n.decideAction(policy("https://a.example/", NavigationKind.Reload), null, null, null))
        assertEquals(WpeVerdict.Use, n.decideAction(policy("https://a.example/", newWindow = true), null, null, null))
    }

    @Test fun reloadingInMemoryHtmlLoadsItAgain() {
        val n = WpeNavigation()
        assertEquals(WpeVerdict.ReloadHtml, n.decideAction(policy(demo.url, NavigationKind.Reload), null, null, demo.url))
    }

    @Test fun aMainFrameResponseNobodyAnnouncedIsAskedAndASubframesPasses() {
        val n = WpeNavigation()
        assertEquals(ask("https://a.example/", main = true, gesture = false), n.atResponse(response("https://a.example/", true), null))
        assertEquals(WpeResponseVerdict.Use, n.atResponse(response("https://a.example/", false), null))
    }

    @Test fun theOldestWaitingNavigationsAreForgottenButTheMainFrameIsStillAsked() {
        val n = WpeNavigation(capacity = 2)
        for (i in 1..3) n.decideAction(policy("https://a.example/$i"), null, null, null)
        assertEquals(ask("https://a.example/1", main = true, gesture = false), n.atResponse(response("https://a.example/1", true), null), "without its gesture")
        assertEquals(ask("https://a.example/3", main = true, gesture = true), n.atResponse(response("https://a.example/3", true), null))
    }

    @Test fun whatTheClientAllowedAtTheActionPassesTheMainFramesResponseOnce() {
        val n = WpeNavigation()
        n.allowedAtAction("about:blank")
        assertEquals(WpeResponseVerdict.Use, n.atResponse(response("about:blank", true), null))
        assertEquals(ask("about:blank", main = true, gesture = false), n.atResponse(response("about:blank", true), null))
    }

    @Test fun aCommitForgetsWhatTheLastDocumentAnnounced() {
        val n = WpeNavigation()
        n.decideAction(policy("https://b.example/"), null, null, null)
        n.clear()
        assertEquals(ask("https://b.example/", main = true, gesture = false), n.atResponse(response("https://b.example/", true), null), "no stale gesture")
    }

    @Test fun aMainFrameResponseWithNoUrlIsRefused() {
        val n = WpeNavigation()
        val noUrl = WpeEvent.Response(2, null, mainFrame = true, canShow = true, attachment = false, status = 200, mime = "text/html", filename = null)
        assertEquals(WpeResponseVerdict.Ignore, n.atResponse(noUrl, null))
    }

    @Test fun aBundleFileIsReportedInTheLibrarysTerms() {
        val n = WpeNavigation()
        val url = WebKitUrls.bundleUrl(demo, "next.html")
        assertEquals(WpeVerdict.Ask(NavigationRequest("https://demo.invalid/next.html", isMainFrame = true, isUserGesture = true)), n.decideAction(policy(url), null, demo, null))
    }
}
