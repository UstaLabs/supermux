package dev.supermux.net

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header

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

/**
 * Attach `Authorization: Bearer …` — unless [token] is blank, which is the browser: there the
 * credential is the HttpOnly `cmux_token` cookie the engine attaches to every same-origin request
 * and WebSocket upgrade itself, and the broker resolves `cookieToken(req) || bearerToken(req)`.
 * A literal `Bearer ` would not match the broker's regex anyway, so sending nothing is the honest
 * shape. Shared by [BrokerApi] and the four WebSocket clients.
 */
internal fun HttpRequestBuilder.bearer(token: String) {
    if (token.isNotBlank()) header("Authorization", "Bearer $token")
}
