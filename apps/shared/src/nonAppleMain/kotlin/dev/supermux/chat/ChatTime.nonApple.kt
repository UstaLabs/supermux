package dev.supermux.chat

import java.util.TimeZone

/** JVM + Android: the default zone's offset at that instant (DST-aware). */
actual fun localUtcOffsetMs(epochMs: Long): Long =
    TimeZone.getDefault().getOffset(epochMs).toLong()
