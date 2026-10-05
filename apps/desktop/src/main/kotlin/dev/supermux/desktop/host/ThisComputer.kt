package dev.supermux.desktop.host

import dev.supermux.host.PairedHost
import java.net.InetAddress
import java.net.URI

/**
 * True iff [url] is an http(s) URL whose host is THIS computer's loopback: `localhost`, an IPv4
 * address in 127.0.0.0/8 (all four octets numeric, so `127.example.com` is not), or `::1` in any
 * spelling. An unparseable URL, or one with no host, is not loopback.
 */
fun isLoopbackUrl(url: String?): Boolean {
    val host = urlHost(url) ?: return false
    if (host == "localhost") return true
    if (IPV4.matches(host)) {
        val octets = host.split('.').map { it.toInt() }
        return octets.all { it in 0..255 } && octets[0] == 127
    }
    // An IPv6 literal (only ever parsed, never resolved: it contains a colon).
    if (':' in host && IPV6_CHARS.matches(host)) {
        return runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }
    return false
}

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
private val IPV6_CHARS = Regex("""[0-9a-f:.]+""")

/** [url]'s host, lowercased, without IPv6 brackets; null when it doesn't parse or has none. */
internal fun urlHost(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val host = runCatching { URI(url.trim()).host }.getOrNull() ?: return null
    return host.removePrefix("[").removeSuffix("]").lowercase().takeIf { it.isNotEmpty() }
}

/** [url]'s port, the scheme's default when it names none; null when it doesn't parse. */
internal fun urlPort(url: String?): Int? {
    if (url.isNullOrBlank()) return null
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.host == null) return null
    return when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> null
    }
}

/**
 * "This computer"'s paired record — the ONE lookup every caller uses (the wizard, the record sync,
 * the tray, Settings ▸ Hosting, the git install, keep-awake, pairing):
 *  1. the record with the running broker's [hostId];
 *  2. else a record paired before hostIds existed: no hostId, a loopback direct URL, and exactly
 *     the supervisor's [port]. A loopback record on another port may be another broker on this
 *     computer, and a loopback record with another hostId certainly is.
 */
fun thisComputerRecord(hosts: List<PairedHost>, hostId: String?, port: Int?): PairedHost? {
    if (!hostId.isNullOrBlank()) hosts.firstOrNull { it.hostId == hostId }?.let { return it }
    if (port == null) return null
    return hosts.firstOrNull { it.hostId.isNullOrBlank() && isLoopbackUrl(it.directUrl) && urlPort(it.directUrl) == port }
}
