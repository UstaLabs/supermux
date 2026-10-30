package dev.supermux.webview.engine.linux

import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.webkit.NavigationVerdict
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.engine.webkit.decideNavigation
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
 * URLs are matched without their fragment. A main-frame commit forgets everything: the last document's frames are gone.
 */
internal class WpeNavigation(private val capacity: Int = 64) {
    /** The engine's own navigation, once WebKit asked about it: its main-frame response is the engine's. */
    private var own: String? = null

    /** The page's http(s) navigations allowed at their action and asked about at their response: URL → user gesture. */
    private val waiting = LinkedHashMap<String, Boolean>()

    /** The page's navigations the client allowed at their action as the main frame's. */
    private val allowed = LinkedHashSet<String>()

    fun decideAction(policy: WpeEvent.Policy, own: OwnNavigation?, bundle: WebOrigin?, htmlUrl: String?): WpeVerdict {
        val verdict = decideNavigation(policy.url, isMainFrame = true, opensWindow = policy.newWindow, kind = policy.kind,
            isUserGesture = policy.gesture, own = own, bundle = bundle, htmlUrl = htmlUrl)
        return when (verdict) {
            NavigationVerdict.Own -> {
                this.own = policy.url?.let(::keyOf)
                WpeVerdict.Own
            }
            NavigationVerdict.Allow -> WpeVerdict.Use
            NavigationVerdict.Refuse -> WpeVerdict.Ignore
            NavigationVerdict.ReloadHtml -> WpeVerdict.ReloadHtml
            is NavigationVerdict.Ask -> {
                val url = policy.url
                val scheme = schemeOf(normalizeUrl(url ?: ""))
                if (url != null && (scheme == "http" || scheme == "https")) {
                    val key = keyOf(url)
                    waiting.remove(key)
                    waiting[key] = policy.gesture
                    while (waiting.size > capacity) waiting.remove(waiting.keys.first())
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
        if (own == key) {
            own = null
            return WpeResponseVerdict.Use
        }
        val gesture = waiting.remove(key)
        if (gesture == null && allowed.remove(key)) return WpeResponseVerdict.Use
        return WpeResponseVerdict.Ask(NavigationRequest(WebKitUrls.toLibraryUrl(url, bundle), isMainFrame = true, isUserGesture = gesture ?: false))
    }


    fun clear() {
        own = null
        waiting.clear()
        allowed.clear()
    }

    private fun keyOf(url: String): String = url.substringBefore('#')
}
