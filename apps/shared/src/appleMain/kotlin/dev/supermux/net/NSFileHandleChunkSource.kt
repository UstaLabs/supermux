package dev.supermux.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.pread
import platform.posix.stat

/**
 * A [ChunkSource] over a local file path (iOS/macOS) using POSIX positioned reads (`pread`),
 * so a large video never loads whole into RAM — the fix for the old Data → base64 → ByteArray
 * ~2.3× blow-up. Swift constructs it with the picked file's URL path. Synchronous read (no
 * coroutine capture across a SKIE suspension point — the K/N GC-pinning trap).
 */
@OptIn(ExperimentalForeignApi::class)
class NSFileHandleChunkSource(private val path: String) : ChunkSource {
    override val size: Long = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) == 0) st.st_size.toLong() else 0L
    }

    override fun read(offset: Long, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val fd = open(path, O_RDONLY)
        if (fd < 0) return ByteArray(0)
        try {
            val buf = ByteArray(len)
            val n = buf.usePinned { pinned ->
                pread(fd, pinned.addressOf(0), len.convert(), offset.convert()).toLong()
            }
            return if (n <= 0L) ByteArray(0) else buf.copyOf(n.toInt())
        } finally {
            close(fd)
        }
    }
}
