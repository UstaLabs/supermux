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
        fromNetwork: Boolean = false,
    ) = decideNavigation(url, isMainFrame, opensWindow, kind, isUserGesture, own, bundle, htmlUrl, fromNetwork)

    /**
     * The engine's own load of https://example.com, which the server redirects to https://www.example.com/ (and on):
     * WebKit asks about each redirect as a main-frame navigation of the load's kind, with the request it built from the
     * one it sent (fromNetwork). Until the load commits, those are the engine's own; a navigation the old document starts
     * meanwhile has the same kind, but WebKit hasn't sent it yet.
     */
    @Test fun theServerRedirectsOfTheEnginesOwnLoadAreItsOwnUntilItCommits() {
        val load = OwnNavigation.Load("https://example.com")
        assertEquals(NavigationVerdict.Own, decide("https://example.com/", own = load))
        val redirecting = load.afterAllowed(NavigationKind.Other)
        assertEquals(OwnNavigation.Redirecting(NavigationKind.Other), redirecting)
        assertEquals(NavigationVerdict.Own, decide("https://www.example.com/", own = redirecting, fromNetwork = true))
        assertEquals(NavigationVerdict.Own, decide("http://www.example.com/next", own = redirecting?.afterAllowed(NavigationKind.Other), fromNetwork = true))
        // The old document's own navigation while the load is provisional: WebKit hasn't sent its request yet.
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://evil.example/", isMainFrame = true, isUserGesture = false)),
            decide("https://evil.example/", own = redirecting),
        )
        // To the web only, as on Android: a redirect anywhere else is refused, the private schemes included, and the
        // engine ends the load with an error (redirectOfOwn): WebKit's own error for a refused navigation says nothing.
        assertEquals(NavigationVerdict.Refuse, decide("myapp://callback?code=1", own = redirecting, fromNetwork = true))
        assertEquals(NavigationVerdict.Refuse, decide(WebKitUrls.FLOOR_URL, own = redirecting, fromNetwork = true))
        assertEquals(NavigationVerdict.Refuse, decide("webview-bundle://plugin-1.invalid/", own = redirecting, fromNetwork = true, bundle = plugin))
        assertEquals(true, redirectOfOwn(redirecting, isMainFrame = true, NavigationKind.Other, fromNetwork = true))
        assertEquals(false, redirectOfOwn(redirecting, isMainFrame = true, NavigationKind.Other, fromNetwork = false))
        assertEquals(false, redirectOfOwn(redirecting, isMainFrame = false, NavigationKind.Other, fromNetwork = true))
        assertEquals(false, redirectOfOwn(redirecting, isMainFrame = true, NavigationKind.Link, fromNetwork = true))
        assertEquals(false, redirectOfOwn(load, isMainFrame = true, NavigationKind.Other, fromNetwork = true), "not before the load itself was allowed")
        // Another kind, or a subframe, is never the load's redirect.
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://www.example.com/", isMainFrame = true, isUserGesture = false)),
            decide("https://www.example.com/", NavigationKind.Link, own = redirecting, fromNetwork = true),
        )
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://www.example.com/", isMainFrame = false, isUserGesture = false)),
            decide("https://www.example.com/", isMainFrame = false, own = redirecting, fromNetwork = true),
        )
        // Without a pending navigation of the engine's own, a sent request is the page's like any other.
        assertEquals(
            NavigationVerdict.Ask(NavigationRequest("https://www.example.com/", isMainFrame = true, isUserGesture = false)),
            decide("https://www.example.com/", fromNetwork = true),
        )
    }

    @Test fun aRestoredPagesRedirectsAreItsOwnTooAndTheFloorAndHistoryKeepNone() {
        assertEquals(OwnNavigation.Redirecting(NavigationKind.BackForward), OwnNavigation.Restore.afterAllowed(NavigationKind.BackForward))
        assertEquals(
            NavigationVerdict.Own,
            decide("https://www.example.com/", NavigationKind.BackForward, own = OwnNavigation.Restore.afterAllowed(NavigationKind.BackForward), fromNetwork = true),
        )
        assertEquals(null, OwnNavigation.Floor.afterAllowed(NavigationKind.Other))
        // A reload's and history's redirects keep their kind, which is never asked about.
        assertEquals(null, OwnNavigation.Reload.afterAllowed(NavigationKind.Reload))
        assertEquals(null, OwnNavigation.History.afterAllowed(NavigationKind.BackForward))
        assertEquals(NavigationVerdict.Allow, decide("https://www.example.com/", NavigationKind.Reload, fromNetwork = true))
    }

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

    /** What WebKit's request for each looked like on macOS 26: a redirect's carries the network's own headers. */
    @Test fun aRequestWebKitHasSentNamesTheNetworksOwnHeaders() {
        val redirect = listOf("Accept", "Sec-Fetch-Site", "Upgrade-Insecure-Requests", "Sec-Fetch-Mode", "User-Agent", "Accept-Language", "Sec-Fetch-Dest", "Priority")
        assertEquals(true, sentByTheNetwork(redirect))
        assertEquals(true, sentByTheNetwork(listOf("sec-fetch-mode")))
        assertEquals(false, sentByTheNetwork(listOf("User-Agent", "Accept")), "a page's navigation, or the app's load")
        assertEquals(false, sentByTheNetwork(emptyList()))
    }

    @Test fun noUrlIsRefused() {
        assertEquals(NavigationVerdict.Refuse, decide(null))
        assertEquals(NavigationVerdict.Refuse, decide(""))
    }
}
