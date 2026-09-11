package dev.supermux.auth

import kotlinx.browser.window

/**
 * The browser holds NO bearer: the credential is the HttpOnly `cmux_token` cookie the broker set on
 * `/pair?t=` or a successful `POST /pair/claim`, and the browser attaches it to every same-origin
 * request and WebSocket upgrade itself. So [load] is always null and the broker's
 * `cookieToken(req) || bearerToken(req)` picks the cookie. The base URL is the page origin.
 */
actual class SecureTokenStore actual constructor() {
    actual fun save(token: String) = Unit
    actual fun load(): String? = null
    actual fun clear() = Unit
    actual fun saveBaseUrl(url: String) = Unit
    actual fun loadBaseUrl(): String? = window.location.origin
}
