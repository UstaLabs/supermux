package dev.supermux.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** JVM + Android: the exact `ofLocalizedDateTime(MEDIUM, SHORT)` both hosts formatted with before. */
actual fun formatLocalDateTimeMedium(epochMs: Long): String {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}
