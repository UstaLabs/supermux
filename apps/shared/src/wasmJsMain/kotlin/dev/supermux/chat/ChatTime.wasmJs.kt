package dev.supermux.chat

@Suppress("UNUSED_PARAMETER")
private fun tzOffsetMin(epochMs: Double): Int = js("new Date(epochMs).getTimezoneOffset()")

/** JS reports minutes WEST of UTC; the expect wants an offset to ADD to UTC, hence the sign flip. */
actual fun localUtcOffsetMs(epochMs: Long): Long =
    -(tzOffsetMin(epochMs.toDouble()).toLong() * 60_000L)
