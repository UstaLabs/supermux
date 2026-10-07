package dev.supermux.chat

/**
 * Chat timestamp formatting, shared by every host.
 *
 * `java.time` cannot come along to `:ui` commonMain (iOS is next), so the arithmetic is plain
 * `Long` maths over epoch milliseconds and the only platform-shaped fact — how far the local
 * wall clock is from UTC at a given instant — arrives through [localUtcOffsetMs].
 */

/** Local wall-clock offset from UTC, in milliseconds, at [epochMs] (DST-aware). */
expect fun localUtcOffsetMs(epochMs: Long): Long

private const val DAY_MS = 86_400_000L

/**
 * Parse the broker's `ts` shape — epoch seconds, epoch milliseconds, or ISO-8601 — into epoch
 * milliseconds. Null when [ts] is blank or unparseable.
 *
 * The seconds/millis split is the same threshold the old `java.time` formatters used
 * (`< 1e12` reads as seconds), so an existing transcript renders identically.
 */
fun parseChatTs(ts: String?): Long? {
    if (ts.isNullOrBlank()) return null
    ts.toLongOrNull()?.let { n -> return if (n < 1_000_000_000_000L) n * 1000L else n }
    return parseIso8601Millis(ts)
}

/**
 * Minimal ISO-8601 instant parser: `yyyy-MM-ddTHH:mm[:ss[.fff]][Z|±HH:mm]`. Returns null on
 * anything it does not recognise (the caller then shows no time at all, as before).
 */
internal fun parseIso8601Millis(text: String): Long? {
    val s = text.trim()
    if (s.length < 16) return null
    val year = s.substring(0, 4).toIntOrNull() ?: return null
    if (s[4] != '-' || s[7] != '-') return null
    val month = s.substring(5, 7).toIntOrNull() ?: return null
    val day = s.substring(8, 10).toIntOrNull() ?: return null
    if (s[10] != 'T' && s[10] != 't' && s[10] != ' ') return null
    if (s[13] != ':') return null
    val hour = s.substring(11, 13).toIntOrNull() ?: return null
    val minute = s.substring(14, 16).toIntOrNull() ?: return null
    var i = 16
    var second = 0
    var millis = 0
    if (i < s.length && s[i] == ':') {
        second = s.substring(i + 1, minOf(i + 3, s.length)).toIntOrNull() ?: return null
        i += 3
        if (i < s.length && s[i] == '.') {
            var j = i + 1
            while (j < s.length && s[j].isDigit()) j++
            val frac = s.substring(i + 1, j)
            millis = (frac + "000").substring(0, 3).toIntOrNull() ?: 0
            i = j
        }
    }
    var offsetMinutes = 0
    if (i < s.length) {
        when (s[i]) {
            'Z', 'z' -> Unit
            '+', '-' -> {
                val sign = if (s[i] == '-') -1 else 1
                val rest = s.substring(i + 1).replace(":", "")
                if (rest.length < 4) return null
                val oh = rest.substring(0, 2).toIntOrNull() ?: return null
                val om = rest.substring(2, 4).toIntOrNull() ?: return null
                offsetMinutes = sign * (oh * 60 + om)
            }
            else -> return null
        }
    }
    val days = daysFromCivil(year, month, day)
    val utcMs = days * DAY_MS + hour * 3_600_000L + minute * 60_000L + second * 1000L + millis
    return utcMs - offsetMinutes * 60_000L
}

/**
 * Days since 1970-01-01 for a proleptic-Gregorian civil date (Howard Hinnant's `days_from_civil`,
 * the standard branch-free algorithm — correct for every year the app can see).
 */
internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153L * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

/** Inverse of [daysFromCivil] — (year, month, day) from days since the epoch. */
internal fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}

private fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

/**
 * A chat row's time label.
 *
 * - today → `HH:mm` (the old `DateTimeFormatter.ofPattern("HH:mm")` output, unchanged)
 * - yesterday → `Yesterday HH:mm`
 * - anything older → `yyyy-MM-dd HH:mm`
 *
 * "Today" is decided in local wall-clock days, using [offsetMs] (defaulted from the host clock)
 * for both instants so a message sent seconds ago never lands on "yesterday".
 */
fun formatChatTime(
    epochMs: Long,
    nowMs: Long,
    offsetMs: Long = localUtcOffsetMs(epochMs),
): String {
    val local = epochMs + offsetMs
    val localNow = nowMs + offsetMs
    val day = floorDiv(local, DAY_MS)
    val today = floorDiv(localNow, DAY_MS)
    val minuteOfDay = floorMod(local, DAY_MS) / 60_000L
    val hhmm = "${two((minuteOfDay / 60).toInt())}:${two((minuteOfDay % 60).toInt())}"
    return when (day) {
        today -> hhmm
        today - 1 -> "Yesterday $hhmm"
        else -> {
            val (y, m, d) = civilFromDays(day)
            "$y-${two(m)}-${two(d)} $hhmm"
        }
    }
}

/** [formatChatTime] over the raw broker `ts`; null when there is nothing to show. */
fun chatTimeLabel(ts: String?, nowMs: Long): String? =
    parseChatTs(ts)?.let { formatChatTime(it, nowMs) }

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0) q - 1 else q
}

private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
