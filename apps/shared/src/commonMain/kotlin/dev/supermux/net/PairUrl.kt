package dev.supermux.net

import io.ktor.http.Url

/**
 * Parsed pairing input: a broker base URL (ws/wss form expected by BrokerClient/
 * BrokerApi — BrokerApi re-maps ws→http internally) plus the device bearer token.
 *
 * Mirrors the iOS [PairToken.parse] + deepLinkPair semantics so both clients accept
 * the exact same pairing-URL / deep-link / bare-token forms. Lives in :shared so the
 * parser is a single source of truth, unit-testable on JVM, and reusable by a future
 * desktop target.
 */
data class PairUrl(val baseUrl: String, val token: String) {
    companion object {
        /**
         * Accepts:
         *  - `https://host[:port]/pair?t=TOKEN` → base = `wss://host[:port]` (http→ws normalized), token = `t`.
         *  - `supermux://pair?t=TOKEN&base=https%3A%2F%2Fhost` → token = `t`, base = `base` (or [fallbackBaseUrl]).
         *  - bare TOKEN → `PairUrl([fallbackBaseUrl], TOKEN)` only when [fallbackBaseUrl] != null.
         * Returns null when no token can be read.
         */
        fun parse(input: String, fallbackBaseUrl: String? = null): PairUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null

            val scheme = schemeOf(trimmed)
            if (scheme != null) {
                val url = runCatching { Url(trimmed) }.getOrNull()
                if (url != null) {
                    if (scheme.startsWith("http")) {
                        val token = queryToken(url) ?: return null
                        val host = url.host
                        if (host.isBlank()) return null
                        // ktor fills url.port with the protocol default when absent; only
                        // append a port when the input actually carried one.
                        val portPart = if (hasExplicitPort(trimmed)) ":${url.port}" else ""
                        return PairUrl(normalize("$scheme://$host$portPart"), token)
                    }
                    if (scheme == "supermux") {
                        val token = queryToken(url) ?: return null
                        val base = url.parameters["base"]?.takeIf { it.isNotBlank() }
                            ?: fallbackBaseUrl
                            ?: return null
                        return PairUrl(normalize(base), token)
                    }
                }
            }

            // Bare token — only usable once a broker URL is already known.
            return fallbackBaseUrl?.let { PairUrl(normalize(it), trimmed) }
        }

        /** Store the ws-form: http→ws, https→wss (BrokerApi re-maps ws→http internally). */
        private fun normalize(base: String): String = base.trim().trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")

        private fun queryToken(url: Url): String? =
            url.parameters["t"]?.takeIf { it.isNotEmpty() }

        /** Scheme prefix of an absolute URL (e.g. "https", "supermux"), or null if relative. */
        private fun schemeOf(s: String): String? {
            val idx = s.indexOf("://")
            if (idx <= 0) return null
            val candidate = s.substring(0, idx)
            // RFC-3986 scheme: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
            if (!candidate[0].isLetter()) return null
            if (!candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
            return candidate.lowercase()
        }

        /** Did the authority carry an explicit `:port`? (ktor would otherwise report a default.) */
        private fun hasExplicitPort(s: String): Boolean {
            val afterScheme = s.substringAfter("://", "")
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            val hostPort = authority.substringAfterLast('@') // strip any userinfo
            return hostPort.substringAfter(':', "").toIntOrNull() != null
        }
    }
}
