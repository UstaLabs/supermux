package dev.supermux.desktop.usage

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M4f Task 2 (pure, TDD-first): [formatResetIso] (Claude windows + Cursor `billingCycleEnd`, an
 * ISO-8601 string) and [formatResetEpochSeconds] (Codex windows, a `Double` of epoch SECONDS).
 * Ports Android MoreScreens.kt:998-1021's `formatReset` semantics as two typed entry points.
 *
 * Deviation from the M4f plan text: the plan says "use kotlinx-datetime (shared dep)" but no
 * module in this repo (desktop OR shared) actually depends on kotlinx-datetime — Android's own
 * `formatReset` uses `java.time` (see MoreScreens.kt's imports), and the desktop module already
 * uses `java.time.Instant` elsewhere (chat/Timeline.kt's gutter timestamps). Adding a new Gradle
 * dependency would also mean touching build.gradle.kts, outside this task's "only apps/desktop/src"
 * ground rule. So both formatters take a `now: Instant` (java.time) injection instead — same
 * determinism property the plan asked for, just the JDK type that's actually on the classpath.
 */
class UsageResetFormatTest {

    private val now = Instant.parse("2026-07-09T12:00:00Z")

    // ── formatResetIso: null / blank ─────────────────────────────────────────────────────────────

    @Test fun format_reset_iso_null_is_blank() {
        assertEquals("", formatResetIso(null, now))
    }

    @Test fun format_reset_iso_blank_string_is_blank() {
        assertEquals("", formatResetIso("   ", now))
    }

    @Test fun format_reset_iso_unparseable_string_is_blank() {
        assertEquals("", formatResetIso("not-a-date", now))
    }

    // ── formatResetIso: diff <= 0 ────────────────────────────────────────────────────────────────

    @Test fun format_reset_iso_in_the_past_resets_soon() {
        assertEquals("resets soon", formatResetIso("2026-07-09T11:00:00Z", now))
    }

    @Test fun format_reset_iso_exactly_now_resets_soon() {
        assertEquals("resets soon", formatResetIso("2026-07-09T12:00:00Z", now))
    }

    // ── formatResetIso: < 24h ────────────────────────────────────────────────────────────────────

    @Test fun format_reset_iso_hours_and_minutes_out() {
        // 12:00 -> 14:15 = 2h15m
        assertEquals("resets in 2h 15m", formatResetIso("2026-07-09T14:15:00Z", now))
    }

    @Test fun format_reset_iso_under_an_hour_omits_the_hours_part() {
        // 12:00 -> 12:30 = 0h30m -> "resets in 30m" (no "0h")
        assertEquals("resets in 30m", formatResetIso("2026-07-09T12:30:00Z", now))
    }

    @Test fun format_reset_iso_epoch_millis_numeric_string_fallback() {
        // Numeric-string epoch-millis path (Android tries this before ISO parse).
        val millis = now.plusSeconds(3600).toEpochMilli() // 1h out
        assertEquals("resets in 1h 0m", formatResetIso(millis.toString(), now))
    }

    // ── formatResetIso: >= 24h ───────────────────────────────────────────────────────────────────

    @Test fun format_reset_iso_days_out_shows_short_month_and_day() {
        assertEquals("resets Jul 14", formatResetIso("2026-07-14T00:00:00Z", now))
    }

    @Test fun format_reset_iso_defaults_now_to_clock_when_omitted() {
        // Smoke test for the default-arg path (Clock/Instant.now()): a reset far enough in the
        // future must still hit the ">=24h" branch regardless of when the test runs.
        val farFuture = Instant.now().plusSeconds(9999L * 24 * 3600).toString()
        assertEquals(true, formatResetIso(farFuture).startsWith("resets "))
    }

    // ── formatResetEpochSeconds: null ────────────────────────────────────────────────────────────

    @Test fun format_reset_epoch_seconds_null_is_blank() {
        assertEquals("", formatResetEpochSeconds(null, now))
    }

    // ── formatResetEpochSeconds: diff <= 0 ───────────────────────────────────────────────────────

    @Test fun format_reset_epoch_seconds_in_the_past_resets_soon() {
        val secs = now.minusSeconds(60).epochSecond.toDouble()
        assertEquals("resets soon", formatResetEpochSeconds(secs, now))
    }

    // ── formatResetEpochSeconds: < 24h ───────────────────────────────────────────────────────────

    @Test fun format_reset_epoch_seconds_hours_and_minutes_out() {
        val secs = now.plusSeconds(3600 * 5 + 60 * 40).epochSecond.toDouble() // 5h40m
        assertEquals("resets in 5h 40m", formatResetEpochSeconds(secs, now))
    }

    @Test fun format_reset_epoch_seconds_under_an_hour_omits_the_hours_part() {
        val secs = now.plusSeconds(60 * 12).epochSecond.toDouble() // 12m
        assertEquals("resets in 12m", formatResetEpochSeconds(secs, now))
    }

    // ── formatResetEpochSeconds: >= 24h ──────────────────────────────────────────────────────────

    @Test fun format_reset_epoch_seconds_days_out_shows_short_month_and_day() {
        val secs = Instant.parse("2026-08-01T00:00:00Z").epochSecond.toDouble()
        assertEquals("resets Aug 1", formatResetEpochSeconds(secs, now))
    }
}
