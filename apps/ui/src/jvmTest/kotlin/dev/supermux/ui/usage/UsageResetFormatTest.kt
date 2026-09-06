package dev.supermux.ui.usage

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure usage reset/fetched-at formatters (cluster E6 — moved to `:ui` by name): [formatResetIso] (Claude windows + Cursor `billingCycleEnd`, an
 * ISO-8601 string) and [formatResetEpochSeconds] (Codex windows, a `Double` of epoch SECONDS).
 * Ports Android MoreScreens.kt:998-1021's `formatReset` semantics as two typed entry points.
 *
 * Time deviation, updated for `:ui`: `java.time` cannot come into commonMain, so both formatters
 * take `now` as epoch MILLIS and the one calendar step ("resets Jul 14") goes through `:shared`'s
 * `shortMonthDayLabel`, which is the civil-date maths chat timestamps already use. The tests still
 * build their fixtures with `java.time` — this source set is the JVM one.
 */
class UsageResetFormatTest {

    // Epoch millis, not `java.time.Instant`: the formatters are commonMain now (cluster E6), so
    // `now` is a Long. The fixtures still build their instants with java.time — this is jvmTest.
    private val now = Instant.parse("2026-07-09T12:00:00Z").toEpochMilli()

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
        val millis = now + 3_600_000L // 1h out
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
        val secs = (now - 60_000L) / 1000.0
        assertEquals("resets soon", formatResetEpochSeconds(secs, now))
    }

    // ── formatResetEpochSeconds: < 24h ───────────────────────────────────────────────────────────

    @Test fun format_reset_epoch_seconds_hours_and_minutes_out() {
        val secs = (now + (3600L * 5 + 60 * 40) * 1000L) / 1000.0 // 5h40m
        assertEquals("resets in 5h 40m", formatResetEpochSeconds(secs, now))
    }

    @Test fun format_reset_epoch_seconds_under_an_hour_omits_the_hours_part() {
        val secs = (now + 12 * 60_000L) / 1000.0 // 12m
        assertEquals("resets in 12m", formatResetEpochSeconds(secs, now))
    }

    // ── formatResetEpochSeconds: >= 24h ──────────────────────────────────────────────────────────

    @Test fun format_reset_epoch_seconds_days_out_shows_short_month_and_day() {
        val secs = Instant.parse("2026-08-01T00:00:00Z").epochSecond.toDouble()
        assertEquals("resets Aug 1", formatResetEpochSeconds(secs, now))
    }

    // ── formatFetchedAt: relative "as of …" caption from ISO snapshot times ───────────────────────

    @Test fun format_fetched_at_null_or_blank_is_blank() {
        assertEquals("", formatFetchedAt(null, now))
        assertEquals("", formatFetchedAt("   ", now))
        assertEquals("", formatFetchedAt("not-a-date", now))
    }

    @Test fun format_fetched_at_under_a_minute_is_just_now() {
        assertEquals("as of just now", formatFetchedAt("2026-07-09T11:59:30Z", now))
        assertEquals("as of just now", formatFetchedAt("2026-07-09T12:00:00Z", now))
    }

    @Test fun format_fetched_at_minutes_hours_days() {
        assertEquals("as of 5m ago", formatFetchedAt("2026-07-09T11:55:00Z", now))
        assertEquals("as of 2h ago", formatFetchedAt("2026-07-09T10:00:00Z", now))
        assertEquals("as of 2d ago", formatFetchedAt("2026-07-07T12:00:00Z", now))
    }
}
