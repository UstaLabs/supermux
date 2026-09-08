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
fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    if (isEmpty()) NSData() else NSData.create(bytes = pinned.addressOf(0), length = size.convert())
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    return ByteArray(len).also { out ->
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
