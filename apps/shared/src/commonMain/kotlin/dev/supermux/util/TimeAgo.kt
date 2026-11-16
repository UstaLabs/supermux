package dev.supermux.util

/** How long ago something happened, read like a clock; the UI words it (SharedWords.kt timeAgoText). */
sealed interface TimeAgo {
    data object JustNow : TimeAgo
    data class Minutes(val n: Int) : TimeAgo
    data class Hours(val n: Int) : TimeAgo
    data class Days(val n: Int) : TimeAgo
}

/** [thenMs] relative to [nowMs] (epoch millis); a time in the future reads as just now. */
fun timeAgo(nowMs: Long, thenMs: Long): TimeAgo {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> TimeAgo.JustNow
        minutes < 60 -> TimeAgo.Minutes(minutes)
        minutes < 24 * 60 -> TimeAgo.Hours(minutes / 60)
        else -> TimeAgo.Days(minutes / (24 * 60))
    }
}
