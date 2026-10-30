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

    /**
     * The engine's own navigation of [kind], allowed and not committed yet. Until it commits, the server's redirects
     * of it are the engine's own too ([redirectOfOwn]): followed to the web without asking the client, as the client
     * decides no navigation the app starts, and refused anywhere else. The engines clear it at the commit, or when the
     * navigation fails.
     */
    data class Redirecting(val kind: NavigationKind) : OwnNavigation

    /**
     * What the engine keeps once [decideNavigation] allowed this navigation as its own, of [kind]: [Redirecting] for a
     * load or a restore, whose redirects would otherwise be asked about. A reload's and history's keep their kind,
     * which is never asked about, and the floor's never leave its scheme: they keep nothing.
     */
    fun afterAllowed(kind: NavigationKind): OwnNavigation? = when (this) {
        is Load, Restore, is Redirecting -> Redirecting(kind)
        Reload, History, Floor -> null
    }
}

/**
 * Whether a navigation action's request carries the headers only WebKit's network layer adds (`Sec-Fetch-*`,
 * `Upgrade-Insecure-Requests`): a request it has sent already, as a server redirect's is. A navigation the page starts,
 * or the app's own load, comes to its policy decision before anything is sent, without them. Seen on macOS 26: a
 * redirect's request to the web names `Sec-Fetch-Site`, `-Mode` and `-Dest`, `Upgrade-Insecure-Requests`,
 * `Accept-Language` and `Priority`; one to a scheme that isn't the web's, `Upgrade-Insecure-Requests`; a script's
 * `location` change during the same provisional load, and the app's load, `User-Agent` and `Accept` only.
 */
internal fun sentByTheNetwork(headerNames: Collection<String>): Boolean =
    headerNames.any { it.startsWith("Sec-Fetch-", ignoreCase = true) || it.equals("Upgrade-Insecure-Requests", ignoreCase = true) }

/**
 * Whether a navigation action is a server redirect of the engine's own navigation, [own]: in the main frame, of the
 * same kind, its request one WebKit has sent ([fromNetwork], [sentByTheNetwork]). WebKit's public API says no more
 * (`didReceiveServerRedirectForProvisionalNavigation:` comes after the decision), and a navigation the old document
 * starts while the load is provisional has the same kind and frame: only its unsent request tells it apart.
 * [decideNavigation] allows it to the web, and refuses it anywhere else; the engine then ends the load with an error,
 * since WebKit's own report of a refused navigation is one the engines ignore.
 */
internal fun redirectOfOwn(own: OwnNavigation?, isMainFrame: Boolean, kind: NavigationKind, fromNetwork: Boolean): Boolean =
    isMainFrame && fromNetwork && own is OwnNavigation.Redirecting && own.kind == kind

private fun isWeb(url: String): Boolean = schemeOf(url).let { it == "http" || it == "https" }

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
 * - Once allowed, until it commits, its server redirects ([redirectOfOwn]; [fromNetwork]: WebKit has sent the
 *   request) are its own to the web, http(s), and refused anywhere else, private schemes included.
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
    fromNetwork: Boolean = false,
): NavigationVerdict {
    if (url.isNullOrEmpty()) return NavigationVerdict.Refuse
    if (redirectOfOwn(own, isMainFrame, kind, fromNetwork)) return if (isWeb(url)) NavigationVerdict.Own else NavigationVerdict.Refuse
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
    is OwnNavigation.Redirecting -> false
}
