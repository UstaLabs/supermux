package dev.supermux.webview.engine.ios

import dev.supermux.webview.engine.webkit.profileUuidString
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.posix.memcpy

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** Whether this device runs iOS [major].[minor] or later. */
internal fun osAtLeast(major: Long, minor: Long = 0): Boolean =
    NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
        cValue<NSOperatingSystemVersion> {
            majorVersion = major
            minorVersion = minor
            patchVersion = 0
        },
    )

/**
 * The UUID of a named profile's data store: the same for the same [id] at every launch (a name-based UUID, version 5
 * layout, from SHA-256), so WebKit finds the profile's store again.
 */
internal fun profileUuid(id: String): NSUUID = NSUUID(uUIDString = profileUuidString(id))
