package dev.supermux.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
import platform.posix.memcpy

/**
 * A [ChunkSource] over a local file path (iOS/macOS), reading slices via NSFileHandle
 * so a large video never loads whole into RAM — the fix for the old
 * Data → base64 → ByteArray blow-up. Swift constructs it with the picked file's
 * URL path. Synchronous read (no coroutine capture across a SKIE suspension point).
 */
@OptIn(ExperimentalForeignApi::class)
class NSFileHandleChunkSource(private val path: String) : ChunkSource {
    override val size: Long =
        ((NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize)) as? NSNumber)
            ?.longLongValue ?: 0L

    override fun read(offset: Long, len: Int): ByteArray {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return ByteArray(0)
        try {
            handle.seekToFileOffset(offset.toULong())
            val data = handle.readDataOfLength(len.toULong())
            return data.toByteArray()
        } finally {
            handle.closeFile()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = this.length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return out
}
