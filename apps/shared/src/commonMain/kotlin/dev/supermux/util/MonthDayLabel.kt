package dev.supermux.util

import dev.supermux.chat.civilFromDays
import dev.supermux.chat.localUtcOffsetMs

private const val DAY_MS = 86_400_000L

/** Short English month names — the `TextStyle.SHORT` + `Locale.US` set both apps formatted with. */
private val SHORT_MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * "Jul 14" — the local calendar day of [epochMs] as short month + day-of-month.
 *
 * Both apps' usage "resets <Mon D>" line went through
 * `Instant.ofEpochMilli(ms).atZone(systemDefault())` + `month.getDisplayName(SHORT, Locale.US)`;
 * that is `java.time`, which cannot follow the Usage screen into `:ui` commonMain (cluster E6).
 * The only platform-shaped fact is the wall-clock offset, which already has an expect
 * ([localUtcOffsetMs]) — the civil-date maths and the month table are plain Kotlin, so the output
 * is identical on every target.
 */
fun shortMonthDayLabel(epochMs: Long, offsetMs: Long = localUtcOffsetMs(epochMs)): String {
    val local = epochMs + offsetMs
    val days = if (local >= 0 || local % DAY_MS == 0L) local / DAY_MS else local / DAY_MS - 1
    val (_, month, day) = civilFromDays(days)
    val name = SHORT_MONTHS.getOrNull(month - 1) ?: return ""
    return "$name $day"
}
