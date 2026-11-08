package dev.supermux.net

/**
 * The only links the app hands to a browser or the OS: web pages, mail, phone calls and texts. Links come from
 * messages an agent wrote, so a scheme that could run code or reach a file (`javascript:`, `data:`, `blob:`, `file:`,
 * a UNC share) or come back into the app (`supermux://`, whose links arrive from the OS) is never opened.
 */
private val OPENABLE_SCHEMES = setOf("http", "https", "mailto", "tel", "sms")

fun isOpenableLink(url: String): Boolean = linkScheme(url) in OPENABLE_SCHEMES

/**
 * [url]'s scheme, lowercased, read as a browser reads it: the C0 controls and spaces around it trimmed, and nothing
 * else forgiven, so `java\tscript:` has no scheme. Null for a link without one (`#top`, `/s/abc`, `//host/x`).
 */
fun linkScheme(url: String): String? {
    val trimmed = url.trim { it <= ' ' }
    val colon = trimmed.indexOf(':')
    if (colon <= 0) return null
    val scheme = trimmed.substring(0, colon)
    val letter = { c: Char -> c in 'a'..'z' || c in 'A'..'Z' }
    if (!letter(scheme[0]) || !scheme.all { letter(it) || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return null
    return scheme.lowercase()
}
