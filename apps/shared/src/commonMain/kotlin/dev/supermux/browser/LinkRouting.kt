package dev.supermux.browser

import dev.supermux.net.isOpenableLink
import dev.supermux.net.linkScheme
import io.ktor.http.Url

/** A Browser tab as link routing sees it: its id and the page it shows now. */
data class OpenBrowserTab(val id: String, val url: String?)

/** Where a link from the chat opens (#105, Ahmet's rule). */
sealed interface LinkRoute {
    data class NewTab(val url: String) : LinkRoute

    /** A tab already shows this very link: it comes forward, nothing reloads. */
    data class Focus(val tabId: String) : LinkRoute

    /** The system's browser or handler: a ⌘/Ctrl-click or long-press, mail, phone, texts. */
    data class System(val url: String) : LinkRoute

    /** Never opened: anything the allowlist refuses (javascript:, file:, data:, the app's own scheme, relative links). */
    data object Refused : LinkRoute
}

/**
 * A link opens in a NEW Browser tab, unless a tab already shows that same link: that tab comes forward. A modified
 * click goes to the system browser. "The same link" ignores only the host's case; another fragment is another link.
 */
fun routeLink(url: String, tabs: List<OpenBrowserTab>, modified: Boolean = false): LinkRoute {
    if (!isOpenableLink(url)) return LinkRoute.Refused
    val scheme = linkScheme(url)
    if (scheme != "http" && scheme != "https" || modified) return LinkRoute.System(url)
    val key = sameLinkKey(url) ?: return LinkRoute.NewTab(url)
    tabs.firstOrNull { tab -> tab.url?.let(::sameLinkKey) == key }?.let { return LinkRoute.Focus(it.id) }
    return LinkRoute.NewTab(url)
}

private fun sameLinkKey(url: String): String? = runCatching { Url(url) }.getOrNull()?.let { u ->
    val query = if (u.encodedQuery.isNotEmpty()) "?${u.encodedQuery}" else ""
    "${u.protocol.name}://${u.host.lowercase()}:${u.port}${u.encodedPath.ifEmpty { "/" }}$query#${u.encodedFragment}"
}
