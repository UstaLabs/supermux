package dev.supermux.webview.engine.webkit

import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationRulesTest {
    private val plugin = WebOrigin("plugin-1")

    private fun decide(
        url: String?,
        kind: NavigationKind = NavigationKind.Other,
        isMainFrame: Boolean = true,
        opensWindow: Boolean = false,
        isUserGesture: Boolean = false,
        own: OwnNavigation? = null,
        bundle: WebOrigin? = null,
        htmlUrl: String? = null,
    ) = decideNavigation(url, isMainFrame, opensWindow, kind, isUserGesture, own, bundle, htmlUrl)

    @Test fun aNavigationThePageStartsIsAskedAboutInTheLibrarysTerms() {
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://example.com/next", isMainFrame = true, isUserGesture = true)),
            decide("https://example.com/next", NavigationKind.Link, isUserGesture = true),
        )
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://plugin-1.invalid/page2.html", isMainFrame = true, isUserGesture = false)),
            decide("webview-bundle://plugin-1.invalid/page2.html", bundle = plugin),
        )
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://ads.example/frame", isMainFrame = false, isUserGesture = false)),
            decide("https://ads.example/frame", isMainFrame = false),
        )
    }

    @Test fun theEnginesOwnLoadIsNotAskedAbout() {
        assertEquals(NavigationVerdict.Own, decide("https://example.com/", own = OwnNavigation.Load("https://example.com")))
        assertEquals(NavigationVerdict.Own, decide("https://c1.invalid/", own = OwnNavigation.Load("https://c1.invalid/")))
        // Another URL than the one loaded is the page's.
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://evil.example/", isMainFrame = true, isUserGesture = false)),
            decide("https://evil.example/", own = OwnNavigation.Load("https://example.com/")),
        )
        // A link with the loaded URL is the page's too: a load is never a link.
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://example.com/", isMainFrame = true, isUserGesture = false)),
            decide("https://example.com/", NavigationKind.Link, own = OwnNavigation.Load("https://example.com/")),
        )
        assertEquals(NavigationVerdict.Own, decide("https://example.com/", NavigationKind.Reload, own = OwnNavigation.Reload))
        assertEquals(NavigationVerdict.Own, decide("https://example.com/a", NavigationKind.BackForward, own = OwnNavigation.History))
        assertEquals(NavigationVerdict.Own, decide("https://example.com/a", NavigationKind.BackForward, own = OwnNavigation.Restore))
        assertEquals(NavigationVerdict.Own, decide("https://example.com/a", NavigationKind.Other, own = OwnNavigation.Restore))
    }

    @Test fun subframesNeverTakeTheEnginesOwnNavigation() {
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://example.com/", isMainFrame = false, isUserGesture = false)),
            decide("https://example.com/", isMainFrame = false, own = OwnNavigation.Load("https://example.com/")),
        )
    }

    @Test fun historyAndReloadsAreNeverAskedAbout() {
        assertEquals(NavigationVerdict.Allow, decide("https://example.com/a", NavigationKind.BackForward))
        assertEquals(NavigationVerdict.Allow, decide("https://example.com/a", NavigationKind.Reload))
        assertEquals(NavigationVerdict.Allow, decide("https://example.com/a", NavigationKind.FormResubmit))
    }

    @Test fun theFloorIsReachedOnlyByTheEnginesOwnFloorNavigation() {
        val floor = WebKitUrls.FLOOR_URL
        assertEquals(NavigationVerdict.Own, decide(floor, NavigationKind.BackForward, own = OwnNavigation.Floor))
        assertEquals(NavigationVerdict.Own, decide(floor, NavigationKind.Other, own = OwnNavigation.Floor))
        // history.back() into it, goBack() into it, a restored state's history, the page itself, or a subframe: refused.
        assertEquals(NavigationVerdict.Refuse, decide(floor, NavigationKind.BackForward))
        assertEquals(NavigationVerdict.Refuse, decide(floor, NavigationKind.BackForward, own = OwnNavigation.History))
        assertEquals(NavigationVerdict.Refuse, decide(floor, NavigationKind.BackForward, own = OwnNavigation.Restore))
        assertEquals(NavigationVerdict.Refuse, decide(floor, NavigationKind.Link))
        assertEquals(NavigationVerdict.Refuse, decide(floor, isMainFrame = false, own = OwnNavigation.Floor))
    }

    @Test fun onlyTheLeasesBundleIsServed() {
        assertEquals(NavigationVerdict.Refuse, decide("webview-bundle://plugin-2.invalid/", bundle = plugin))
        assertEquals(NavigationVerdict.Refuse, decide("webview-bundle://plugin-1.invalid/", bundle = null))
        assertEquals(NavigationVerdict.Refuse, decide("webview-bundle://plugin-2.invalid/", isMainFrame = false, bundle = plugin))
        assertEquals(NavigationVerdict.Refuse, decide("webview-bundle://plugin-2.invalid/", NavigationKind.BackForward, bundle = plugin))
    }

    @Test fun aNewWindowIsAskedAboutWhenWebKitAsksForTheWindow() {
        assertEquals(NavigationVerdict.Allow, decide("https://example.com/", NavigationKind.Link, isMainFrame = false, opensWindow = true))
        assertEquals(NavigationVerdict.Refuse, decide(WebKitUrls.FLOOR_URL, opensWindow = true))
    }

    @Test fun aPageReloadingInMemoryHtmlGetsItAgain() {
        assertEquals(NavigationVerdict.ReloadHtml, decide("https://c1.invalid/", NavigationKind.Reload, htmlUrl = "https://c1.invalid/"))
        assertEquals(NavigationVerdict.Allow, decide("https://c1.invalid/", NavigationKind.Reload, isMainFrame = false, htmlUrl = "https://c1.invalid/"))
        assertEquals(NavigationVerdict.Allow, decide("https://example.com/", NavigationKind.Reload, htmlUrl = "https://c1.invalid/"))
    }

    @Test fun noUrlIsRefused() {
        assertEquals(NavigationVerdict.Refuse, decide(null))
        assertEquals(NavigationVerdict.Refuse, decide(""))
    }
}
