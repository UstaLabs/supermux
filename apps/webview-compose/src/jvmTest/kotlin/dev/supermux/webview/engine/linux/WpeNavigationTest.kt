package dev.supermux.webview.engine.linux

import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WpeNavigationTest {
    private val demo = WebOrigin("demo")

    private fun policy(url: String, kind: NavigationKind = NavigationKind.Link, gesture: Boolean = true, newWindow: Boolean = false) =
        WpeEvent.Policy(1, url, kind, newWindow, gesture, redirect = false)

    private fun response(url: String, main: Boolean) =
        WpeEvent.Response(2, url, mainFrame = main, canShow = true, attachment = false, status = 200, mime = "text/html", filename = null)

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
        assertEquals(NavigationRequest("https://b.example/next", isMainFrame = true, isUserGesture = true), n.requestAtResponse(response("https://b.example/next", true), null))
        assertNull(n.requestAtResponse(response("https://b.example/next", true), null))
        n.decideAction(policy("https://c.example/frame", gesture = false), null, null, null)
        assertEquals(NavigationRequest("https://c.example/frame", isMainFrame = false, isUserGesture = false), n.requestAtResponse(response("https://c.example/frame", false), null))
    }

    @Test fun historyReloadAndNewWindowsAreNotAsked() {
        val n = WpeNavigation()
        assertEquals(WpeVerdict.Use, n.decideAction(policy("https://a.example/", NavigationKind.BackForward), null, null, null))
        assertEquals(WpeVerdict.Use, n.decideAction(policy("https://a.example/", NavigationKind.Reload), null, null, null))
        assertEquals(WpeVerdict.Use, n.decideAction(policy("https://a.example/", newWindow = true), null, null, null))
    }

    @Test fun reloadingInMemoryHtmlLoadsItAgain() {
        val n = WpeNavigation()
        assertEquals(WpeVerdict.ReloadHtml, n.decideAction(policy(demo.url, NavigationKind.Reload), null, null, demo.url))
    }

    @Test fun aResponseNobodyWaitedForAsksNothing() {
        assertNull(WpeNavigation().requestAtResponse(response("https://a.example/", true), null))
    }

    @Test fun theOldestWaitingNavigationsAreForgotten() {
        val n = WpeNavigation(capacity = 2)
        for (i in 1..3) n.decideAction(policy("https://a.example/$i"), null, null, null)
        assertNull(n.requestAtResponse(response("https://a.example/1", true), null))
        assertEquals("https://a.example/3", n.requestAtResponse(response("https://a.example/3", true), null)?.url)
    }

    @Test fun aBundleFileIsReportedInTheLibrarysTerms() {
        val n = WpeNavigation()
        val url = WebKitUrls.bundleUrl(demo, "next.html")
        assertEquals(WpeVerdict.Ask(NavigationRequest("https://demo.invalid/next.html", isMainFrame = true, isUserGesture = true)), n.decideAction(policy(url), null, demo, null))
    }
}
