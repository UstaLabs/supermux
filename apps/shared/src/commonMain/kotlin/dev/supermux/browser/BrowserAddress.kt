package dev.supermux.browser

import dev.supermux.net.ProxyDto
import dev.supermux.net.linkScheme
import dev.supermux.util.proxyUrl
import io.ktor.http.Url

/** What the user typed in a Browser tab's address (#105). No search: text that isn't an address says so. */
sealed interface AddressInput {
    data class Go(val url: String) : AddressInput

    /** A port on the host machine: a bare `3000`, `localhost:3000`, `127.0.0.1:3000`, with its path and query. */
    data class Port(val port: Int, val path: String) : AddressInput

    data class NotAnAddress(val text: String) : AddressInput
}

/** Where a port on the host opens from this device. */
sealed interface PortTarget {
    data class Go(val url: String) : PortTarget

    /** Nothing exposes the port yet, and this device isn't the host, whose localhost it is: the tab offers a private link. */
    data class OfferPreview(val port: Int, val path: String) : PortTarget
}

/** How the address bar shows a page: a preview as its port (not its relay URL), the scheme always hidden. */
sealed interface DisplayAddress {
    data class Preview(val port: Int, val path: String) : DisplayAddress

    data class Local(val port: Int, val path: String) : DisplayAddress

    data class Web(val host: String, val path: String) : DisplayAddress
}

private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")
private val LOCAL_START = Regex("^(localhost|127\\.0\\.0\\.1|\\[::1])(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)

fun parseAddress(text: String): AddressInput {
    val t = text.trim()
    val no = AddressInput.NotAnAddress(t)
    if (t.isEmpty() || t.any { it.isWhitespace() }) return no
    if (t.all { it.isDigit() }) return t.toIntOrNull()?.takeIf { it in 1..65535 }?.let { AddressInput.Port(it, "/") } ?: no
    // A scheme other than the web's (javascript:, data:, mailto:) is never an address; `host:port` is not a scheme.
    val scheme = linkScheme(t)
    if (scheme != null && scheme != "http" && scheme != "https" && !t.substringAfter(':').first().isDigit()) return no
    val withScheme = when {
        t.contains("://") -> t
        LOCAL_START.matches(t) -> "http://$t"
        else -> "https://$t"
    }
    val url = runCatching { Url(withScheme) }.getOrNull() ?: return no
    if (url.protocol.name != "http" && url.protocol.name != "https") return no
    val host = url.host.lowercase()
    if (host in LOCAL_HOSTS) return AddressInput.Port(url.port, pathAndQuery(url))
    if ('.' !in host) return no
    return AddressInput.Go(withScheme)
}

/** [port] on the host: itself when this app runs there, else its preview link, else an offer to make one. */
fun resolvePort(port: Int, path: String, previews: List<ProxyDto>, onHost: Boolean): PortTarget {
    if (onHost) return PortTarget.Go("http://localhost:$port$path")
    val preview = previews.firstOrNull { it.port == port } ?: return PortTarget.OfferPreview(port, path)
    return PortTarget.Go(proxyUrl(preview).trimEnd('/') + path)
}

fun displayAddress(url: String, previews: List<ProxyDto>): DisplayAddress {
    for (p in previews) {
        val base = proxyUrl(p).trimEnd('/')
        if (url == base || url.startsWith("$base/")) return DisplayAddress.Preview(p.port, url.removePrefix(base).ifEmpty { "/" })
    }
    val parsed = runCatching { Url(url) }.getOrNull() ?: return DisplayAddress.Web(url, "/")
    val host = parsed.host.lowercase()
    return if (host in LOCAL_HOSTS) DisplayAddress.Local(parsed.port, pathAndQuery(parsed)) else DisplayAddress.Web(host, pathAndQuery(parsed))
}

private fun pathAndQuery(url: Url): String =
    url.encodedPath.ifEmpty { "/" } + if (url.encodedQuery.isNotEmpty()) "?${url.encodedQuery}" else ""
