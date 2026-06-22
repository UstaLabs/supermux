package dev.supermux.net

import java.util.zip.Inflater

/**
 * Android streaming inflate backed by [java.util.zip.Inflater] (identical to the
 * JVM actual; Android is a separate KMP source set so it needs its own actual).
 */
actual class ZlibInflater actual constructor() {
    private val inflater = Inflater() // nowrap=false → expects the 2-byte zlib header
    private val out = ByteArray(64 * 1024)
    private var closed = false

    actual fun feed(input: ByteArray) {
        if (closed || input.isEmpty()) return
        inflater.setInput(input)
    }

    actual fun inflate(): ByteArray {
        if (closed) return ByteArray(0)
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
