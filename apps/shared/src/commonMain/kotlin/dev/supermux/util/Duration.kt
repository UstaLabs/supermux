package dev.supermux.util

/**
 * Human-readable elapsed duration — the two largest relevant units (web parity
 * with format-duration.ts). 5 -> "5 seconds", 185 -> "3 minutes 5 seconds",
 * 3725 -> "1 hour 2 minutes". Shared so iOS + Android format the timer identically.
 */
fun formatDuration(totalSeconds: Long): String {
    val s = maxOf(0L, totalSeconds)
    val days = s / 86400
    val hours = (s % 86400) / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    fun u(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"}"
    return when {
        days > 0 -> if (hours > 0) "${u(days, "day")} ${u(hours, "hour")}" else u(days, "day")
        hours > 0 -> if (minutes > 0) "${u(hours, "hour")} ${u(minutes, "minute")}" else u(hours, "hour")
        minutes > 0 -> if (seconds > 0) "${u(minutes, "minute")} ${u(seconds, "second")}" else u(minutes, "minute")
        else -> u(seconds, "second")
    }
}
