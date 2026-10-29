package dev.supermux.webview.engine.webkit

import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.schemeOf

/** What started a navigation, as WebKit reports it (`WKNavigationType`). */
internal enum class NavigationKind { Link, FormSubmit, BackForward, Reload, FormResubmit, Other }

/**
 * A navigation the engine started itself: the next main-frame navigation decision that matches it is that
 * navigation, which the client is never asked about (the [dev.supermux.webview.engine.WebEngine] contract: loads,
 * restores, back, forward and reload never go through the client).
 */
internal sealed interface OwnNavigation {
    /** A load of [url]: content the app asked for, or a page's new window it opened here. */
    data class Load(val url: String) : OwnNavigation

    data object Reload : OwnNavigation

    /** `goBack` or `goForward`. */
    data object History : OwnNavigation

    /** A saved state's current page, after `interactionState` was set. */
    data object Restore : OwnNavigation

    /** The floor: at creation, and when a reset restores the floor state. */
    data object Floor : OwnNavigation
}

/** What the navigation delegate does with a navigation action. */
internal sealed interface NavigationVerdict {
    /** The engine's own navigation: allowed, and no longer expected. */
    data object Own : NavigationVerdict

    /**
     * Allowed without asking: history and reloads, which are never the page's to vet, and new-window requests, which
     * the client is asked about when WebKit asks for the window.
     */
    data object Allow : NavigationVerdict

    data object Refuse : NavigationVerdict

    /** The page reloads in-memory HTML, which WebKit would fetch from the network: refuse it, and load the HTML again. */
    data object ReloadHtml : NavigationVerdict

    /** A navigation the page started: the client decides. */
    data class Ask(val request: NavigationRequest) : NavigationVerdict
}

/**
 * The navigation delegate's decision about a navigation to [url].
 * - The floor is reached only by the engine's own [OwnNavigation.Floor]: never by the page, by history, or by a
 *   restored state's history.
 * - The bundle scheme serves only the lease's [bundle]: any other bundle URL is refused, whatever the client says.
 * - A request for a new window ([opensWindow]) is allowed here: WebKit then asks the UI delegate for the window, and
 *   the engine asks the client's `onNewWindow` there.
 * - [own], the engine's own pending navigation, is matched by kind, and a load by its URL ([WebKitUrls.sameUrl]).
 * - A page reloading in-memory HTML ([htmlUrl], the lease's `Html` document URL) gets the HTML loaded again.
 * - History and reloads aren't asked about. Everything else the page starts is: in the library's URL terms.
 */
internal fun decideNavigation(
    url: String?,
    isMainFrame: Boolean,
    opensWindow: Boolean,
    kind: NavigationKind,
    isUserGesture: Boolean,
    own: OwnNavigation?,
    bundle: WebOrigin?,
    htmlUrl: String?,
): NavigationVerdict {
    if (url.isNullOrEmpty()) return NavigationVerdict.Refuse
    if (WebKitUrls.isFloor(url)) {
        return if (own == OwnNavigation.Floor && isMainFrame) NavigationVerdict.Own else NavigationVerdict.Refuse
    }
    if (schemeOf(url) == WebKitUrls.BUNDLE_SCHEME && (bundle == null || !WebKitUrls.isBundleUrl(url, bundle))) {
        return NavigationVerdict.Refuse
    }
    if (opensWindow) return NavigationVerdict.Allow
    if (isMainFrame && own != null && own != OwnNavigation.Floor && isOwn(own, kind, url)) return NavigationVerdict.Own
    if (isMainFrame && htmlUrl != null && kind == NavigationKind.Reload && WebKitUrls.sameUrl(url, htmlUrl)) {
        return NavigationVerdict.ReloadHtml
    }
    if (kind == NavigationKind.BackForward || kind == NavigationKind.Reload || kind == NavigationKind.FormResubmit) {
        return NavigationVerdict.Allow
    }
    return NavigationVerdict.Ask(NavigationRequest(WebKitUrls.toLibraryUrl(url, bundle), isMainFrame, isUserGesture))
}

private fun isOwn(own: OwnNavigation, kind: NavigationKind, url: String): Boolean = when (own) {
    is OwnNavigation.Load -> kind == NavigationKind.Other && WebKitUrls.sameUrl(url, own.url)
    OwnNavigation.Reload -> kind == NavigationKind.Reload
    OwnNavigation.History -> kind == NavigationKind.BackForward
    OwnNavigation.Restore -> kind == NavigationKind.BackForward || kind == NavigationKind.Other
    OwnNavigation.Floor -> false
}
