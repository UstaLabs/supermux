package dev.supermux.util

/**
 * Transport-security policy for add-host URLs (spec §3.5). A stored device bearer must not travel
 * over an unencrypted path unless the user has knowingly accepted it. Encrypted transports (https /
 * wss) never need consent; plain http / ws is auto-allowed ONLY for loopback (the host is the same
 * machine, so nothing leaves it) — every other plain-HTTP target requires an explicit, labeled
 * opt-in in the UI.
 *
 * The spec also permits auto-allowing a route verified through a VPN / tailnet interface; that check
 * is platform-specific (it needs the OS routing/interface tables) and is a deliberate TODO here —
 * until it exists, such routes fall through to the opt-in path, which is the safe default.
 */
object TransportPolicy {
    private val LOOPBACK_V4 = Regex("^127(?:\\.\\d{1,3}){3}$")

    /**
     * True when [url] may be added WITHOUT the "unencrypted connection" opt-in:
     *  - `https://` / `wss://` — encrypted, always fine.
     *  - `http://` / `ws://` to a loopback host (`localhost`, `127.0.0.0/8`, `::1`) — never leaves
     *    the device.
     *
     * False for any other plain-HTTP/WS target (LAN/Internet), and for an unrecognized/scheme-less
     * URL (conservative: require explicit consent rather than silently trust it).
     *
     * TODO(spec §3.5): also return true for a route verified through a VPN/tailnet interface — a
     * platform-specific check not yet wired here.
     */
    fun isPlainHttpAllowedWithoutOptIn(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return when (scheme) {
            "https", "wss" -> true
            "http", "ws" -> isLoopbackHost(hostOf(url))
            else -> false
        }
    }

    /** The host of an `scheme://` URL: strips userinfo, path/query/fragment, port, and IPv6 brackets. */
    private fun hostOf(url: String): String {
        val authority = url.substringAfter("://", "")
            .substringBefore("/").substringBefore("?").substringBefore("#")
            .substringAfter("@")
        return if (authority.startsWith("[")) {
            authority.substringAfter("[").substringBefore("]") // IPv6 literal, e.g. [::1]:9898
        } else {
            authority.substringBefore(":") // strip :port
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1" || LOOPBACK_V4.matches(h)
    }
}
