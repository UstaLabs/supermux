package dev.supermux.webview.engine.android

/**
 * Android engine state, as [dev.supermux.webview.engine.WebEngine.saveState] returns it: a header naming the content
 * the state is of, then WebView's own state (a marshalled Bundle). The header is what lets
 * [dev.supermux.webview.engine.WebEngine.restoreState] refuse another content's state. Kept in memory only (page
 * memory), never on disk: Parcel bytes are only valid on the platform version that wrote them.
 *
 * Layout: the 17 bytes of `WVC-ANDROID-STATE`, a version byte, the content key's UTF-8 length as 4 big-endian bytes,
 * the key, the payload.
 */
internal object SavedStateCodec {
    private val MAGIC = "WVC-ANDROID-STATE".encodeToByteArray()
    private const val VERSION: Byte = 1
    private val HEADER = MAGIC.size + 1 + 4

    fun encode(contentKey: String, payload: ByteArray): ByteArray {
        val key = contentKey.encodeToByteArray()
        val out = ByteArray(HEADER + key.size + payload.size)
        MAGIC.copyInto(out)
        out[MAGIC.size] = VERSION
        writeInt(out, MAGIC.size + 1, key.size)
        key.copyInto(out, HEADER)
        payload.copyInto(out, HEADER + key.size)
        return out
    }

    /** The payload of [state] if it is this format's and [contentKey]'s; null otherwise. */
    fun decode(state: ByteArray, contentKey: String): ByteArray? {
        if (state.size < HEADER) return null
        for (i in MAGIC.indices) if (state[i] != MAGIC[i]) return null
        if (state[MAGIC.size] != VERSION) return null
        val keyLength = readInt(state, MAGIC.size + 1)
        if (keyLength < 0 || keyLength > state.size - HEADER) return null
        val key = contentKey.encodeToByteArray()
        if (keyLength != key.size) return null
        for (i in key.indices) if (state[HEADER + i] != key[i]) return null
        return state.copyOfRange(HEADER + keyLength, state.size)
    }

    private fun writeInt(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 24).toByte()
        out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte()
        out[at + 3] = value.toByte()
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff shl 24) or (bytes[at + 1].toInt() and 0xff shl 16) or
            (bytes[at + 2].toInt() and 0xff shl 8) or (bytes[at + 3].toInt() and 0xff)
}
