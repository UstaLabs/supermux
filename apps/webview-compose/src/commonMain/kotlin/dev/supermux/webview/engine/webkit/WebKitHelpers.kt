package dev.supermux.webview.engine.webkit

import dev.supermux.webview.WebError
import dev.supermux.webview.runtime.MAX_SNAPSHOT_SCALE
import dev.supermux.webview.sha256

/**
 * The UUID of a named profile's data store (`WKWebsiteDataStore(forIdentifier:)`): the same for the same [id] at every
 * launch, on iOS and macOS alike (a name-based UUID, version 5 layout, from SHA-256), so WebKit finds the store again.
 * Lowercase hex; Foundation reads either case.
 */
internal fun profileUuidString(id: String): String {
    val bytes = sha256("dev.supermux.webview.profile:$id".encodeToByteArray()).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

/** WebKit's failures as the library reports them (spec §8). */
internal object WebKitErrors {
    const val URL_DOMAIN: String = "NSURLErrorDomain"
    const val WEBKIT_DOMAIN: String = "WebKitErrorDomain"

    /** `NSURLErrorCancelled`: the navigation was replaced by another. */
    const val CANCELLED: Long = -999

    /** `WebKitErrorCannotShowURL`. */
    const val CANNOT_SHOW_URL: Long = 101

    /** `WebKitErrorFrameLoadInterruptedByPolicyChange`: the engine itself turned the navigation into a download. */
    const val FRAME_LOAD_INTERRUPTED_BY_POLICY_CHANGE: Long = 102

    /** A navigation that ended without failing: replaced by another, or turned into a download by the engine. */
    fun isIgnorable(domain: String, code: Long): Boolean =
        domain == URL_DOMAIN && code == CANCELLED || domain == WEBKIT_DOMAIN && code == FRAME_LOAD_INTERRUPTED_BY_POLICY_CHANGE

    /** Certificate failures (`NSURLErrorDomain` −1206…−1200) are always refused; a URL WebKit can't show; else the network. */
    fun toWebError(domain: String, code: Long, description: String, url: String?): WebError = when {
        domain == URL_DOMAIN && code in -1206L..-1200L -> WebError.Certificate(url, description)
        domain == WEBKIT_DOMAIN && code == CANNOT_SHOW_URL -> WebError.UnsupportedOnPlatform(url)
        else -> WebError.Network(url, description)
    }
}

/** `WKNavigationType`'s raw value as a [NavigationKind]: link 0, form 1, back-forward 2, reload 3, resubmit 4, other −1. */
internal fun navigationKindOf(type: Long): NavigationKind = when (type) {
    0L -> NavigationKind.Link
    1L -> NavigationKind.FormSubmit
    2L -> NavigationKind.BackForward
    3L -> NavigationKind.Reload
    4L -> NavigationKind.FormResubmit
    else -> NavigationKind.Other
}

/**
 * The `snapshotWidth` (in points) that makes `takeSnapshot` of a page [widthPoints] wide give an image at the screen's
 * own scale, [screenScale], up to [MAX_SNAPSHOT_SCALE] pixels a point, so a cover looks like the page itself. WebKit
 * renders the snapshot at the screen's scale: the page's own width gives every pixel of a screen up to 3×, and a denser
 * screen gets a narrower width. The runtime keeps the snapshot as it is and stores it encoded, off the main thread.
 */
internal fun snapshotWidthPoints(widthPoints: Double, screenScale: Double): Double {
    val most = MAX_SNAPSHOT_SCALE.toDouble()
    return if (screenScale <= most) widthPoints else widthPoints * most / screenScale
}

/**
 * [path] escaped for a URL's path as Foundation's `URLPathAllowedCharacterSet` escapes it: letters, digits and
 * `-._~!$&'()*+,;=:@/` stay; every other UTF-8 byte becomes `%XX`.
 */
internal fun escapeUrlPath(path: String): String {
    val out = StringBuilder()
    for (byte in path.encodeToByteArray()) {
        val b = byte.toInt() and 0xff
        val c = b.toChar()
        if (b < 0x80 && (c.isLetterOrDigit() || c in PATH_ALLOWED)) {
            out.append(c)
        } else {
            out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0f])
        }
    }
    return out.toString()
}

private const val PATH_ALLOWED = "-._~!$&'()*+,;=:@/"
private const val HEX = "0123456789ABCDEF"
