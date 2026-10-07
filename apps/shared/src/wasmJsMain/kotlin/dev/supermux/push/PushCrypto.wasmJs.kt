package dev.supermux.push

/**
 * Never called in the browser: web push is plaintext VAPID handled entirely by the service
 * worker (`sw.js`), so no sealed blob ever reaches Kotlin. Kept as a loud failure rather than a
 * silent empty string so a future caller finds out immediately.
 */
actual fun openSealedPush(blob: String, privateKeyPkcs8B64: String): String =
    throw UnsupportedOperationException("sealed push is native-only; the browser uses plaintext web push")
