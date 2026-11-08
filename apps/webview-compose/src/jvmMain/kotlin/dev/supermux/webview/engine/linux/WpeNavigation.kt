package dev.supermux.webview.engine.linux

import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.webkit.NavigationVerdict
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.engine.webkit.decideNavigation
import dev.supermux.webview.engine.webkit.redirectOfOwn
import dev.supermux.webview.normalizeUrl
import dev.supermux.webview.schemeOf

/** What the engine does with a navigation action WebKit asks about. */
internal sealed interface WpeVerdict {
    /** The engine's own navigation: allowed, and no longer expected. */
    data object Own : WpeVerdict

    data object Use : WpeVerdict

    data object Ignore : WpeVerdict

    /** The page reloads in-memory HTML, which WebKit would fetch from its origin: refuse it, and load the HTML again. */
    data object ReloadHtml : WpeVerdict

    /** Ask the client now, as the main frame's: a URL that gets no response (`mailto:`, `tel:`, a custom scheme). */
    data class Ask(val request: NavigationRequest) : WpeVerdict

    /** Allowed now; the client is asked at its response, where WebKit says which frame it is for. */
    data object AtResponse : WpeVerdict
}

/** What the engine does with a response WebKit asks about. */
internal sealed interface WpeResponseVerdict {
    data object Use : WpeResponseVerdict

    /** A main-frame response with no URL to ask about: refused. */
    data object Ignore : WpeResponseVerdict

    /** Ask the client, now that the frame is known. */
    data class Ask(val request: NavigationRequest) : WpeResponseVerdict
}

/**
 * The WebKit navigation rules on WPE. WebKit's decide-policy doesn't say which frame a navigation is for (F17, W4); its
 * response does. So the shared rule runs as for the main frame, which is right for the floor, the bundle scheme, the
 * engine's own loads, history and the reload of in-memory HTML. What it would ask the client about is asked where the
 * frame is known: an http(s) URL at its response, any other at the action. Main thread.
 *
 * No main-frame response gets past the client unasked but the engine's own navigation's (its load, restore, floor,
 * goBack, goForward or reload), which is kept apart from the page's, never evicted, and taken by the main frame only:
 * - a main-frame response the page's actions announced is asked about with their gesture; one nothing announced, or
 *   whose record went, is asked about too, with none;
 * - so the page's own history and reload navigations are asked about at their main-frame response: WPE can't tell a
 *   subframe's `history.back()` from the main frame's at the action, and a pass kept for one would let a subframe clear
 *   a URL for the main frame;
 * - a navigation the client allowed at its action, as the main frame's, isn't asked again at its response.
 *
 * Until it commits, the engine's own navigation's server redirects are its own too (the shared rule,
 * [OwnNavigation.Redirecting]: WebKit's `redirect` flag), to the web only. WPE doesn't say which frame a redirect is
 * for either: one an old document's frame makes meanwhile is let through at its action as well, and its subframe
 * response is asked about as before. Its main-frame response at any of the own navigation's URLs is the engine's; the
 * page can't use one, since a main-frame navigation of its own cancels the engine's first, which ends its claim
 * ([forgetOwn]).
 *
 * URLs are matched without their fragment. A main-frame commit forgets everything: the last document's frames are gone.
 */
internal class WpeNavigation(private val capacity: Int = 64) {
    /**
     * The engine's own navigation, once WebKit asked about it, and its server redirects since: its main-frame response
     * is the engine's, at any of them. A few at most: WebKit gives up on a load after 20 redirects.
     */
    private val own = LinkedHashSet<String>()

    /** The page's http(s) navigations allowed at their action and asked about at their response: URL → user gesture. */
    private val waiting = LinkedHashMap<String, Boolean>()

    /** The page's navigations the client allowed at their action as the main frame's. */
    private val allowed = LinkedHashSet<String>()

    fun decideAction(policy: WpeEvent.Policy, own: OwnNavigation?, bundle: WebOrigin?, htmlUrl: String?): WpeVerdict {
        val verdict = decideNavigation(policy.url, isMainFrame = true, opensWindow = policy.newWindow, kind = policy.kind,
            isUserGesture = policy.gesture, own = own, bundle = bundle, htmlUrl = htmlUrl, fromNetwork = policy.redirect)
        return when (verdict) {
            NavigationVerdict.Own -> {
                val key = policy.url?.let(::keyOf)
                if (redirectOfOwn(own, isMainFrame = true, kind = policy.kind, fromNetwork = policy.redirect)) {
                    // The own navigation's redirect, or another frame's: a subframe's response there is asked about.
                    if (key != null && this.own.size < MAX_REDIRECTS) {
                        this.own += key
                        waitFor(key, gesture = false)
                    }
                } else {
                    this.own.clear()
                    key?.let { this.own += it }
                }
                WpeVerdict.Own
            }
            NavigationVerdict.Allow -> WpeVerdict.Use
            NavigationVerdict.Refuse -> WpeVerdict.Ignore
            NavigationVerdict.ReloadHtml -> WpeVerdict.ReloadHtml
            is NavigationVerdict.Ask -> {
                val url = policy.url
                val scheme = schemeOf(normalizeUrl(url ?: ""))
                if (url != null && (scheme == "http" || scheme == "https")) {
                    waitFor(keyOf(url), policy.gesture)
                    WpeVerdict.AtResponse
                } else {
                    WpeVerdict.Ask(verdict.request)
                }
            }
        }
    }

    /** The client allowed [url], asked about at its action as the main frame's: its response isn't asked about again. */
    fun allowedAtAction(url: String) {
        val key = keyOf(url)
        allowed.remove(key)
        allowed += key
        while (allowed.size > capacity) allowed.remove(allowed.first())
    }

    /** What [response] gets: the client's question, now with its frame, or nothing to ask. */
    fun atResponse(response: WpeEvent.Response, bundle: WebOrigin?): WpeResponseVerdict {
        val url = response.url ?: return if (response.mainFrame) WpeResponseVerdict.Ignore else WpeResponseVerdict.Use
        val key = keyOf(url)
        if (!response.mainFrame) {
            val gesture = waiting.remove(key) ?: return WpeResponseVerdict.Use
            return WpeResponseVerdict.Ask(NavigationRequest(WebKitUrls.toLibraryUrl(url, bundle), isMainFrame = false, isUserGesture = gesture))
        }
        if (key in own) {
            own.clear()
            waiting.remove(key)
            return WpeResponseVerdict.Use
        }
        val gesture = waiting.remove(key)
        if (gesture == null && allowed.remove(key)) return WpeResponseVerdict.Use
        return WpeResponseVerdict.Ask(NavigationRequest(WebKitUrls.toLibraryUrl(url, bundle), isMainFrame = true, isUserGesture = gesture ?: false))
    }

    /** Whether [url] is one of the engine's own navigation's: its URL, or one of its server redirects'. */
    fun isOwn(url: String?): Boolean = url != null && keyOf(url) in own

    /** The engine's own navigation ended (it failed, or the page's own navigation cancelled it): none of its URLs is its. */
    fun forgetOwn() = own.clear()

    fun clear() {
        own.clear()
        waiting.clear()
        allowed.clear()
    }

    /** A main-frame response at [key] is asked about with [gesture]; the oldest record goes past [capacity]. */
    private fun waitFor(key: String, gesture: Boolean) {
        waiting.remove(key)
        waiting[key] = gesture
        while (waiting.size > capacity) waiting.remove(waiting.keys.first())
    }

    private fun keyOf(url: String): String = url.substringBefore('#')

    private companion object {
        /** The own navigation's URLs kept: past WebKit's own limit of 20 redirects, a load fails anyway. */
        const val MAX_REDIRECTS = 32
    }
}
