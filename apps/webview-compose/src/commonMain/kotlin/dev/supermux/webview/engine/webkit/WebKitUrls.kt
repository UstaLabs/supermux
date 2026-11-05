package dev.supermux.webview.engine.webkit

import dev.supermux.webview.WebOrigin
import dev.supermux.webview.authorityOf
import dev.supermux.webview.hostOf
import dev.supermux.webview.normalizeUrl
import dev.supermux.webview.schemeOf

/**
 * The URLs and origins of the WebKit engines (iOS now, macOS later).
 *
 * WebKit can't serve `https` from a `WKURLSchemeHandler`, so a [dev.supermux.webview.WebContent.Bundle] of origin
 * `https://<host>.invalid` is served at `webview-bundle://<host>.invalid/…`. WebKit gives a scheme handler's URLs a
 * tuple origin and a secure context. The engine reports such documents in the library's terms (`https://<host>.invalid`)
 * and maps back to deliver. The page itself sees `webview-bundle://<host>.invalid` as its origin.
 *
 * The floor (`webview-floor://reset/`) is the one page an engine shows between leases; no lease ever reaches it. The
 * macOS engine's is on the bundles' scheme ([BUNDLE_FLOOR_URL]): WebKit keeps a page in its WebContent process across
 * navigations within one scheme of an app's own, and starts a new one for a navigation to another, which can hold
 * AppKit's thread for seconds (2026-11-05): so a reset between two bundle pages, and the load after it, start none.
 */
internal object WebKitUrls {
    const val BUNDLE_SCHEME: String = "webview-bundle"
    const val FLOOR_SCHEME: String = "webview-floor"
    const val FLOOR_URL: String = "$FLOOR_SCHEME://reset/"

    /** The macOS engine's floor, on the bundles' scheme, at a host of two labels: no bundle's (a [WebOrigin]'s has one). */
    const val FLOOR_HOST: String = "reset.floor.invalid"
    const val BUNDLE_FLOOR_URL: String = "$BUNDLE_SCHEME://$FLOOR_HOST/"

    /** The floor: blank, in the theme the web view is given. */
    const val FLOOR_HTML: String = "<!doctype html><html><head><meta name=\"color-scheme\" content=\"light dark\">" +
        "<meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"

    /** Where WebKit serves [origin]'s bundle file at [path], a path relative to the bundle's root. */
    fun bundleUrl(origin: WebOrigin, path: String): String = "${bundleOrigin(origin)}/$path"

    /** [origin] as WebKit writes the origin of its bundle: `webview-bundle://<host>.invalid`. */
    fun bundleOrigin(origin: WebOrigin): String = "$BUNDLE_SCHEME://${origin.host}.${WebOrigin.SUFFIX}"

    /** Whether [url] is one of [bundle]'s files. */
    fun isBundleUrl(url: String, bundle: WebOrigin): Boolean =
        schemeOf(url) == BUNDLE_SCHEME && hostOf(url) == "${bundle.host}.${WebOrigin.SUFFIX}"

    /** Whether [url] is on one of the engine's own schemes, which the page never navigates to by itself. */
    fun isPrivate(url: String): Boolean = schemeOf(url).let { it == BUNDLE_SCHEME || it == FLOOR_SCHEME }

    fun isFloor(url: String?): Boolean =
        url != null && schemeOf(url).let { it == FLOOR_SCHEME || it == BUNDLE_SCHEME && hostOf(url) == FLOOR_HOST }

    /** [url] in the library's terms: a file of [bundle] becomes its `https://<host>.invalid/…` URL; anything else stays. */
    fun toLibraryUrl(url: String, bundle: WebOrigin?): String {
        if (bundle == null || !isBundleUrl(url, bundle)) return url
        val cleaned = normalizeUrl(url)
        return "https" + cleaned.substring(cleaned.indexOf(':'))
    }

    /**
     * The library's name for the origin WebKit reports as [protocol], [host] and [port] (0: the scheme's default), as
     * browsers serialize origins. An empty protocol or host is an opaque origin, `"null"`. [bundle]'s WebKit origin is
     * its `https://<host>.invalid`.
     */
    fun libraryOrigin(protocol: String, host: String, port: Int, bundle: WebOrigin?): String {
        if (protocol.isEmpty() || host.isEmpty()) return "null"
        val scheme = protocol.lowercase()
        val name = host.lowercase().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
        if (bundle != null && scheme == BUNDLE_SCHEME && name == "${bundle.host}.${WebOrigin.SUFFIX}" && port == 0) {
            return bundle.origin
        }
        val defaultPort = when (scheme) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            else -> -1
        }
        return if (port == 0 || port == defaultPort) "$scheme://$name" else "$scheme://$name:$port"
    }

    /**
     * The origins whose documents get the shim, as the pages themselves see them: [origins] (the library's terms),
     * plus the bundle origin of each local `https://<host>.invalid` among them.
     */
    fun pageOrigins(origins: Set<String>): Set<String> {
        val out = LinkedHashSet(origins)
        for (origin in origins) {
            val host = hostOf(origin) ?: continue
            if (schemeOf(origin) == "https" && host.endsWith(".${WebOrigin.SUFFIX}") && host.count { it == '.' } == 1) {
                out += "$BUNDLE_SCHEME://$host"
            }
        }
        return out
    }

    /** [origin] as the page sees it: [bundle]'s library origin is its bundle origin; any other origin stays. */
    fun pageOrigin(origin: String, bundle: WebOrigin?): String =
        if (bundle != null && origin == bundle.origin) bundleOrigin(bundle) else origin

    /** Whether the lease can go back: there is an item after the newest floor item in [backUrls], oldest first. */
    fun canGoBack(backUrls: List<String?>): Boolean = backUrls.size - (backUrls.indexOfLast(::isFloor) + 1) > 0

    /** Whether the lease can go forward: a forward item that isn't the floor. */
    fun canGoForward(forwardUrls: List<String?>): Boolean = forwardUrls.any { !isFloor(it) }

    /** The raw path of [url]: what follows its authority, query and fragment included; "" when there is none. */
    fun rawPath(url: String): String {
        val afterScheme = normalizeUrl(url).substringAfter("://", missingDelimiterValue = "")
        val end = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (end < 0) "" else afterScheme.substring(end)
    }

    /**
     * Whether two URLs name the same resource, as WebKit hands back a URL the engine asked to load: scheme and host
     * compared without case, a default port and an empty path made explicit.
     */
    fun sameUrl(a: String?, b: String?): Boolean = a != null && b != null && canonical(a) == canonical(b)

    private fun canonical(url: String): String {
        val cleaned = normalizeUrl(url)
        val scheme = schemeOf(cleaned) ?: return cleaned
        val authority = authorityOf(cleaned) ?: return scheme + cleaned.substring(scheme.length)
        val defaultPort = when (scheme) {
            "https" -> ":443"
            "http" -> ":80"
            else -> null
        }
        val hostPort = if (defaultPort != null && authority.endsWith(defaultPort)) authority.dropLast(defaultPort.length) else authority
        val rest = rawPath(cleaned).ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        return "$scheme://$hostPort$rest"
    }
}
