package dev.supermux.webview.engine.windows

import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.NavigationVerdict
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.decideNavigation
import dev.supermux.webview.engine.webkit.redirectOfOwn
import dev.supermux.webview.hostOf
import dev.supermux.webview.schemeOf
import java.util.concurrent.atomic.AtomicReference

/**
 * What the engine publishes for the glue's UI thread (D9): a new instance at every change of the lease, never changed.
 * The contract: an engine that answers `onNavigation` off main reads [client] through a copy it publishes safely.
 */
internal class NavigationSnapshot(val released: Boolean, val client: EngineClient?) {
    companion object {
        val Closed = NavigationSnapshot(released = true, client = null)
    }
}

/**
 * The navigation policy on WebView2 (D9), answered inside `NavigationStarting` on the glue's UI thread: pure, but for the
 * claim of the engine's own navigation, an [AtomicReference] the EDT sets at every load.
 * - The floor ([FLOOR_URL]) is reached only by the engine's own floor navigation, in the main frame.
 * - The rest is the WebKit engines' common rule ([decideNavigation]): the engine's own navigation, matched by kind and
 *   URL, is claimed once, and its server redirects (`IsRedirected`) go to the web without asking, and nowhere else
 *   ([WinGlue.REFUSE_OWN]: the engine ends the load with an error); history and reloads aren't asked about; everything the
 *   page starts is asked about, with WebView2's own `IsUserInitiated` as the gesture (L2).
 * - No client, a client that fails, or a released engine: refused.
 */
internal object Wv2Navigation {
    const val FLOOR_HOST: String = "reset.floor.invalid"
    const val FLOOR_URL: String = "https://$FLOOR_HOST/"

    /** The floor's page: blank, in the theme the engine is given until the next lease's configure. */
    fun floorHtml(dark: Boolean): String = "<!doctype html><html><head><meta name=\"color-scheme\" content=\"light dark\">" +
        "<style>html,body{margin:0;height:100%;background:${if (dark) "#000" else "#fff"}}</style></head><body></body></html>"

    fun isFloor(url: String?): Boolean = url != null && schemeOf(url) == "https" && hostOf(url) == FLOOR_HOST

    /** WebView2's kind as the common rule's: a new document is a link when the user started it, else "other". */
    fun kindOf(flags: Int): NavigationKind = when (((flags shr 4) and 0xF) - 1) {
        WinGlue.KIND_RELOAD -> NavigationKind.Reload
        WinGlue.KIND_BACK_OR_FORWARD -> NavigationKind.BackForward
        else -> if (flags and WinGlue.NAV_USER != 0) NavigationKind.Link else NavigationKind.Other
    }

    fun decide(flags: Int, url: String?, snapshot: NavigationSnapshot, own: AtomicReference<OwnNavigation?>): Int =
        decide(flags, url, snapshot, own::get, own::compareAndSet)

    /** [decide] with the marker's [read] and its [claim] (a compare-and-set) given apart: a test races them. */
    internal fun decide(
        flags: Int,
        url: String?,
        snapshot: NavigationSnapshot,
        read: () -> OwnNavigation?,
        claim: (expected: OwnNavigation?, next: OwnNavigation?) -> Boolean,
    ): Int {
        if (snapshot.released || url.isNullOrEmpty()) return WinGlue.REFUSE
        val mainFrame = flags and WinGlue.NAV_MAIN_FRAME != 0
        val redirect = flags and WinGlue.NAV_REDIRECT != 0
        val gesture = flags and WinGlue.NAV_USER != 0
        val kind = kindOf(flags)
        while (true) {
            val current = read()
            if (isFloor(url)) {
                if (!mainFrame || current != OwnNavigation.Floor) return WinGlue.REFUSE
                if (claim(current, null)) return WinGlue.OWN
                continue
            }
            val verdict = decideNavigation(
                url = url, isMainFrame = mainFrame, opensWindow = false, kind = kind, isUserGesture = gesture,
                own = current, bundle = null, htmlUrl = null, fromNetwork = redirect,
            )
            when (verdict) {
                NavigationVerdict.Own -> if (claim(current, current?.afterAllowed(kind))) return WinGlue.OWN
                NavigationVerdict.Allow -> return WinGlue.ALLOW
                NavigationVerdict.Refuse -> return if (redirectOfOwn(current, mainFrame, kind, redirect)) WinGlue.REFUSE_OWN else WinGlue.REFUSE
                // No htmlUrl is passed: WebView2 serves in-memory HTML again itself on a reload (D7).
                NavigationVerdict.ReloadHtml -> return WinGlue.ALLOW
                is NavigationVerdict.Ask -> return ask(snapshot.client, verdict.request)
            }
        }
    }

    private fun ask(client: EngineClient?, request: NavigationRequest): Int = try {
        if (client?.onNavigation(request) == NavigationDecision.Allow) WinGlue.ALLOW else WinGlue.REFUSE
    } catch (e: Exception) {
        WinGlue.REFUSE
    }
}
