package dev.supermux.webview.engine.android

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.hostOf
import dev.supermux.webview.localOrigin
import dev.supermux.webview.normalizeBundlePath
import dev.supermux.webview.normalizeUrl

/** What the engine answers for a request it may serve itself (spec §7 "Local content"). */
internal sealed interface LocalAnswer {
    /** Not a local origin: WebView loads it from the network. */
    data object Network : LocalAnswer

    /** The page of a [WebContent.Html]. */
    class Html(val html: String) : LocalAnswer

    /** A bundle's file: [path] from [resources], or a 404 if they have none. */
    class BundleFile(val resources: WebResourceProvider, val path: String) : LocalAnswer

    data object NotFound : LocalAnswer

    data object MethodNotAllowed : LocalAnswer
}

/**
 * How the engine answers a request for [url] made with [method] while it serves [content] (spec §7: local content is
 * served from `https://<origin>.invalid/` through `shouldInterceptRequest`, and `.invalid` never resolves, so a missed
 * interception fails closed).
 * - Every request to a `.invalid` host is the engine's, never the network's: a resolver would see the host, and the
 *   request would fail anyway. Only [content]'s own origin is served, over https, with GET or HEAD; any other request
 *   there is a 404, or a 405 for another method.
 * - [WebContent.Html] is served at its origin's root only: it inlines its assets.
 * - [WebContent.Bundle] serves [normalizeBundlePath]'s path, and its entry at the root.
 */
internal fun answerFor(url: String, method: String, content: WebContent?): LocalAnswer {
    val host = hostOf(url) ?: return LocalAnswer.Network
    val name = host.trimEnd('.')
    if (name != WebOrigin.SUFFIX && !name.endsWith(".${WebOrigin.SUFFIX}")) return LocalAnswer.Network
    val local = content?.localOrigin
    if (local == null || !local.owns(url)) return LocalAnswer.NotFound
    if (!method.equals("GET", ignoreCase = true) && !method.equals("HEAD", ignoreCase = true)) {
        return LocalAnswer.MethodNotAllowed
    }
    val path = pathOf(url)
    return when (content) {
        is WebContent.Html -> if (path == "/" || path.isEmpty()) LocalAnswer.Html(content.html) else LocalAnswer.NotFound
        is WebContent.Bundle -> {
            val file = normalizeBundlePath(path) ?: return LocalAnswer.NotFound
            LocalAnswer.BundleFile(content.resources, file.ifEmpty { content.entry })
        }
        is WebContent.Url -> LocalAnswer.NotFound
    }
}

/** The path of a hierarchical [url]: from the slash after its authority up to its query or fragment; "" if none. */
private fun pathOf(url: String): String {
    val rest = normalizeUrl(url).substringAfter("://", "")
    val start = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (start < 0 || rest[start] != '/') return ""
    return rest.substring(start).substringBefore('?').substringBefore('#')
}
