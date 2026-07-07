package dev.supermux.net

/**
 * A byte source for resumable upload. `read` is SYNCHRONOUS on purpose: it must
 * not capture a Swift closure across a coroutine suspension point (the Kotlin/Native
 * GC-pinning trap). Platform file-backed implementations (iOS NSFileHandle,
 * Android ContentResolver) live in their own source sets; [ByteArrayChunkSource]
 * covers in-memory bodies and all common tests.
 */
interface ChunkSource {
    /** Total byte length of the source. */
    val size: Long

    /** Bytes in range [offset, min(offset+len, size)). May return fewer than [len]
     *  bytes only at end-of-source; never more. */
    fun read(offset: Long, len: Int): ByteArray
}

/** In-memory [ChunkSource] over a [ByteArray]. Used for small/pasted bodies and tests. */
class ByteArrayChunkSource(private val bytes: ByteArray) : ChunkSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, len: Int): ByteArray {
        val start = offset.toInt()
        val end = minOf(start + len, bytes.size)
        if (start >= end) return ByteArray(0)
        return bytes.copyOfRange(start, end)
    }
}
