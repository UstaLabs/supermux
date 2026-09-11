package dev.supermux.util

@Suppress("UNUSED_PARAMETER")
private fun formatMedium(epochMs: Double): String = js(
    "new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(epochMs))",
)

/** The browser's locale, medium date + short time — the same intent as the JVM's FormatStyle pair. */
actual fun formatLocalDateTimeMedium(epochMs: Long): String = formatMedium(epochMs.toDouble())
