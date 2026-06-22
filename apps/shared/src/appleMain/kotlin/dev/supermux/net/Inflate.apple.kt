package dev.supermux.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit
import platform.zlib.uByteVar
import platform.zlib.z_stream

/**
 * iOS streaming inflate backed by `platform.zlib` (zlib-wrapped, default
 * windowBits via `inflateInit`). Mirrors the JVM `Inflater` contract: [feed]
 * appends compressed bytes; [inflate] drains as much output as the fed input
 * currently allows.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ZlibInflater actual constructor() {
    private val strm = nativeHeap.alloc<z_stream>()
    private var closed = false
    // Compressed bytes not yet consumed by the inflater. zlib keeps a pointer
    // into `pending`, so we must keep it alive (pinned) across inflate() calls
    // and only drop fully-consumed input.
    private var pending = ByteArray(0)
    private val out = ByteArray(64 * 1024)

    init {
        strm.zalloc = null
        strm.zfree = null
        strm.opaque = null
        strm.next_in = null
        strm.avail_in = 0u
        val rc = inflateInit(strm.ptr)
        check(rc == Z_OK) { "inflateInit failed: $rc" }
    }

    actual fun feed(input: ByteArray) {
        if (closed || input.isEmpty()) return
        // Append any not-yet-consumed bytes ahead of the new input.
        pending = if (pending.isEmpty()) input else pending + input
    }

    actual fun inflate(): ByteArray {
        if (closed || pending.isEmpty()) return ByteArray(0)
        pending.usePinned { inPin ->
            out.usePinned { outPin ->
                strm.next_in = inPin.addressOf(0).reinterpret<uByteVar>()
                strm.avail_in = pending.size.convert()
                strm.next_out = outPin.addressOf(0).reinterpret<uByteVar>()
                strm.avail_out = out.size.convert()
                val rc = inflate(strm.ptr, Z_NO_FLUSH)
                if (rc != Z_OK && rc != Z_STREAM_END && rc != Z_BUF_ERROR) {
                    throw IllegalStateException("inflate failed: $rc")
                }
                val produced = out.size - strm.avail_out.toInt()
                val consumed = pending.size - strm.avail_in.toInt()
                // Drop the bytes zlib consumed; keep the remainder for next time.
                pending = if (consumed >= pending.size) ByteArray(0) else pending.copyOfRange(consumed, pending.size)
                return if (produced == 0) ByteArray(0) else out.copyOf(produced)
            }
        }
        return ByteArray(0)
    }

    actual fun close() {
        if (closed) return
        closed = true
        inflateEnd(strm.ptr)
        nativeHeap.free(strm.ptr.rawValue)
    }
}
