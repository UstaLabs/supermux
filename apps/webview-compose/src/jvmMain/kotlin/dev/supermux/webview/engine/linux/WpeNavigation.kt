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

/**
 * The WebKit navigation rules on WPE. WebKit's decide-policy doesn't say which frame a navigation is for (F17, W4); its
 * response does. So the shared rule runs as for the main frame, which is right for the floor, the bundle scheme, the
 * engine's own loads, history and the reload of in-memory HTML. What it would ask the client about is asked where the
 * frame is known: an http(s) URL at its response, any other at the action. Main thread.
 */
internal class WpeNavigation(private val capacity: Int = 64) {
    /** http(s) navigations allowed at their action and asked about at their response: URL → user gesture. */
    private val waiting = LinkedHashMap<String, Boolean>()

    fun decideAction(policy: WpeEvent.Policy, own: OwnNavigation?, bundle: WebOrigin?, htmlUrl: String?): WpeVerdict {
        val verdict = decideNavigation(policy.url, isMainFrame = true, opensWindow = policy.newWindow, kind = policy.kind,
            isUserGesture = policy.gesture, own = own, bundle = bundle, htmlUrl = htmlUrl)
        return when (verdict) {
            NavigationVerdict.Own -> WpeVerdict.Own
            NavigationVerdict.Allow -> WpeVerdict.Use
            NavigationVerdict.Refuse -> WpeVerdict.Ignore
            NavigationVerdict.ReloadHtml -> WpeVerdict.ReloadHtml
            is NavigationVerdict.Ask -> {
                val url = policy.url
                val scheme = schemeOf(normalizeUrl(url ?: ""))
                if (url != null && (scheme == "http" || scheme == "https")) {
                    waiting.remove(url)
                    waiting[url] = policy.gesture
                    while (waiting.size > capacity) waiting.remove(waiting.keys.first())
                    WpeVerdict.AtResponse
                } else {
                    WpeVerdict.Ask(verdict.request)
                }
            }
        }
    }

    /** The client's question for a navigation [decideAction] left to its response, now with its frame; null if none waited. */
    fun requestAtResponse(response: WpeEvent.Response, bundle: WebOrigin?): NavigationRequest? {
        val url = response.url ?: return null
        val gesture = waiting.remove(url) ?: return null
        return NavigationRequest(WebKitUrls.toLibraryUrl(url, bundle), isMainFrame = response.mainFrame, isUserGesture = gesture)
    }

    fun clear() = waiting.clear()
}
