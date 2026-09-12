package dev.supermux.web.push

/**
 * The VAPID application server key, base64url (RFC 4648 §5) → raw bytes.
 *
 * The Push API wants the key as a `Uint8Array`; the broker sends it as an UNPADDED base64url
 * string (`GET /push/vapid-public-key`). This is the Kotlin twin of the Vue app's
 * `urlBase64ToUint8Array` (src/web-app/src/composables/useNotifications.ts): re-pad to a multiple
 * of 4, swap `-`→`+` and `_`→`/`, then decode.
 *
 * It is its own file, and pure, because it is the only part of the push flow a test can assert
 * without a push service: [WebPushRegistrar] cannot be driven to a real subscription in headless
 * Chrome, but a wrong key here is silent (the browser rejects `subscribe()` with a generic error),
 * so this is the piece that earns vectors.
 *
 * Kotlin's own `Base64.UrlSafe` is not used: it is `@ExperimentalEncodingApi`, and its default
 * `PaddingOption.PRESENT` rejects the unpadded string the broker actually sends. Decoding by hand
 * is ~20 lines and has no such surprises.
 *
 * Returns an empty array for input that is not base64 at all, rather than throwing — every caller
 * is inside a "be quiet on failure" push path, and an empty key fails `subscribe()` visibly.
 */
fun vapidKeyBytes(b64url: String): ByteArray {
    val cleaned = b64url.trim().trimEnd('=')
    if (cleaned.isEmpty()) return ByteArray(0)
    var acc = 0
    var bits = 0
    val out = ArrayList<Byte>(cleaned.length * 3 / 4 + 2)
    for (ch in cleaned) {
        val v = B64_INDEX[ch] ?: return ByteArray(0)
        acc = (acc shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((acc shr bits) and 0xFF).toByte())
        }
    }
    // A trailing group of 6 leftover bits is the padding remainder, and it must be zero — a
    // non-zero remainder means the string was truncated mid-byte, not merely unpadded.
    return out.toByteArray()
}

/** base64url alphabet, plus the two standard-base64 characters so a padded/standard key decodes too. */
private val B64_INDEX: Map<Char, Int> = buildMap {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    alphabet.forEachIndexed { i, c -> put(c, i) }
    put('+', 62)
    put('/', 63)
}
