package dev.supermux.net

/**
 * A rolling byte buffer for reassembling a length-prefixed binary protocol off a
 * frame transport whose chunk boundaries don't align with message boundaries
 * (Ktor `Frame.Binary`). [append] feeds raw chunks; [take] pulls exactly N bytes
 * if available. Pure and synchronous so the "WS boundaries ≠ RFB boundaries"
 * reassembly is unit-testable without a live socket.
 */
class RollingBuffer {
    private var buf = ByteArray(0)
    private var pos = 0

    /** Bytes currently available to [take]. */
    val available: Int get() = buf.size - pos

    /** Append a chunk of freshly received bytes. */
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (pos == buf.size) {
            // fully drained — adopt the new chunk directly
            buf = bytes
            pos = 0
            return
        }
        if (pos > 0) {
            buf = buf.copyOfRange(pos, buf.size)
            pos = 0
        }
        val merged = ByteArray(buf.size + bytes.size)
        buf.copyInto(merged, 0)
        bytes.copyInto(merged, buf.size)
        buf = merged
    }

    /** Take exactly [n] bytes, or return null if fewer than [n] are available. */
    fun take(n: Int): ByteArray? {
        require(n >= 0)
        if (n == 0) return ByteArray(0)
        if (available < n) return null
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}
