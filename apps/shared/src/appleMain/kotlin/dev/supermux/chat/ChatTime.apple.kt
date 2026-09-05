package dev.supermux.chat

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMTForDate

/** Apple: the local zone's offset at that instant (DST-aware). */
actual fun localUtcOffsetMs(epochMs: Long): Long {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    return NSTimeZone.localTimeZone.secondsFromGMTForDate(date) * 1000L
}
