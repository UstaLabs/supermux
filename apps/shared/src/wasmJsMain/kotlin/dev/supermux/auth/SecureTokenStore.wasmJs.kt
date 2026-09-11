package dev.supermux.auth

import kotlinx.browser.window

/**
 * The browser holds NO bearer: the credential is the HttpOnly `cmux_token` cookie the broker set on
 * `/pair?t=` or a successful `POST /pair/claim`, and the browser attaches it to every same-origin
 * request and WebSocket upgrade itself. So [load] is always null and the broker's
 * `cookieToken(req) || bearerToken(req)` picks the cookie. The base URL is the page origin.
 *
 * CONSEQUENCE for plan 2: any pairing gate that reads "token != null" as "paired" will see the
 * browser as permanently unpaired. The web host must decide pairedness with `GET /me` instead —
 * the cookie makes that probe authoritative without ever exposing the token to script.
 */
actual class SecureTokenStore actual constructor() {
    actual fun save(token: String) = Unit
    actual fun load(): String? = null

    /**
     * No-op: an HttpOnly cookie is invisible and unclearable from script by design. Only the broker
     * can expire it, via `POST /logout`, which the web host's CookieSession (plan 2) calls on sign
     * out; this store has nothing of its own to drop.
     */
    actual fun clear() = Unit
    actual fun saveBaseUrl(url: String) = Unit
    actual fun loadBaseUrl(): String? = window.location.origin
}
