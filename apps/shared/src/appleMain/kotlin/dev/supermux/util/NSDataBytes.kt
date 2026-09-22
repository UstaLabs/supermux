package dev.supermux.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * `NSData` ⇄ `ByteArray`, in one place.
 *
 * Every Apple seam that moves bytes across the boundary needs this pair — the Keychain
 * (`kSecValueData`), the snapshot cache file, a pasted PNG, a picked attachment — and each of them
 * would otherwise carry its own `usePinned`/`memcpy` copy of a conversion that is easy to get
 * subtly wrong (an empty array must not become a pointer to nothing, and `memcpy` with a zero
 * length on a null pointer is undefined).
 *
 * Both copy. There is no zero-copy option worth having here: `NSData` may be discontiguous or
 * memory-mapped, and a `ByteArray` handed to Kotlin code must stay valid after the `NSData` is
 * released.
 */
@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData {
    // The empty check comes BEFORE `usePinned`: pinning an empty array and asking for
    // `addressOf(0)` is an out-of-bounds index, so the old order would have thrown on the one
    // input most likely to reach here by accident (a zero-byte attachment, an empty token).
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.convert()) }
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    return ByteArray(len).also { out ->
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

/**
 * [toNSData] as a TOP-LEVEL function, because Swift cannot see the extension.
 *
 * Kotlin/Native does not export extension functions whose receiver is a Kotlin builtin, so
 * `ByteArray.toNSData()` is absent from the generated header while `NSData.toByteArray()` (receiver
 * `NSData`, an ObjC type) is present. `:ios`'s `dataFrom` covers the Compose bridge, but it lives in
 * SupermuxKit and the watch app links `Shared` alone — so the ONE conversion both need
 * (the terminal's pty bytes) has to be here.
 *
 * Swift calls `NSDataBytesKt.nsDataOf(bytes:)` and then `[UInt8](data)`: two bulk copies instead of
 * one Objective-C message per byte. On a `cat` of a large file that is the difference between a
 * memcpy and several million bridged calls per second.
 */
fun nsDataOf(bytes: ByteArray): NSData = bytes.toNSData()
