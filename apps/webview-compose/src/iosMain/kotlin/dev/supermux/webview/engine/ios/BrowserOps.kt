package dev.supermux.webview.engine.ios

import dev.supermux.webview.WebCookie
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieMaximumAge
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookieOriginURL
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSameSiteLax
import platform.Foundation.NSHTTPCookieSameSitePolicy
import platform.Foundation.NSHTTPCookieSameSiteStrict
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL

/**
 * The properties of the `NSHTTPCookie` for [cookie], as the macOS engine builds them: host-only (the URL's host, by its
 * origin URL) when it names no domain; `Secure` and `HttpOnly` when it says so; `SameSite` `Lax` or `Strict` (`None` is
 * the absence of a policy); a maximum age only when it has one, else a session cookie.
 */
internal fun cookieProperties(cookie: WebCookie): Map<Any?, Any?> {
    val properties = mutableMapOf<Any?, Any?>()
    properties[NSHTTPCookieName] = cookie.name
    properties[NSHTTPCookieValue] = cookie.value
    properties[NSHTTPCookiePath] = cookie.path
    val domain = cookie.domain
    if (!domain.isNullOrEmpty()) {
        properties[NSHTTPCookieDomain] = domain
    } else {
        NSURL.URLWithString(cookie.url)?.let { properties[NSHTTPCookieOriginURL] = it }
    }
    if (cookie.secure) properties[NSHTTPCookieSecure] = "TRUE"
    if (cookie.httpOnly) properties["HttpOnly"] = "TRUE"
    when (cookie.sameSite) {
        "Strict" -> properties[NSHTTPCookieSameSitePolicy] = NSHTTPCookieSameSiteStrict
        "Lax" -> properties[NSHTTPCookieSameSitePolicy] = NSHTTPCookieSameSiteLax
    }
    cookie.maxAgeSeconds?.takeIf { it >= 0 }?.let { properties[NSHTTPCookieMaximumAge] = it.toString() }
    return properties
}

/** The `NSHTTPCookie` for [cookie], or null when Foundation refuses its properties (no host, say). */
internal fun httpCookieOf(cookie: WebCookie): NSHTTPCookie? = NSHTTPCookie.cookieWithProperties(cookieProperties(cookie))
