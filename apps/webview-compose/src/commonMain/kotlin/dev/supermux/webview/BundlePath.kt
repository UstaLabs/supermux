package dev.supermux.webview

/**
 * The path to ask a [WebResourceProvider] for, from the path of a request on a bundle's origin (the URL's path, with or
 * without its query and fragment), or null when the request must be refused, which engines serve as a 404. The result
 * meets [WebResourceProvider.load]'s contract: relative (no leading slash), percent-decoded once, and free of query,
 * fragment, `.` and `..` segments, backslashes, `%`, NUL and other control characters. The bundle's root is `""`.
 * - Empty and `.` segments are dropped.
 * - A `..` segment, raw or percent-encoded (`%2e%2e`), is refused rather than resolved: a URL parser has already resolved
 *   the dot segments it should, so one left here is an attempt to leave the bundle.
 * - So is a backslash, raw or encoded (`..%5c`, which some servers treat as a separator); a `%` left after decoding
 *   (`%252e`, which a provider that decodes again would turn into a dot); NUL (`%00`, which ends a C string early) and
 *   every other control character; an escape that isn't two hex digits; bytes that aren't UTF-8; and raw non-ASCII,
 *   which a URL parser never leaves in a path.
 */
internal fun normalizeBundlePath(rawPath: String): String? {
    val path = rawPath.substringBefore('#').substringBefore('?')
    if (path.any { it == '\\' || it.code >= 0x80 }) return null
    val decoded = percentDecode(path) ?: return null
    if (decoded.any { it == '\\' || it == '%' || it.code < 0x20 || it.code == 0x7f }) return null
    val segments = ArrayList<String>()
    for (segment in decoded.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> return null
            else -> segments += segment
        }
    }
    return segments.joinToString("/")
}

/** [text] (ASCII) with its `%XX` escapes decoded as UTF-8, once; null if an escape is malformed or the bytes aren't UTF-8. */
private fun percentDecode(text: String): String? {
    if ('%' !in text) return text
    val bytes = ByteArray(text.length)
    var size = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            if (i + 2 >= text.length) return null
            val high = hexValue(text[i + 1])
            val low = hexValue(text[i + 2])
            if (high < 0 || low < 0) return null
            bytes[size++] = (high * 16 + low).toByte()
            i += 3
        } else {
            bytes[size++] = c.code.toByte()
            i++
        }
    }
    return try {
        bytes.decodeToString(0, size, throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        null
    }
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}
