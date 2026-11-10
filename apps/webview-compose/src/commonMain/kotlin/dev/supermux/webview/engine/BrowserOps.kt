package dev.supermux.webview.engine

import dev.supermux.webview.FindResult
import dev.supermux.webview.WebCookie
import kotlin.math.roundToInt

/*
 * The pure rules of the browser operations (WebEngine.find, setZoom, setCookie) for an engine with no more than a
 * counting listener, a text-zoom setting and a header-string cookie store: Android's WebView. Tested on the JVM.
 */

/** The page-zoom range the navigator accepts, in percent. */
private const val MIN_ZOOM_PERCENT = 25f
private const val MAX_ZOOM_PERCENT = 500f

/** A find answer from a counting listener's values: [activeMatchOrdinal] is 0-based, `current` 1-based. */
internal fun findResultOf(activeMatchOrdinal: Int, numberOfMatches: Int): FindResult =
    if (numberOfMatches <= 0) {
        FindResult(found = false, count = 0, current = null)
    } else {
        FindResult(found = true, count = numberOfMatches, current = activeMatchOrdinal.coerceIn(0, numberOfMatches - 1) + 1)
    }

/** [factor] (1.0 = 100 %) as a whole percent within 25..500; NaN is 100. */
internal fun textZoomPercent(factor: Float): Int =
    if (factor.isNaN()) 100 else (factor * 100f).coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT).roundToInt()

private fun isToken(s: String): Boolean =
    s.isNotEmpty() && s.none { it <= ' ' || it == ';' || it == '=' || it == ',' || it == '"' || it == '\\' || it.code == 127 }

private fun isPlain(s: String): Boolean = s.none { it < ' ' || it == ';' || it.code == 127 }

/**
 * [c] as the `Set-Cookie` string `CookieManager.setCookie(url, …)` takes: `name=value; Path=…; Domain=…; Secure;
 * HttpOnly; SameSite=…; Max-Age=…`. Null for a cookie whose parts could end the pair or add an attribute (a `;`, a
 * control character, a name that is no token, an unknown SameSite): it is refused, never sent mangled. A Max-Age of
 * zero or less expires the cookie at once.
 */
internal fun cookieHeader(c: WebCookie): String? {
    if (!isToken(c.name) || !isPlain(c.value) || !isPlain(c.path)) return null
    val domain = c.domain
    if (domain != null && (domain.isEmpty() || !isPlain(domain))) return null
    val sameSite = when (c.sameSite.lowercase()) {
        "lax" -> "Lax"
        "strict" -> "Strict"
        "none" -> "None"
        else -> return null
    }
    return buildString {
        append(c.name).append('=').append(c.value)
        append("; Path=").append(c.path)
        if (domain != null) append("; Domain=").append(domain)
        if (c.secure) append("; Secure")
        if (c.httpOnly) append("; HttpOnly")
        append("; SameSite=").append(sameSite)
        c.maxAgeSeconds?.let { append("; Max-Age=").append(maxOf(it, 0L)) }
    }
}
