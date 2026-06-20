package dev.supermux.net

/**
 * Convert a broker base URL to the ws/wss scheme that Darwin's (iOS) WebSocket
 * requires. The broker base may be an http(s) URL; Android tolerates http(s) on a
 * WebSocket but Darwin does not. Idempotent for bases already using ws/wss. Only
 * the leading scheme is rewritten, so a path/query containing "http" is left intact.
 */
internal fun wsBaseUrl(baseUrl: String): String = when {
    baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
    baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
    else -> baseUrl
}
