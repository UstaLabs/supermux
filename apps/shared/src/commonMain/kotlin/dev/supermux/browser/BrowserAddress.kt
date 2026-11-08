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

    /** No way to it from here: this device isn't the host, the host gave no by-port previews, and nothing exposes it. */
    data class NoRoute(val port: Int) : PortTarget
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

/**
 * [port] on the host: itself when this app runs there, else the host's by-port preview ([byPort], a template such as
 * `https://…/p/{port}/`; Ahmet 2026-11-08: no registration, no question), else a named preview of the port, else none.
 */
fun resolvePort(port: Int, path: String, previews: List<ProxyDto>, onHost: Boolean, byPort: String? = null): PortTarget {
    if (onHost) return PortTarget.Go("http://localhost:$port$path")
    if (byPort != null) return PortTarget.Go(byPortUrl(byPort, port) + path.removePrefix("/"))
    val preview = previews.firstOrNull { it.port == port } ?: return PortTarget.NoRoute(port)
    return PortTarget.Go(proxyUrl(preview).trimEnd('/') + path)
}

/** The by-port preview [template] (`…{port}…/`) for [port]. */
fun byPortUrl(template: String, port: Int): String = template.replace("{port}", port.toString())

fun displayAddress(url: String, previews: List<ProxyDto>, byPort: String? = null): DisplayAddress {
    byPort?.let { byPortAddress(url, it) }?.let { return it }
    for (p in previews) {
        val base = proxyUrl(p).trimEnd('/')
        if (url == base || url.startsWith("$base/")) return DisplayAddress.Preview(p.port, url.removePrefix(base).ifEmpty { "/" })
    }
    val parsed = runCatching { Url(url) }.getOrNull() ?: return DisplayAddress.Web(url, "/")
    val host = parsed.host.lowercase()
    return if (host in LOCAL_HOSTS) DisplayAddress.Local(parsed.port, pathAndQuery(parsed)) else DisplayAddress.Web(host, pathAndQuery(parsed))
}

/** [url] as a by-port preview's port and path, or null when it isn't one ([template]'s `{port}` is digits there). */
private fun byPortAddress(url: String, template: String): DisplayAddress.Preview? {
    val before = template.substringBefore("{port}")
    val after = template.substringAfter("{port}")
    if (!url.startsWith(before)) return null
    val digits = url.removePrefix(before).takeWhile { it.isDigit() }
    val port = digits.takeIf { it.length in 1..5 }?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val rest = url.removePrefix(before + digits)
    if (!rest.startsWith(after)) return null
    return DisplayAddress.Preview(port, "/" + rest.removePrefix(after))
}

private fun pathAndQuery(url: Url): String =
    url.encodedPath.ifEmpty { "/" } + if (url.encodedQuery.isNotEmpty()) "?${url.encodedQuery}" else ""

/** What a Browser tab does with an address: load it, or say why it can't. */
sealed interface AddressAction {
    data class Load(val url: String) : AddressAction

    data class Say(val message: String) : AddressAction
}

/**
 * [text] typed in the address bar, or a page the tab was asked to open, as what the tab does. A port on the host is the
 * host's own address when this app runs there ([onHost]), else its by-port preview ([byPort]), else a named preview;
 * text that isn't an address says so. Every page a tab loads goes through here, so a `localhost` link from chat opens the
 * port's preview on a phone.
 */
fun addressAction(text: String, previews: List<ProxyDto>, onHost: Boolean, byPort: String? = null): AddressAction =
    when (val input = parseAddress(text)) {
        is AddressInput.Go -> AddressAction.Load(input.url)
        is AddressInput.Port -> when (val target = resolvePort(input.port, input.path, previews, onHost, byPort)) {
            is PortTarget.Go -> AddressAction.Load(target.url)
            is PortTarget.NoRoute -> AddressAction.Say("The host's port ${target.port} can't be opened from here.")
        }
        is AddressInput.NotAnAddress -> AddressAction.Say("“${input.text}” isn't an address. Type a site, or a port such as 5173.")
    }
