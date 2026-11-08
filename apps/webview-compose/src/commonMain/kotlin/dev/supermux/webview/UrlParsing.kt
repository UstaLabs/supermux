package dev.supermux.webview

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

/** Schemes a browser parses leniently: any run of `/` and `\` after the colon, and `\` ends the authority. */
private val SPECIAL_SCHEMES = setOf("http", "https", "ws", "wss", "ftp")

/** What a browser's URL parser does first: drop ASCII tab and newline anywhere, trim C0 controls and spaces. */
internal fun normalizeUrl(url: String): String =
    url.filterNot { it == '\t' || it == '\n' || it == '\r' }.trim { it <= ' ' }

/** The lowercased scheme of [url], or null when it has none. */
internal fun schemeOf(url: String): String? = SCHEME.find(normalizeUrl(url))?.groupValues?.get(1)?.lowercase()

/** The lowercased authority (`userinfo@host:port`) of a hierarchical URL, or null. */
internal fun authorityOf(url: String): String? {
    val cleaned = normalizeUrl(url)
    val scheme = schemeOf(cleaned) ?: return null
    val rest = cleaned.substring(scheme.length + 1)
    val special = scheme in SPECIAL_SCHEMES
    val start = when {
        special -> rest.trimStart('/', '\\')
        rest.startsWith("//") -> rest.substring(2)
        else -> return null
    }
    return start.takeWhile { it != '/' && it != '?' && it != '#' && !(special && it == '\\') }.lowercase()
}

/** The lowercased host of a hierarchical URL, without userinfo or port, or null. */
internal fun hostOf(url: String): String? {
    val hostPort = authorityOf(url)?.substringAfterLast('@') ?: return null
    val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    return host.ifEmpty { null }
}

/**
 * The origin of [url] as browsers serialize it: `scheme://host[:port]`, lowercased, with the scheme's default port
 * dropped. URLs without a host (about:, data:, unparseable text) have the opaque origin `"null"`.
 */
internal fun originOf(url: String): String {
    val scheme = schemeOf(url) ?: return "null"
    if (scheme == "blob") {
        // WHATWG: a blob URL has the origin of the URL inside it when that one is http(s); otherwise it is opaque.
        val inner = normalizeUrl(url).substringAfter(':')
        return if (schemeOf(inner).let { it == "http" || it == "https" }) originOf(inner) else "null"
    }
    val hostPort = authorityOf(url)?.substringAfterLast('@')?.takeIf { it.isNotEmpty() } ?: return "null"
    val defaultPort = when (scheme) {
        "https", "wss" -> ":443"
        "http", "ws" -> ":80"
        else -> null
    }
    val normalized = if (defaultPort != null && hostPort.endsWith(defaultPort)) hostPort.dropLast(defaultPort.length) else hostPort
    return "$scheme://$normalized"
}
