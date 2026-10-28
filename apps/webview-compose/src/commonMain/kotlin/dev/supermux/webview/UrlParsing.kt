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
