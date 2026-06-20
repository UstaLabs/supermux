package dev.supermux.net

import java.util.zip.Inflater

/** JVM streaming inflate backed by [java.util.zip.Inflater] (zlib-wrapped, nowrap=false). */
actual class ZlibInflater actual constructor() {
    private val inflater = Inflater() // nowrap=false → expects the 2-byte zlib header (RFB ZRLE uses it)
    private val out = ByteArray(64 * 1024)
    private var closed = false

    actual fun feed(input: ByteArray) {
        if (closed || input.isEmpty()) return
        inflater.setInput(input)
    }

    actual fun inflate(): ByteArray {
        if (closed) return ByteArray(0)
        // Produce only what the currently-set input allows; needsInput() guards
        // against blocking when this chunk is exhausted.
        if (inflater.needsInput() || inflater.finished()) return ByteArray(0)
        val n = inflater.inflate(out)
        if (n == 0) return ByteArray(0)
        return out.copyOf(n)
    }

    actual fun close() {
        if (closed) return
        closed = true
        inflater.end()
    }
}
