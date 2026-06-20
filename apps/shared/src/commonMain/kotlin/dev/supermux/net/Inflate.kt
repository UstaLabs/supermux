package dev.supermux.net

/**
 * Streaming/incremental zlib inflate.
 *
 * ZRLE shares **one** zlib stream across every FramebufferUpdate for the life of
 * the connection, so the decoder needs to feed compressed bytes in and pull
 * decompressed bytes out incrementally — state must persist between calls. This
 * is a thin `expect/actual` over the platform zlib (JVM `java.util.zip.Inflater`,
 * Apple `platform.zlib`).
 *
 * Usage: [feed] appended compressed bytes, then call [inflate] repeatedly until
 * it returns an empty array (no more output available from the data fed so far).
 */
expect class ZlibInflater() {
    /** Append more compressed input to the stream. */
    fun feed(input: ByteArray)

    /**
     * Decompress as much as possible from the input fed so far, returning the
     * newly produced bytes (possibly empty if more input is needed). Call in a
     * loop until it returns an empty array to drain all currently-available
     * output.
     */
    fun inflate(): ByteArray

    /** Release native resources. Safe to call more than once. */
    fun close()
}
