package dev.supermux.android.chat

import android.content.ContentResolver
import android.net.Uri
import dev.supermux.net.ChunkSource
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * A [ChunkSource] backed by a content:// Uri. Each read opens a fresh seekable
 * file descriptor and does a position-absolute read, so a large video never loads
 * whole into the app heap (the pre-resumable path read the entire file via
 * readBytes()). Synchronous read — safe to call off the IO dispatcher inside
 * uploadResumable without capturing a coroutine across a suspension point.
 */
class ContentResolverChunkSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val size: Long,
) : ChunkSource {
    override fun read(offset: Long, len: Int): ByteArray {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: return ByteArray(0)
        // pfd.use owns the fd; we don't close the channel/stream ourselves to avoid
        // a double-close (FileInputStream.close would also close the same fd).
        pfd.use {
            val ch = FileInputStream(it.fileDescriptor).channel
            val buf = ByteBuffer.allocate(len)
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
