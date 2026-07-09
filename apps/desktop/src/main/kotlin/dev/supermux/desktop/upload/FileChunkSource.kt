package dev.supermux.desktop.upload

import dev.supermux.net.ChunkSource
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A [ChunkSource] backed by a `java.io.File`. Each read opens a fresh
 * `RandomAccessFile`/`FileChannel` and does a position-absolute read, mirroring
 * Android's `ContentResolverChunkSource` (fresh-FD-per-read, NOT a shared mutable
 * position) so concurrent reads from the resumable-upload retry logic are safe.
 * Synchronous by contract — see [ChunkSource].
 *
 * KNOWN LIMITATION: [size] reflects the file's CURRENT length at read time, so a file deleted or
 * truncated between staging (launcher) and upload (post-spawn) streams as fewer/zero bytes rather
 * than failing — the attachment lands short/empty. Acceptable: launcher staging→upload is a short
 * window and a best-effort attachment; not worth snapshotting bytes at stage time (bounded RAM).
 */
class FileChunkSource(private val file: File) : ChunkSource {
    override val size: Long get() = file.length()

    override fun read(offset: Long, len: Int): ByteArray {
        val total = size
        if (offset >= total || len <= 0) return ByteArray(0)
        val capped = minOf(len.toLong(), total - offset).toInt()
        RandomAccessFile(file, "r").use { raf ->
            val ch = raf.channel
            val buf = ByteBuffer.allocate(capped)
            var pos = offset
            while (buf.hasRemaining()) {
                val n = ch.read(buf, pos)
                if (n < 0) break
                pos += n
            }
            return buf.array().copyOf(buf.position())
        }
    }
}
