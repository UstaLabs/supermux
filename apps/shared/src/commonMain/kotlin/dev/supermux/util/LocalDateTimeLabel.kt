package dev.supermux.util

import dev.supermux.chat.parseChatTs

/**
 * The user's own locale/zone rendering of an instant — "Jun 1, 2024, 1:00 AM" on a US host.
 *
 * Both apps' Curator "Next run" row went through `DateTimeFormatter.ofLocalizedDateTime(MEDIUM,
 * SHORT)`; that is `java.time`, which cannot follow the screen into `:ui` commonMain (cluster E4).
 * The formatting is the ONLY platform-shaped step, so it arrives here as an expect the way
 * `localUtcOffsetMs` does — the JVM/Android actual is literally the old call, so neither host's
 * output changes.
 */
expect fun formatLocalDateTimeMedium(epochMs: Long): String

/**
 * Curator "Next run" label: disabled / the run time in the user's locale / the raw broker string
 * when it is not a timestamp we understand / "—" when there is none.
 *
 * Mirrors the web's `nextRunLabel`, and is the union of the two apps' identical copies.
 */
fun curatorNextRunLabel(enabled: Boolean, nextRun: String?): String {
    if (!enabled) return "Disabled"
    val raw = nextRun ?: return "—"
    val epochMs = parseChatTs(raw) ?: return raw
    return runCatching { formatLocalDateTimeMedium(epochMs) }.getOrNull() ?: raw
}
