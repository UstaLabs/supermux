package dev.supermux.ios

import dev.supermux.util.toByteArray
import dev.supermux.util.toNSData
import platform.Foundation.NSData

/**
 * `NSData` ⇄ `ByteArray` for the SWIFT side of the bridge.
 *
 * Swift sees a Kotlin `ByteArray` as `KotlinByteArray`, whose only element access is
 * `get(index:)`/`set(index:value:)` — one Objective-C message per byte. Converting a picked video
 * that way is not merely slow, it is quadratic-feeling enough to look like a hang: a 20 MB
 * attachment would be 20 million bridged calls.
 *
 * These two hop the conversion into Kotlin instead, where it is a single `memcpy` (see
 * `:shared`'s `NSDataBytes.kt`). Swift calls `IosBytesKt.bytesFrom(data:)` /
 * `IosBytesKt.dataFrom(bytes:)` and never touches an element.
 */
fun bytesFrom(data: NSData): ByteArray = data.toByteArray()

fun dataFrom(bytes: ByteArray): NSData = bytes.toNSData()
