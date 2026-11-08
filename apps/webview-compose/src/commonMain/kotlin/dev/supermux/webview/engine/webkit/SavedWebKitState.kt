package dev.supermux.webview.engine.webkit

/**
 * What a WebKit engine's `saveState` returns: WebKit's opaque session state (`interactionState`), wrapped with the key
 * of the content it is the state of, so `restoreState` restores only that content's state (the engine contract).
 *
 * Layout: the magic `WKS1`, the content key's UTF-8 length as 4 bytes big-endian, the key, then WebKit's bytes.
 */
internal object SavedWebKitState {
    private val MAGIC = "WKS1".encodeToByteArray()

    fun encode(contentKey: String, state: ByteArray): ByteArray {
        val key = contentKey.encodeToByteArray()
        val out = ByteArray(MAGIC.size + 4 + key.size + state.size)
        MAGIC.copyInto(out)
        var at = MAGIC.size
        for (shift in intArrayOf(24, 16, 8, 0)) out[at++] = (key.size ushr shift).toByte()
        key.copyInto(out, at)
        state.copyInto(out, at + key.size)
        return out
    }

    /** WebKit's state in [bytes], if [bytes] is a saved state of [contentKey]'s; null otherwise. */
    fun decode(bytes: ByteArray, contentKey: String): ByteArray? {
        if (bytes.size < MAGIC.size + 4) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        var length = 0
        for (i in 0 until 4) length = (length shl 8) or (bytes[MAGIC.size + i].toInt() and 0xff)
        val keyStart = MAGIC.size + 4
        if (length < 0 || length > bytes.size - keyStart) return null
        val key = bytes.decodeToString(keyStart, keyStart + length)
        if (key != contentKey) return null
        val state = bytes.copyOfRange(keyStart + length, bytes.size)
        return state.takeIf { it.isNotEmpty() }
    }
}
