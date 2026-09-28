package com.swithun.cmpmermaid.core.gantt.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Native UTC translation of the Day.js parsing and calendar operations used by
 * Mermaid 12's ganttDb.js. UTC keeps the common implementation deterministic
 * across JVM, Android, and Kotlin/Native.
 */
internal object GanttDatePort {
    private val inputTokens = listOf(
        "YYYY",
        "MMMM",
        "MMM",
        "DDD",
        "SSS",
        "YY",
        "MM",
        "DD",
        "Do",
        "HH",
        "hh",
        "mm",
        "ss",
        "SS",
        "ZZ",
        "Q",
        "M",
        "D",
        "H",
        "h",
        "m",
        "s",
        "S",
        "A",
        "a",
        "X",
        "x",
        "Z",
    )
    private val monthNames = listOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    )
    private val weekdayNames = listOf(
        "Monday",
        "Tuesday",
        "Wednesday",
        "Thursday",
        "Friday",
        "Saturday",
        "Sunday",
    )

    fun parse(
        source: String,
        format: String,
    ): GMResult<Long, MermaidError> {
        val value = source.trim()
        val pattern = format.trim().ifEmpty { "YYYY-MM-DD" }
        if (pattern == "x" && value.all(Char::isDigit)) {
            return value.toLongOrNull()
                ?.let { millis -> GMResult.Ok(millis) }
                ?: invalidDate(source, format)
        }
        if (pattern == "X" && value.matches(Regex("""\d+(?:\.\d+)?"""))) {
            return value.toDoubleOrNull()
                ?.takeIf(Double::isFinite)
                ?.let { seconds -> GMResult.Ok((seconds * 1_000.0).roundToLong()) }
                ?: invalidDate(source, format)
        }

        val compiled = compileInputPattern(pattern)
        val match = compiled.regex.matchEntire(value) ?: return invalidDate(source, format)
        val values = linkedMapOf<String, String>()
        compiled.tokens.forEachIndexed { index, token ->
            values[token] = match.groupValues[index + 1]
        }
        return buildMillis(values, source, format)
    }

    fun parseDuration(source: String): GanttDuration? {
        val match = DURATION.matchEntire(source.trim()) ?: return null
        val value = match.groupValues[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        val unit = when (match.groupValues[2]) {
            "y" -> GanttDurationUnit.Year
            "M" -> GanttDurationUnit.Month
            "w" -> GanttDurationUnit.Week
            "d" -> GanttDurationUnit.Day
            "h" -> GanttDurationUnit.Hour
            "m" -> GanttDurationUnit.Minute
            "s" -> GanttDurationUnit.Second
            "ms" -> GanttDurationUnit.Millisecond
            else -> return null
        }
        return GanttDuration(value, unit)
    }

    fun add(
        millis: Long,
        duration: GanttDuration,
    ): GMResult<Long, MermaidError> = try {
        val result = when (duration.unit) {
            GanttDurationUnit.Year -> addCalendar(
                millis,
                duration.value.toInt(),
                DateTimeUnit.YEAR,
            )
            GanttDurationUnit.Month -> addCalendar(
                millis,
                duration.value.toInt(),
                DateTimeUnit.MONTH,
            )
            GanttDurationUnit.Week ->
                millis + duration.value.times(7.0).roundToLong() * MILLIS_PER_DAY
            GanttDurationUnit.Day ->
                millis + duration.value.roundToLong() * MILLIS_PER_DAY
            GanttDurationUnit.Hour ->
                millis + (duration.value * MILLIS_PER_HOUR).roundToLong()
            GanttDurationUnit.Minute ->
                millis + (duration.value * MILLIS_PER_MINUTE).roundToLong()
            GanttDurationUnit.Second ->
                millis + (duration.value * MILLIS_PER_SECOND).roundToLong()
            GanttDurationUnit.Millisecond ->
                millis + duration.value.roundToLong()
        }
        GMResult.Ok(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        GMResult.Err(
            MermaidError.Layout(
                "Invalid Gantt duration '${duration.value}${duration.unit.suffix}': " +
                    (failure.message ?: "date overflow"),
            ),
        )
    }

    fun addDays(
        millis: Long,
        days: Int,
    ): GMResult<Long, MermaidError> = add(
        millis,
        GanttDuration(days.toDouble(), GanttDurationUnit.Day),
    )

    fun dateOnly(millis: Long): String {
        val date = localDateTime(millis).date
        return "${date.year.pad(4)}-${date.month.number.pad(2)}-${date.day.pad(2)}"
    }

    fun format(
        millis: Long,
        format: String,
    ): String {
        val dateTime = localDateTime(millis)
        val date = dateTime.date
        val dayOfYear = date.dayOfYear
        val weekday = date.dayOfWeek
        val hour12 = when (val hour = dateTime.hour % 12) {
            0 -> 12
            else -> hour
        }
        val isoDayNumber = date.dayOfWeek.ordinal + 1
        val sundayIndex = isoDayNumber % 7
        val replacements = mapOf(
            "%a" to weekdayNames[weekday.ordinal].take(3),
            "%A" to weekdayNames[weekday.ordinal],
            "%b" to monthNames[date.month.ordinal].take(3),
            "%B" to monthNames[date.month.ordinal],
            "%c" to buildString {
                append(weekdayNames[weekday.ordinal].take(3))
                append(' ')
                append(monthNames[date.month.ordinal].take(3))
                append(' ')
                append(date.day.toString().padStart(2, ' '))
                append(' ')
                append(dateTime.hour.pad(2))
                append(':')
                append(dateTime.minute.pad(2))
                append(':')
                append(dateTime.second.pad(2))
                append(' ')
                append(date.year.pad(4))
            },
            "%d" to date.day.pad(2),
            "%e" to date.day.toString().padStart(2, ' '),
            "%H" to dateTime.hour.pad(2),
            "%I" to hour12.pad(2),
            "%j" to dayOfYear.pad(3),
            "%m" to date.month.number.pad(2),
            "%M" to dateTime.minute.pad(2),
            "%L" to dateTime.nanosecond.div(1_000_000).pad(3),
            "%p" to if (dateTime.hour < 12) "AM" else "PM",
            "%S" to dateTime.second.pad(2),
            "%U" to weekNumber(date, GanttWeekday.Sunday).pad(2),
            "%w" to sundayIndex.toString(),
            "%W" to weekNumber(date, GanttWeekday.Monday).pad(2),
            "%x" to "${date.month.number.pad(2)}/${date.day.pad(2)}/${date.year.pad(4)}",
            "%X" to "${dateTime.hour.pad(2)}:${dateTime.minute.pad(2)}:" +
                dateTime.second.pad(2),
            "%y" to (date.year % 100).pad(2),
            "%Y" to date.year.pad(4),
            "%Z" to "+0000",
            "%%" to "%",
        )
        return buildString {
            var index = 0
            while (index < format.length) {
                if (format[index] == '%' && index + 1 < format.length) {
                    val token = format.substring(index, index + 2)
                    val replacement = replacements[token]
                    if (replacement != null) {
                        append(replacement)
                        index += 2
                        continue
                    }
                }
                append(format[index])
                index += 1
            }
        }
    }

    fun formatInput(
        millis: Long,
        format: String,
    ): String {
        val dateTime = localDateTime(millis)
        val date = dateTime.date
        val hour12 = (dateTime.hour % 12).takeIf { it > 0 } ?: 12
        val replacements = mapOf(
            "YYYY" to date.year.pad(4),
            "MMMM" to monthNames[date.month.ordinal],
            "MMM" to monthNames[date.month.ordinal].take(3),
            "DDD" to date.dayOfYear.toString(),
            "SSS" to dateTime.nanosecond.div(1_000_000).pad(3),
            "YY" to (date.year % 100).pad(2),
            "MM" to (date.month.ordinal + 1).pad(2),
            "DD" to date.day.pad(2),
            "Do" to ordinal(date.day),
            "HH" to dateTime.hour.pad(2),
            "hh" to hour12.pad(2),
            "mm" to dateTime.minute.pad(2),
            "ss" to dateTime.second.pad(2),
            "SS" to dateTime.nanosecond.div(10_000_000).pad(2),
            "ZZ" to "+0000",
            "Q" to (date.month.ordinal / 3 + 1).toString(),
            "M" to (date.month.ordinal + 1).toString(),
            "D" to date.day.toString(),
            "H" to dateTime.hour.toString(),
            "h" to hour12.toString(),
            "m" to dateTime.minute.toString(),
            "s" to dateTime.second.toString(),
            "S" to dateTime.nanosecond.div(100_000_000).toString(),
            "A" to if (dateTime.hour < 12) "AM" else "PM",
            "a" to if (dateTime.hour < 12) "am" else "pm",
            "X" to (millis / 1_000L).toString(),
            "x" to millis.toString(),
            "Z" to "+00:00",
        )
        return buildString {
            var index = 0
            while (index < format.length) {
                if (format[index] == '[') {
                    val end = format.indexOf(']', startIndex = index + 1)
                    if (end >= 0) {
                        append(format.substring(index + 1, end))
                        index = end + 1
                        continue
                    }
                }
                val token = inputTokens.firstOrNull { candidate ->
                    format.startsWith(candidate, index)
                }
                if (token == null) {
                    append(format[index])
                    index += 1
                } else {
                    append(replacements.getValue(token))
                    index += token.length
                }
            }
        }
    }

    fun weekday(millis: Long): GanttWeekday =
        localDateTime(millis).date.dayOfWeek.toGanttWeekday()

    fun startOfDay(millis: Long): Long =
        localDateTime(millis).date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    fun endOfDay(millis: Long): Long =
        startOfDay(millis) + MILLIS_PER_DAY - 1L

    fun automaticTicks(
        minimum: Long,
        maximum: Long,
        desiredCount: Int,
    ): List<Long> {
        if (maximum <= minimum) return listOf(minimum)
        val span = maximum - minimum
        val target = span.toDouble() / desiredCount.coerceAtLeast(1)
        val upperIndex = AUTO_INTERVALS.indexOfFirst { interval ->
            interval.approxMillis >= target
        }
        val interval = when {
            upperIndex < 0 -> GanttTickInterval(1, GanttTickUnit.Year)
            upperIndex == 0 -> AUTO_INTERVALS.first()
            else -> {
                val lower = AUTO_INTERVALS[upperIndex - 1]
                val upper = AUTO_INTERVALS[upperIndex]
                if (target / lower.approxMillis < upper.approxMillis / target) {
                    lower
                } else {
                    upper
                }
            }
        }
        return ticks(minimum, maximum, interval, GanttWeekday.Sunday)
    }

    fun parseTickInterval(source: String?): GanttTickInterval? {
        val match = source?.trim()?.let(TICK_INTERVAL::matchEntire) ?: return null
        val count = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val unit = when (match.groupValues[2]) {
            "millisecond" -> GanttTickUnit.Millisecond
            "second" -> GanttTickUnit.Second
            "minute" -> GanttTickUnit.Minute
            "hour" -> GanttTickUnit.Hour
            "day" -> GanttTickUnit.Day
            "week" -> GanttTickUnit.Week
            "month" -> GanttTickUnit.Month
            else -> return null
        }
        return GanttTickInterval(count, unit)
    }

    fun ticks(
        minimum: Long,
        maximum: Long,
        interval: GanttTickInterval,
        weekStart: GanttWeekday,
    ): List<Long> {
        if (minimum > maximum || interval.count <= 0) return emptyList()
        var cursor = firstTickAtOrAfter(minimum, interval, weekStart)
            ?: return emptyList()
        val result = mutableListOf<Long>()
        while (cursor <= maximum && result.size <= MAX_TICKS) {
            result += cursor
            val next = nextTick(cursor, interval) ?: break
            if (next <= cursor) break
            cursor = next
        }
        return if (result.size > MAX_TICKS) emptyList() else result
    }

    private fun firstTickAtOrAfter(
        minimum: Long,
        interval: GanttTickInterval,
        weekStart: GanttWeekday,
    ): Long? {
        if (interval.unit == GanttTickUnit.Millisecond) {
            val count = interval.count.toLong()
            val remainder = positiveModulo(minimum, count)
            return if (remainder == 0L) minimum else minimum + count - remainder
        }
        var cursor = alignTick(minimum, interval.unit, weekStart)
        if (interval.unit == GanttTickUnit.Week && interval.count > 1) {
            val epochWeek = alignTick(0L, GanttTickUnit.Week, weekStart)
            val weekIndex = (cursor - epochWeek) / MILLIS_PER_WEEK
            var weeksToAdvance = positiveModulo(
                -weekIndex,
                interval.count.toLong(),
            )
            if (weeksToAdvance == 0L && cursor < minimum) {
                weeksToAdvance = interval.count.toLong()
            }
            return cursor + weeksToAdvance * MILLIS_PER_WEEK
        }
        val baseInterval = GanttTickInterval(1, interval.unit)
        while (cursor < minimum || !matchesTickStep(cursor, interval)) {
            cursor = addTick(cursor, baseInterval) ?: return null
        }
        return cursor
    }

    private fun nextTick(
        cursor: Long,
        interval: GanttTickInterval,
    ): Long? {
        if (
            interval.unit == GanttTickUnit.Millisecond ||
            interval.unit == GanttTickUnit.Week
        ) {
            return addTick(cursor, interval)
        }
        val baseInterval = GanttTickInterval(1, interval.unit)
        var next = addTick(cursor, baseInterval) ?: return null
        while (!matchesTickStep(next, interval)) {
            next = addTick(next, baseInterval) ?: return null
        }
        return next
    }

    private fun matchesTickStep(
        millis: Long,
        interval: GanttTickInterval,
    ): Boolean {
        if (interval.count == 1) return true
        val dateTime = localDateTime(millis)
        val field = when (interval.unit) {
            GanttTickUnit.Millisecond -> millis
            GanttTickUnit.Second -> dateTime.second.toLong()
            GanttTickUnit.Minute -> dateTime.minute.toLong()
            GanttTickUnit.Hour -> dateTime.hour.toLong()
            GanttTickUnit.Day -> (dateTime.day - 1).toLong()
            GanttTickUnit.Month -> (dateTime.month.number - 1).toLong()
            GanttTickUnit.Year -> dateTime.year.toLong()
            GanttTickUnit.Week -> return true
        }
        return positiveModulo(field, interval.count.toLong()) == 0L
    }

    private fun positiveModulo(
        value: Long,
        divisor: Long,
    ): Long = ((value % divisor) + divisor) % divisor

    private fun compileInputPattern(format: String): CompiledInputPattern {
        val tokens = mutableListOf<String>()
        val regex = buildString {
            append('^')
            var index = 0
            while (index < format.length) {
                if (format[index] == '[') {
                    val end = format.indexOf(']', startIndex = index + 1)
                    if (end >= 0) {
                        append(Regex.escape(format.substring(index + 1, end)))
                        index = end + 1
                        continue
                    }
                }
                val token = inputTokens.firstOrNull { candidate ->
                    format.startsWith(candidate, index)
                }
                if (token == null) {
                    append(Regex.escape(format[index].toString()))
                    index += 1
                } else {
                    tokens += token
                    append(tokenRegex(token))
                    index += token.length
                }
            }
            append('$')
        }
        return CompiledInputPattern(Regex(regex, RegexOption.IGNORE_CASE), tokens)
    }

    private fun tokenRegex(token: String): String = when (token) {
        "YYYY" -> """(-?\d{4,6})"""
        "YY" -> """(\d{2})"""
        "Q" -> """([1-4])"""
        "MMMM" -> "(${monthNames.joinToString("|")})"
        "MMM" -> "(${monthNames.joinToString("|") { it.take(3) }})"
        "MM", "DD", "HH", "hh", "mm", "ss" -> """(\d{2})"""
        "M", "D", "H", "h", "m", "s" -> """(\d{1,2})"""
        "Do" -> """(\d{1,2}(?:st|nd|rd|th))"""
        "DDD", "DDDD" -> """(\d{1,3})"""
        "SSS" -> """(\d{3})"""
        "SS" -> """(\d{2})"""
        "S" -> """(\d)"""
        "A", "a" -> """(am|pm)"""
        "X" -> """(\d+(?:\.\d+)?)"""
        "x" -> """(\d+)"""
        "Z" -> """(Z|[+-]\d{2}:\d{2})"""
        "ZZ" -> """(Z|[+-]\d{4})"""
        else -> error("Unknown Gantt input date token $token")
    }

    private fun buildMillis(
        values: Map<String, String>,
        source: String,
        format: String,
    ): GMResult<Long, MermaidError> {
        values["x"]?.toLongOrNull()?.let { return GMResult.Ok(it) }
        values["X"]?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { seconds ->
            return GMResult.Ok((seconds * 1_000.0).roundToLong())
        }

        val year = when {
            values["YYYY"] != null -> values.getValue("YYYY").toIntOrNull()
            values["YY"] != null -> values.getValue("YY").toIntOrNull()?.let { 2000 + it }
            else -> 1970
        } ?: return invalidDate(source, format)
        var month = when {
            values["MMMM"] != null -> monthNames.indexOfFirst {
                it.equals(values.getValue("MMMM"), ignoreCase = true)
            } + 1
            values["MMM"] != null -> monthNames.indexOfFirst {
                it.take(3).equals(values.getValue("MMM"), ignoreCase = true)
            } + 1
            values["MM"] != null -> values.getValue("MM").toIntOrNull()
            values["M"] != null -> values.getValue("M").toIntOrNull()
            values["Q"] != null -> values.getValue("Q").toIntOrNull()?.let { (it - 1) * 3 + 1 }
            else -> 1
        } ?: return invalidDate(source, format)
        var day = when {
            values["DD"] != null -> values.getValue("DD").toIntOrNull()
            values["Do"] != null -> values.getValue("Do").takeWhile(Char::isDigit).toIntOrNull()
            values["D"] != null -> values.getValue("D").toIntOrNull()
            else -> 1
        } ?: return invalidDate(source, format)
        val dayOfYear = values["DDD"]?.toIntOrNull() ?: values["DDDD"]?.toIntOrNull()
        if (dayOfYear != null) {
            val resolved = try {
                LocalDate(year, 1, 1).plus(dayOfYear - 1, DateTimeUnit.DAY)
            } catch (_: Exception) {
                return invalidDate(source, format)
            }
            month = resolved.month.number
            day = resolved.day
        }

        var hour = values["HH"]?.toIntOrNull()
            ?: values["H"]?.toIntOrNull()
            ?: values["hh"]?.toIntOrNull()
            ?: values["h"]?.toIntOrNull()
            ?: 0
        val meridiem = values["A"] ?: values["a"]
        if (meridiem != null) {
            if (hour !in 1..12) return invalidDate(source, format)
            hour %= 12
            if (meridiem.equals("pm", ignoreCase = true)) hour += 12
        }
        val minute = values["mm"]?.toIntOrNull() ?: values["m"]?.toIntOrNull() ?: 0
        val second = values["ss"]?.toIntOrNull() ?: values["s"]?.toIntOrNull() ?: 0
        val millisecond = when {
            values["SSS"] != null -> values.getValue("SSS").toIntOrNull()
            values["SS"] != null -> values.getValue("SS").toIntOrNull()?.times(10)
            values["S"] != null -> values.getValue("S").toIntOrNull()?.times(100)
            else -> 0
        } ?: return invalidDate(source, format)
        val offsetMinutes = parseOffset(values["Z"] ?: values["ZZ"] ?: "Z")
            ?: return invalidDate(source, format)
        return try {
            val resolvedMonth = Month.values().getOrNull(month - 1)
                ?: return invalidDate(source, format)
            val local = LocalDateTime(
                year = year,
                month = resolvedMonth,
                day = day,
                hour = hour,
                minute = minute,
                second = second,
                nanosecond = millisecond * 1_000_000,
            )
            GMResult.Ok(
                local.toInstant(TimeZone.UTC).toEpochMilliseconds() -
                    offsetMinutes * MILLIS_PER_MINUTE,
            )
        } catch (_: Exception) {
            invalidDate(source, format)
        }
    }

    private fun parseOffset(source: String): Int? {
        if (source == "Z") return 0
        val compact = source.replace(":", "")
        if (!compact.matches(Regex("""[+-]\d{4}"""))) return null
        val sign = if (compact[0] == '-') -1 else 1
        val hours = compact.substring(1, 3).toIntOrNull() ?: return null
        val minutes = compact.substring(3, 5).toIntOrNull() ?: return null
        if (hours > 23 || minutes > 59) return null
        return sign * (hours * 60 + minutes)
    }

    private fun addCalendar(
        millis: Long,
        value: Int,
        unit: DateTimeUnit.DateBased,
    ): Long {
        val dateTime = localDateTime(millis)
        val date = dateTime.date.plus(value, unit)
        return LocalDateTime(date, dateTime.time)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
    }

    private fun localDateTime(millis: Long): LocalDateTime =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)

    private fun alignTick(
        millis: Long,
        unit: GanttTickUnit,
        weekStart: GanttWeekday,
    ): Long {
        val dateTime = localDateTime(millis)
        val aligned = when (unit) {
            GanttTickUnit.Millisecond -> dateTime
            GanttTickUnit.Second -> LocalDateTime(
                dateTime.year,
                dateTime.month,
                dateTime.day,
                dateTime.hour,
                dateTime.minute,
                dateTime.second,
            )
            GanttTickUnit.Minute -> LocalDateTime(
                dateTime.year,
                dateTime.month,
                dateTime.day,
                dateTime.hour,
                dateTime.minute,
            )
            GanttTickUnit.Hour -> LocalDateTime(
                dateTime.year,
                dateTime.month,
                dateTime.day,
                dateTime.hour,
                0,
            )
            GanttTickUnit.Day,
            GanttTickUnit.Week,
            -> LocalDateTime(dateTime.date, kotlinx.datetime.LocalTime(0, 0))
            GanttTickUnit.Month -> LocalDateTime(dateTime.year, dateTime.month, 1, 0, 0)
            GanttTickUnit.Year -> LocalDateTime(dateTime.year, Month.JANUARY, 1, 0, 0)
        }
        var result = aligned.toInstant(TimeZone.UTC).toEpochMilliseconds()
        if (unit == GanttTickUnit.Week) {
            val current = weekday(result).ordinal
            val target = weekStart.ordinal
            val daysBack = (current - target + 7) % 7
            result -= daysBack * MILLIS_PER_DAY
        }
        return result
    }

    private fun addTick(
        millis: Long,
        interval: GanttTickInterval,
    ): Long? = when (interval.unit) {
        GanttTickUnit.Millisecond -> millis + interval.count
        GanttTickUnit.Second -> millis + interval.count * MILLIS_PER_SECOND
        GanttTickUnit.Minute -> millis + interval.count * MILLIS_PER_MINUTE
        GanttTickUnit.Hour -> millis + interval.count * MILLIS_PER_HOUR
        GanttTickUnit.Day -> millis + interval.count * MILLIS_PER_DAY
        GanttTickUnit.Week -> millis + interval.count * 7L * MILLIS_PER_DAY
        GanttTickUnit.Month -> when (
            val result = add(
                millis,
                GanttDuration(interval.count.toDouble(), GanttDurationUnit.Month),
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> null
        }
        GanttTickUnit.Year -> when (
            val result = add(
                millis,
                GanttDuration(interval.count.toDouble(), GanttDurationUnit.Year),
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> null
        }
    }

    private fun weekNumber(
        date: LocalDate,
        weekStart: GanttWeekday,
    ): Int {
        val first = LocalDate(date.year, 1, 1)
        val firstIndex = first.dayOfWeek.toGanttWeekday().ordinal
        val startIndex = weekStart.ordinal
        val offset = (7 - (firstIndex - startIndex + 7) % 7) % 7
        return ((date.dayOfYear - 1 - offset) / 7 + 1).coerceAtLeast(0)
    }

    private fun DayOfWeek.toGanttWeekday(): GanttWeekday = when (this) {
        DayOfWeek.MONDAY -> GanttWeekday.Monday
        DayOfWeek.TUESDAY -> GanttWeekday.Tuesday
        DayOfWeek.WEDNESDAY -> GanttWeekday.Wednesday
        DayOfWeek.THURSDAY -> GanttWeekday.Thursday
        DayOfWeek.FRIDAY -> GanttWeekday.Friday
        DayOfWeek.SATURDAY -> GanttWeekday.Saturday
        DayOfWeek.SUNDAY -> GanttWeekday.Sunday
    }

    private fun Int.pad(length: Int): String =
        toString().padStart(length, '0')

    private fun ordinal(value: Int): String {
        val suffix = if (value % 100 in 11..13) {
            "th"
        } else {
            when (value % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$value$suffix"
    }

    private fun <T> invalidDate(
        source: String,
        format: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = 1,
            column = 1,
            message = "Invalid Gantt date '$source' for format '${format.trim()}'",
        ),
    )

    private data class CompiledInputPattern(
        val regex: Regex,
        val tokens: List<String>,
    )

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND
    private const val MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE
    private const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR
    private const val MILLIS_PER_WEEK = 7L * MILLIS_PER_DAY
    private const val MAX_TICKS = 10_000
    private val DURATION = Regex("""(\d+(?:\.\d+)?)(M|ms|[dhmswy])""")
    private val TICK_INTERVAL =
        Regex("""([1-9]\d*)(millisecond|second|minute|hour|day|week|month)""")
    private val AUTO_INTERVALS = listOf(
        GanttTickInterval(1, GanttTickUnit.Millisecond),
        GanttTickInterval(1, GanttTickUnit.Second),
        GanttTickInterval(5, GanttTickUnit.Second),
        GanttTickInterval(15, GanttTickUnit.Second),
        GanttTickInterval(30, GanttTickUnit.Second),
        GanttTickInterval(1, GanttTickUnit.Minute),
        GanttTickInterval(5, GanttTickUnit.Minute),
        GanttTickInterval(15, GanttTickUnit.Minute),
        GanttTickInterval(30, GanttTickUnit.Minute),
        GanttTickInterval(1, GanttTickUnit.Hour),
        GanttTickInterval(3, GanttTickUnit.Hour),
        GanttTickInterval(6, GanttTickUnit.Hour),
        GanttTickInterval(12, GanttTickUnit.Hour),
        GanttTickInterval(1, GanttTickUnit.Day),
        GanttTickInterval(2, GanttTickUnit.Day),
        GanttTickInterval(1, GanttTickUnit.Week),
        GanttTickInterval(1, GanttTickUnit.Month),
        GanttTickInterval(3, GanttTickUnit.Month),
        GanttTickInterval(1, GanttTickUnit.Year),
    )
}

internal data class GanttDuration(
    val value: Double,
    val unit: GanttDurationUnit,
)

internal enum class GanttDurationUnit(
    val suffix: String,
) {
    Year("y"),
    Month("M"),
    Week("w"),
    Day("d"),
    Hour("h"),
    Minute("m"),
    Second("s"),
    Millisecond("ms"),
}

internal data class GanttTickInterval(
    val count: Int,
    val unit: GanttTickUnit,
) {
    val approxMillis: Double
        get() = count * unit.approxMillis
}

internal enum class GanttTickUnit(
    val approxMillis: Double,
) {
    Millisecond(1.0),
    Second(1_000.0),
    Minute(60_000.0),
    Hour(3_600_000.0),
    Day(86_400_000.0),
    Week(604_800_000.0),
    Month(2_629_746_000.0),
    Year(31_556_952_000.0),
}
