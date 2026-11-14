package dev.supermux.util

import dev.supermux.chat.civilFromDays
import dev.supermux.chat.localUtcOffsetMs

private const val DAY_MS = 86_400_000L

/**
 * The local calendar day of [epochMs] as (month 1–12, day of month) — the date half of the Usage
 * screen's "resets Jul 14" line, which the UI words in the app's language (UsageScreen.kt monthDayLabel).
 *
 * Both apps used to go through `Instant.ofEpochMilli(ms).atZone(systemDefault())`; that is
 * `java.time`, which cannot follow the Usage screen into `:ui` commonMain (cluster E6). The only
 * platform-shaped fact is the wall-clock offset, which already has an expect ([localUtcOffsetMs]) —
 * the civil-date maths is plain Kotlin, so the output is identical on every target.
 */
fun localMonthDay(epochMs: Long, offsetMs: Long = localUtcOffsetMs(epochMs)): Pair<Int, Int> {
    val local = epochMs + offsetMs
    val days = if (local >= 0 || local % DAY_MS == 0L) local / DAY_MS else local / DAY_MS - 1
    val (_, month, day) = civilFromDays(days)
    return month to day
}
